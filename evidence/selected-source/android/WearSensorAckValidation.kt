package com.kullchip.next.wear.transport

import com.kullchip.next.core.sensor.WearRoomBatchIdentity
import com.kullchip.next.core.sensor.WearSensorAck
import com.kullchip.next.core.sensor.WearSensorWireCodec
import com.kullchip.next.wear.spool.WearSpoolRecord

internal fun validateWearSensorAck(
    ack: WearSensorAck,
    oldest: WearSpoolRecord?,
): Result<Unit> = runCatching {
    val record = requireNotNull(oldest) { "wear_ack_without_pending_batch" }
    check(record.batchId == ack.wireBatchId) {
        "wear_ack_not_oldest:${ack.wireBatchId}:${record.batchId}"
    }
    val batch = WearSensorWireCodec.decode(record.copyPayload()).getOrThrow()
    check(batch.wireBatchId == record.batchId) {
        "wear_spool_batch_identity_mismatch:${record.batchId}:${batch.wireBatchId}"
    }
    check(batch.walkId == ack.walkId) {
        "wear_ack_walk_mismatch:${ack.walkId.value}:${batch.walkId.value}"
    }
    val expectedRoomBatchId = WearRoomBatchIdentity.fromWireBatchId(batch.wireBatchId).value
    check(ack.roomBatchId == expectedRoomBatchId) {
        "wear_ack_room_batch_mismatch:${ack.roomBatchId}:$expectedRoomBatchId"
    }
}
