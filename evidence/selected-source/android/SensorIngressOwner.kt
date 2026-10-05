package com.kullchip.next.mobile.ingress

import com.kullchip.next.core.runtime.RuntimeIssuePort
import com.kullchip.next.core.runtime.WalkId
import com.kullchip.next.core.runtime.WalkRuntimeEffect
import com.kullchip.next.core.sensor.EpochMillisClock
import com.kullchip.next.core.sensor.RawPacketJournal
import com.kullchip.next.core.sensor.RawSensorPacket
import com.kullchip.next.core.sensor.SensorBatchId
import com.kullchip.next.core.sensor.SensorBatchIdFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SensorIngressOwner(
    parentScope: CoroutineScope,
    private val journal: RawPacketJournal,
    private val batchIdFactory: SensorBatchIdFactory,
    private val clock: EpochMillisClock,
    private val issuePort: RuntimeIssuePort,
    private val onFatalCommitFailure: suspend (WalkId, String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE,
    private val maxBatchAgeMillis: Long = DEFAULT_MAX_BATCH_AGE_MILLIS,
    private val commitTimeoutMillis: Long = DEFAULT_COMMIT_TIMEOUT_MILLIS,
    inputCapacity: Int = DEFAULT_INPUT_CAPACITY,
) {
    enum class SubmissionResult {
        ACCEPTED,
        BACKPRESSURED,
        CLOSED,
    }

    private sealed interface Message {
        data class Packet(val packet: RawSensorPacket) : Message

        data class DurableBatch(
            val batchId: SensorBatchId?,
            val packets: List<RawSensorPacket>,
            val result: CompletableDeferred<Result<com.kullchip.next.core.sensor.CommittedSensorBatch>>,
        ) : Message

        data class Drain(
            val walkId: WalkId,
            val result: CompletableDeferred<Result<Unit>>,
        ) : Message
    }

    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(
        parentScope.coroutineContext + ownerJob + dispatcher + CoroutineName("SensorIngressOwner"),
    )
    private val inputs = Channel<Message>(capacity = inputCapacity)
    private var unacknowledgedFailure: Throwable? = null
    private val rejectedSubmissionCount = AtomicLong(0L)
    private val rejectionReportScheduled = AtomicBoolean(false)

    init {
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
        require(maxBatchAgeMillis > 0L) { "maxBatchAgeMillis must be positive" }
        require(commitTimeoutMillis > 0L) { "commitTimeoutMillis must be positive" }
        require(inputCapacity > 0) { "inputCapacity must be bounded and positive" }
        ownerScope.launch { runLoop() }
    }

    suspend fun submit(packet: RawSensorPacket) {
        inputs.send(Message.Packet(packet))
    }

    suspend fun submitDurableBatch(
        packets: List<RawSensorPacket>,
    ): Result<com.kullchip.next.core.sensor.CommittedSensorBatch> =
        submitDurableBatchInternal(batchId = null, packets = packets)

    suspend fun submitDurableBatch(
        batchId: SensorBatchId,
        packets: List<RawSensorPacket>,
    ): Result<com.kullchip.next.core.sensor.CommittedSensorBatch> =
        submitDurableBatchInternal(batchId = batchId, packets = packets)

    private suspend fun submitDurableBatchInternal(
        batchId: SensorBatchId?,
        packets: List<RawSensorPacket>,
    ): Result<com.kullchip.next.core.sensor.CommittedSensorBatch> {
        require(packets.isNotEmpty()) { "durable batch must not be empty" }
        require(packets.size <= MAX_DURABLE_BATCH_SIZE) {
            "durable batch exceeds $MAX_DURABLE_BATCH_SIZE packets"
        }
        val walkId = packets.first().walkId
        require(packets.all { it.walkId == walkId }) { "durable batch cannot mix walks" }
        val result = CompletableDeferred<Result<com.kullchip.next.core.sensor.CommittedSensorBatch>>()
        try {
            inputs.send(Message.DurableBatch(batchId, packets.toList(), result))
        } catch (_: ClosedSendChannelException) {
            return Result.failure(IllegalStateException("raw_ingress_closed"))
        }
        return result.await()
    }

    fun trySubmit(packet: RawSensorPacket): SubmissionResult {
        val result = inputs.trySend(Message.Packet(packet))
        if (result.isSuccess) return SubmissionResult.ACCEPTED
        val status = if (result.isClosed) SubmissionResult.CLOSED else SubmissionResult.BACKPRESSURED
        scheduleRejectedSubmissionReport(packet.walkId, status)
        return status
    }

    suspend fun drain(walkId: WalkId): Result<Unit> {
        val result = CompletableDeferred<Result<Unit>>()
        inputs.send(Message.Drain(walkId, result))
        return result.await()
    }

    fun cancel() {
        inputs.close()
        ownerJob.cancel()
    }

    private suspend fun runLoop() {
        var carry: Message? = null
        try {
            while (true) {
                val first = carry ?: inputs.receiveCatching().getOrNull() ?: break
                carry = null
                when (first) {
                    is Message.Drain -> completeDrain(first)
                    is Message.DurableBatch -> first.result.complete(commit(first.packets, first.batchId))
                    is Message.Packet -> {
                        val collected = collectBatch(first.packet)
                        carry = collected.carry
                        commit(collected.packets)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            unacknowledgedFailure = unacknowledgedFailure ?: error
            recordIssue(null, "raw_ingress_loop_failed", error.reason())
        }
    }

    private data class CollectedBatch(
        val packets: List<RawSensorPacket>,
        val carry: Message?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectBatch(first: RawSensorPacket): CollectedBatch {
        val packets = ArrayList<RawSensorPacket>(maxBatchSize)
        packets += first
        var carry: Message? = null
        val deadlineNanos = System.nanoTime() + maxBatchAgeMillis * NANOS_PER_MILLISECOND
        while (packets.size < maxBatchSize) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) break
            val next = select<Message?> {
                inputs.onReceiveCatching { result -> result.getOrNull() }
                onTimeout((remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L)) { null }
            } ?: break
            when (next) {
                is Message.Drain -> {
                    carry = next
                    break
                }
                is Message.DurableBatch -> {
                    carry = next
                    break
                }
                is Message.Packet -> {
                    if (next.packet.walkId == first.walkId) {
                        packets += next.packet
                    } else {
                        carry = next
                        break
                    }
                }
            }
        }
        return CollectedBatch(packets, carry)
    }

    private suspend fun commit(
        packets: List<RawSensorPacket>,
        suppliedBatchId: SensorBatchId? = null,
    ): Result<com.kullchip.next.core.sensor.CommittedSensorBatch> {
        val committed = try {
            var result: Result<com.kullchip.next.core.sensor.CommittedSensorBatch>? = null
            val completed = withTimeoutOrNull(commitTimeoutMillis) {
                result = journal.commit(
                    batchId = suppliedBatchId ?: batchIdFactory.next(),
                    packets = packets,
                    committedAtEpochMillis = clock.now(),
                )
                true
            } == true
            if (completed) {
                requireNotNull(result)
            } else {
                Result.failure(
                    IllegalStateException("raw_commit_timeout:${commitTimeoutMillis}ms"),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        committed.fold(
            onSuccess = { batch ->
                if (batch.unverifiedDuplicateCount > 0) {
                    recordIssue(
                        batch.walkId,
                        "raw_unverified_duplicate",
                        batch.unverifiedDuplicateCount.toString(),
                    )
                }
                if (batch.sequenceGapCount > 0L) {
                    recordIssue(batch.walkId, "raw_sequence_gap", batch.sequenceGapCount.toString())
                }
            },
            onFailure = { error ->
                unacknowledgedFailure = unacknowledgedFailure ?: error
                val walkId = packets.first().walkId
                recordIssue(walkId, "raw_commit_failed", error.reason())
                try {
                    onFatalCommitFailure(walkId, error.reason())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (callbackError: Throwable) {
                    recordIssue(walkId, "raw_failure_callback_failed", callbackError.reason())
                }
            },
        )
        return committed
    }

    private fun scheduleRejectedSubmissionReport(walkId: WalkId, status: SubmissionResult) {
        rejectedSubmissionCount.incrementAndGet()
        scheduleRejectedSubmissionReportIfNeeded(walkId, status)
    }

    private fun scheduleRejectedSubmissionReportIfNeeded(walkId: WalkId, status: SubmissionResult) {
        if (!rejectionReportScheduled.compareAndSet(false, true)) return
        ownerScope.launch {
            delay(REJECTION_REPORT_INTERVAL_MILLIS)
            val count = rejectedSubmissionCount.getAndSet(0L)
            recordIssue(
                walkId,
                "raw_ingress_${status.name.lowercase()}",
                "count=$count",
            )
            rejectionReportScheduled.set(false)
            if (rejectedSubmissionCount.get() > 0L) {
                scheduleRejectedSubmissionReportIfNeeded(walkId, status)
            }
        }
    }

    private fun completeDrain(message: Message.Drain) {
        val failure = unacknowledgedFailure
        unacknowledgedFailure = null
        message.result.complete(failure?.let(Result.Companion::failure) ?: Result.success(Unit))
    }

    private suspend fun recordIssue(walkId: WalkId?, code: String, detail: String) {
        try {
            issuePort.record(
                WalkRuntimeEffect.RecordIssue(
                    operationId = 0L,
                    code = code,
                    detail = detail,
                    walkId = walkId,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Raw persistence and shutdown signaling must continue without diagnostics storage.
        }
    }

    private fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() }
        ?: this::class.simpleName
        ?: "unknown"

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEFAULT_MAX_BATCH_SIZE: Int = 10
        const val DEFAULT_MAX_BATCH_AGE_MILLIS: Long = 1_000L
        const val DEFAULT_COMMIT_TIMEOUT_MILLIS: Long = 5_000L
        const val DEFAULT_INPUT_CAPACITY: Int = 2_048
        const val MAX_DURABLE_BATCH_SIZE: Int = 128
        private const val REJECTION_REPORT_INTERVAL_MILLIS: Long = 1_000L
    }
}
