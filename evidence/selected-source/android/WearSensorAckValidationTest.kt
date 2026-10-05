package com.kullchip.next.wear.transport

import com.kullchip.next.core.runtime.PetId
import com.kullchip.next.core.runtime.WalkId
import com.kullchip.next.core.sensor.DecodeStatus
import com.kullchip.next.core.sensor.PacketId
import com.kullchip.next.core.sensor.RawPacketOrigin
import com.kullchip.next.core.sensor.RawPayload
import com.kullchip.next.core.sensor.RawSensorPacket
import com.kullchip.next.core.sensor.SensorStream
import com.kullchip.next.core.sensor.WearRoomBatchIdentity
import com.kullchip.next.core.sensor.WearSensorAck
import com.kullchip.next.core.sensor.WearSensorBatch
import com.kullchip.next.core.sensor.WearSensorWireCodec
import com.kullchip.next.wear.spool.WearSpoolRecord
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSensorAckValidationTest {
    @Test
    fun acceptsOnlyTheRoomIdentityForTheCurrentFifoHeadAndWalk() {
        val oldest = record("batch-1", WALK)
        val valid = ack("batch-1", WALK)

        assertTrue(validateWearSensorAck(valid, oldest).isSuccess)
        assertFailure(validateWearSensorAck(ack("batch-2", WALK), oldest), "wear_ack_not_oldest")
        assertFailure(validateWearSensorAck(ack("batch-1", WalkId("other")), oldest), "wear_ack_walk_mismatch")
        assertFailure(
            validateWearSensorAck(valid.copy(roomBatchId = "wrong-room-batch"), oldest),
            "wear_ack_room_batch_mismatch",
        )
        assertFailure(validateWearSensorAck(valid, null), "wear_ack_without_pending_batch")
    }

    private fun record(batchId: String, walkId: WalkId): WearSpoolRecord {
        val packet = RawSensorPacket(
            packetId = PacketId("packet-$batchId"),
            walkId = walkId,
            petId = PET,
            stream = SensorStream.PET_WATCH_IMU,
            origin = RawPacketOrigin.LIVE_WEAR,
            sourceDeviceId = "wear-1",
            sourceSessionId = "session-1",
            sequenceNo = 0L,
            observedAtEpochMillis = 1_000L,
            elapsedRealtimeNanos = 1L,
            receivedAtEpochMillis = 1_000L,
            payloadEncoding = "test",
            payload = RawPayload(byteArrayOf(1)),
            decodeStatus = DecodeStatus.MALFORMED,
        )
        val wire = WearSensorWireCodec.encode(
            WearSensorBatch(
                wireBatchId = batchId,
                walkId = walkId,
                petId = PET,
                sourceDeviceId = "wear-1",
                sourceSessionId = "session-1",
                batchSequenceNo = 0L,
                createdAtEpochMillis = 1_000L,
                packets = listOf(packet),
            ),
        ).getOrThrow()
        return WearSpoolRecord(batchId, 0L, wire)
    }

    private fun ack(batchId: String, walkId: WalkId) = WearSensorAck(
        wireBatchId = batchId,
        walkId = walkId,
        roomBatchId = WearRoomBatchIdentity.fromWireBatchId(batchId).value,
        committedAtEpochMillis = 2_000L,
    )

    private fun assertFailure(result: Result<Unit>, reason: String) {
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(reason))
    }

    private companion object {
        val WALK = WalkId("walk-1")
        val PET = PetId("pet-1")
    }
}
