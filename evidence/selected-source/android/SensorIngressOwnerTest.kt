package com.kullchip.next.mobile.ingress

import com.kullchip.next.core.runtime.RuntimeIssuePort
import com.kullchip.next.core.runtime.WalkId
import com.kullchip.next.core.sensor.CommittedSensorBatch
import com.kullchip.next.core.sensor.DecodeStatus
import com.kullchip.next.core.sensor.EpochMillisClock
import com.kullchip.next.core.sensor.GpsSample
import com.kullchip.next.core.sensor.PacketId
import com.kullchip.next.core.sensor.RawPacketJournal
import com.kullchip.next.core.sensor.RawPacketOrigin
import com.kullchip.next.core.sensor.RawPayload
import com.kullchip.next.core.sensor.RawSensorPacket
import com.kullchip.next.core.sensor.SensorBatchId
import com.kullchip.next.core.sensor.SensorBatchIdFactory
import com.kullchip.next.core.sensor.SensorStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorIngressOwnerTest {
    @Test
    fun commitsAtTenPacketsWithoutWaitingForAgeDeadline() = runTest {
        val journal = FakeJournal()
        val ingress = ingress(journal)

        repeat(10) { ingress.submit(packet(sequence = it.toLong())) }
        advanceUntilIdle()

        assertEquals(listOf(10), journal.commits.map { it.size })
        ingress.cancel()
    }

    @Test
    fun drainFlushesShortBatchBeforeAcknowledging() = runTest {
        val journal = FakeJournal()
        val ingress = ingress(journal)
        repeat(3) { ingress.submit(packet(sequence = it.toLong())) }
        val drain = async {
            ingress.drain(WalkId("walk-1"))
        }

        advanceUntilIdle()

        assertTrue(drain.await().isSuccess)
        assertEquals(listOf(3), journal.commits.map { it.size })
        ingress.cancel()
    }

    @Test
    fun oneSecondAgeFlushesLowRatePacket() = runTest {
        val journal = FakeJournal()
        val ingress = ingress(journal)
        ingress.submit(packet(sequence = 0L))

        advanceTimeBy(1_001L)
        advanceUntilIdle()

        assertEquals(listOf(1), journal.commits.map { it.size })
        ingress.cancel()
    }

    @Test
    fun liveAndDatasetOriginsUseSameCommitBoundary() = runTest {
        val journal = FakeJournal()
        val ingress = ingress(journal)
        ingress.submit(packet(sequence = 0L, origin = RawPacketOrigin.LIVE_MOBILE))
        ingress.submit(packet(sequence = 1L, origin = RawPacketOrigin.EXTERNAL_DATASET))
        val drain = async {
            ingress.drain(WalkId("walk-1"))
        }

        advanceUntilIdle()

        assertTrue(drain.await().isSuccess)
        assertEquals(
            listOf(RawPacketOrigin.LIVE_MOBILE, RawPacketOrigin.EXTERNAL_DATASET),
            journal.commits.single().map { it.origin },
        )
        ingress.cancel()
    }

    @Test
    fun durableWearBatchAcknowledgesOnlyAfterJournalCommit() = runTest {
        val journal = FakeJournal(commitGate = CompletableDeferred())
        val ingress = ingress(journal)
        val committed = async {
            ingress.submitDurableBatch((0L until 51L).map(::packet))
        }

        testScheduler.runCurrent()
        assertTrue(!committed.isCompleted)
        assertEquals(listOf(51), journal.commits.map(List<RawSensorPacket>::size))

        journal.commitGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(committed.await().isSuccess)
        ingress.cancel()
    }

    @Test
    fun durableWearBatchPreservesTransportIdempotencyKey() = runTest {
        val journal = FakeJournal()
        val ingress = ingress(journal)
        val suppliedBatchId = SensorBatchId("wear-batch")

        val committed = async {
            ingress.submitDurableBatch(
                batchId = suppliedBatchId,
                packets = (0L until 51L).map(::packet),
            )
        }
        advanceUntilIdle()

        assertTrue(committed.await().isSuccess)
        assertEquals(listOf(suppliedBatchId), journal.batchIds)
        ingress.cancel()
    }

    @Test
    fun failedDurableWearBatchReturnsFailureInsteadOfFalseAck() = runTest {
        val journal = FakeJournal(failure = IllegalStateException("disk_full"))
        val ingress = ingress(journal)
        val committed = async { ingress.submitDurableBatch(listOf(packet(0L))) }

        advanceUntilIdle()

        assertEquals("disk_full", committed.await().exceptionOrNull()?.message)
        ingress.cancel()
    }

    @Test
    fun stalledRawCommitFailsTheBatchAndDrainInsteadOfFreezingIngress() = runTest {
        val journal = FakeJournal(commitGate = CompletableDeferred())
        val fatalFailures = mutableListOf<String>()
        val ingress = ingress(
            journal = journal,
            commitTimeoutMillis = 100L,
            onFatalCommitFailure = { walkId, reason ->
                fatalFailures += "${walkId.value}:$reason"
            },
        )
        val committed = async {
            ingress.submitDurableBatch(listOf(packet(0L)))
        }

        advanceTimeBy(101L)
        advanceUntilIdle()

        assertEquals(
            "raw_commit_timeout:100ms",
            committed.await().exceptionOrNull()?.message,
        )
        assertEquals(
            listOf("walk-1:raw_commit_timeout:100ms"),
            fatalFailures,
        )

        val drain = async { ingress.drain(WalkId("walk-1")) }
        advanceUntilIdle()
        assertEquals(
            "raw_commit_timeout:100ms",
            drain.await().exceptionOrNull()?.message,
        )
        ingress.cancel()
    }

    @Test
    fun timeoutBoundaryNeverDequeuesAndDropsPacket() = runTest {
        val journal = FakeJournal()
        val ingress = ingress(journal, maxBatchAgeMillis = 1L, maxBatchSize = 10)

        repeat(2_000) { sequence ->
            ingress.submit(packet(sequence.toLong()))
            advanceTimeBy(1L)
            testScheduler.runCurrent()
        }
        val drain = async { ingress.drain(WalkId("walk-1")) }
        advanceUntilIdle()

        assertTrue(drain.await().isSuccess)
        assertEquals((0L until 2_000L).toList(), journal.commits.flatten().map { it.sequenceNo })
        ingress.cancel()
    }

    private fun kotlinx.coroutines.test.TestScope.ingress(
        journal: FakeJournal,
        maxBatchAgeMillis: Long = 1_000L,
        maxBatchSize: Int = 10,
        commitTimeoutMillis: Long = SensorIngressOwner.DEFAULT_COMMIT_TIMEOUT_MILLIS,
        onFatalCommitFailure: suspend (WalkId, String) -> Unit = { _, _ -> },
    ): SensorIngressOwner {
        var batch = 0
        return SensorIngressOwner(
            parentScope = this,
            journal = journal,
            batchIdFactory = SensorBatchIdFactory { SensorBatchId("batch-${batch++}") },
            clock = EpochMillisClock { 1_000L },
            issuePort = RuntimeIssuePort {},
            onFatalCommitFailure = onFatalCommitFailure,
            dispatcher = StandardTestDispatcher(testScheduler),
            maxBatchSize = maxBatchSize,
            maxBatchAgeMillis = maxBatchAgeMillis,
            commitTimeoutMillis = commitTimeoutMillis,
            inputCapacity = 32,
        )
    }

    private class FakeJournal(
        val commitGate: CompletableDeferred<Unit>? = null,
        private val failure: Throwable? = null,
    ) : RawPacketJournal {
        val commits = mutableListOf<List<RawSensorPacket>>()
        val batchIds = mutableListOf<SensorBatchId>()

        override suspend fun commit(
            batchId: SensorBatchId,
            packets: List<RawSensorPacket>,
            committedAtEpochMillis: Long,
        ): Result<CommittedSensorBatch> {
            batchIds += batchId
            commits += packets
            commitGate?.await()
            failure?.let { return Result.failure(it) }
            return Result.success(
                CommittedSensorBatch(
                    batchId = batchId,
                    walkId = packets.first().walkId,
                    packets = packets,
                    duplicateCount = 0,
                    sequenceGapCount = 0L,
                ),
            )
        }
    }

    private fun packet(
        sequence: Long,
        origin: RawPacketOrigin = RawPacketOrigin.LIVE_MOBILE,
    ) = RawSensorPacket(
        packetId = PacketId("packet-$sequence-$origin"),
        walkId = WalkId("walk-1"),
        petId = null,
        stream = SensorStream.MOBILE_GPS,
        origin = origin,
        sourceDeviceId = "mobile-1",
        sourceSessionId = "session-1",
        sequenceNo = sequence,
        observedAtEpochMillis = sequence,
        elapsedRealtimeNanos = sequence,
        receivedAtEpochMillis = sequence,
        payloadEncoding = "test-v1",
        payload = RawPayload(byteArrayOf(sequence.toByte())),
        decodeStatus = DecodeStatus.DECODED,
        gps = GpsSample(
            provider = "gps",
            latitude = 37.0,
            longitude = 127.0,
            accuracyMeters = 1f,
            speedMetersPerSecond = 1f,
            bearingDegrees = 0f,
            altitudeMeters = 0.0,
            verticalAccuracyMeters = null,
            speedAccuracyMetersPerSecond = null,
            bearingAccuracyDegrees = null,
            isMock = false,
        ),
    )
}
