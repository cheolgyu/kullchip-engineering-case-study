package com.kullchip.next.wear.spool

import com.kullchip.next.core.sensor.WearSensorWireCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.TreeMap

class SegmentedWearBatchSpool(
    private val directory: File,
    private val maximumPendingBatches: Int = FileWearBatchSpool.DEFAULT_MAXIMUM_PENDING_BATCHES,
    private val maximumPendingWireBytes: Long = FileWearBatchSpool.DEFAULT_MAXIMUM_PENDING_WIRE_BYTES,
    private val maximumRecordsPerSegment: Int = DEFAULT_RECORDS_PER_SEGMENT,
    private val syncEveryRecords: Int = DEFAULT_SYNC_EVERY_RECORDS,
    private val syncIntervalMillis: Long = DEFAULT_SYNC_INTERVAL_MILLIS,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : WearBatchSpool {
    private data class Segment(
        val file: File,
        val firstOrdinal: Long,
        var recordCount: Int,
        var pendingRecordCount: Int,
        var byteCount: Long,
        var sealed: Boolean,
        var output: FileOutputStream? = null,
        var unsyncedRecordCount: Int = 0,
        var lastSyncedAtMillis: Long = 0L,
    )

    private data class Metadata(
        val batchId: String,
        val ordinal: Long,
        val payloadHash: String,
        val payloadSize: Int,
        val frameOffset: Long,
        val frameSize: Int,
        val segment: Segment,
    )

    private data class DecodedRecord(
        val record: WearSpoolRecord,
        val payloadHash: String,
    )

    private data class RecoveredRecord(
        val decoded: DecodedRecord,
        val frameOffset: Long,
        val frameSize: Int,
    )

    private val byOrdinal = TreeMap<Long, Metadata>()
    private val byBatchId = mutableMapOf<String, Metadata>()
    private val segments = mutableListOf<Segment>()
    private var activeSegment: Segment? = null
    private var oldestCache: WearSpoolRecord? = null
    private var pendingWireBytes = 0L
    private var corruptFileCount = 0
    private var nextOrdinal = 0L

    init {
        require(maximumPendingBatches > 0) { "maximumPendingBatches must be positive" }
        require(maximumPendingWireBytes >= WearSensorWireCodec.MAX_WIRE_BYTES) {
            "maximumPendingWireBytes must hold at least one maximum wire batch"
        }
        require(maximumRecordsPerSegment > 1) { "maximumRecordsPerSegment must exceed one" }
        require(syncEveryRecords in 1..maximumRecordsPerSegment) {
            "syncEveryRecords must fit the segment"
        }
        require(syncIntervalMillis > 0L) { "syncIntervalMillis must be positive" }
        require(directory.exists() || directory.mkdirs()) {
            "wear_segment_directory_unavailable:${directory.path}"
        }
        require(directory.isDirectory) { "wear_segment_path_not_directory:${directory.path}" }
        recover()
    }

    @Synchronized
    override fun enqueue(batchId: String, wirePayload: ByteArray): Result<WearSpoolRecord> = runCatching {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(wirePayload.isNotEmpty()) { "wirePayload must not be empty" }
        require(wirePayload.size <= WearSensorWireCodec.MAX_WIRE_BYTES) {
            "wirePayload exceeds ${WearSensorWireCodec.MAX_WIRE_BYTES} bytes"
        }
        val payloadHash = sha256(wirePayload)
        byBatchId[batchId]?.let { existing ->
            check(existing.payloadHash == payloadHash && existing.payloadSize == wirePayload.size) {
                "wear_segment_batch_id_collision:$batchId"
            }
            val existingRecord = readRecord(existing)
            check(existingRecord.copyPayload().contentEquals(wirePayload)) {
                "wear_segment_batch_hash_collision:$batchId"
            }
            return@runCatching existingRecord
        }
        if (byOrdinal.size >= maximumPendingBatches) {
            throw SpoolCapacityExceededException("wear_segment_batch_capacity:$maximumPendingBatches")
        }
        if (pendingWireBytes + wirePayload.size > maximumPendingWireBytes) {
            throw SpoolCapacityExceededException(
                "wear_segment_byte_capacity:$pendingWireBytes:${wirePayload.size}:$maximumPendingWireBytes",
            )
        }

        val ordinal = nextOrdinal++
        val body = encodeRecord(batchId, ordinal, payloadHash, wirePayload)
        val frame = ByteArrayOutputStream(body.size + Int.SIZE_BYTES).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(body.size)
                output.write(body)
            }
            buffer.toByteArray()
        }
        val segment = writableSegment(ordinal, frame.size)
        val frameOffset = segment.byteCount
        val output = requireNotNull(segment.output) { "wear_segment_output_closed:${segment.file.name}" }
        output.write(frame)
        segment.byteCount += frame.size
        segment.recordCount += 1
        segment.pendingRecordCount += 1
        segment.unsyncedRecordCount += 1
        val metadata = Metadata(
            batchId = batchId,
            ordinal = ordinal,
            payloadHash = payloadHash,
            payloadSize = wirePayload.size,
            frameOffset = frameOffset,
            frameSize = frame.size,
            segment = segment,
        )
        val wasEmpty = byOrdinal.isEmpty()
        byOrdinal[ordinal] = metadata
        byBatchId[batchId] = metadata
        pendingWireBytes += wirePayload.size
        if (wasEmpty) oldestCache = WearSpoolRecord(batchId, ordinal, wirePayload)

        val now = monotonicMillis()
        if (
            segment.unsyncedRecordCount >= syncEveryRecords ||
            now - segment.lastSyncedAtMillis >= syncIntervalMillis
        ) {
            sync(segment, now)
        }
        if (
            segment.recordCount >= maximumRecordsPerSegment ||
            segment.byteCount + MAX_FRAMED_RECORD_BYTES > maximumSegmentFileBytes()
        ) {
            seal(segment)
        }
        WearSpoolRecord(batchId, ordinal, wirePayload)
    }

    @Synchronized
    override fun oldest(): Result<WearSpoolRecord?> = runCatching {
        val metadata = byOrdinal.firstEntry()?.value ?: return@runCatching null
        oldestCache
            ?.takeIf { cached -> cached.batchId == metadata.batchId && cached.ordinal == metadata.ordinal }
            ?: readRecord(metadata).also { record -> oldestCache = record }
    }

    @Synchronized
    override fun acknowledge(batchId: String): Result<Boolean> = runCatching {
        val metadata = byBatchId[batchId] ?: return@runCatching false
        if (byOrdinal.firstEntry()?.value !== metadata) return@runCatching false
        byBatchId.remove(batchId)
        byOrdinal.remove(metadata.ordinal)
        pendingWireBytes -= metadata.payloadSize
        metadata.segment.pendingRecordCount -= 1
        check(metadata.segment.pendingRecordCount >= 0) {
            "wear_segment_pending_count_negative:${metadata.segment.file.name}"
        }
        if (oldestCache?.batchId == batchId) oldestCache = null
        if (metadata.segment.sealed && metadata.segment.pendingRecordCount == 0) {
            deleteSegment(metadata.segment)
        }
        true
    }

    @Synchronized
    override fun flush(): Result<Unit> = runCatching {
        activeSegment?.let(::seal)
        segments
            .filter { segment -> segment.sealed && segment.pendingRecordCount == 0 }
            .toList()
            .forEach(::deleteSegment)
    }

    @Synchronized
    override fun snapshot(): WearSpoolSnapshot = WearSpoolSnapshot(
        pendingBatchCount = byOrdinal.size,
        pendingWireBytes = pendingWireBytes,
        corruptFileCount = corruptFileCount,
        oldestOrdinal = byOrdinal.firstKeyOrNull(),
        newestOrdinal = byOrdinal.lastKeyOrNull(),
    )

    private fun writableSegment(ordinal: Long, frameSize: Int): Segment {
        require(frameSize <= MAX_FRAMED_RECORD_BYTES) { "wear_segment_frame_too_large:$frameSize" }
        activeSegment?.let { active ->
            if (
                !active.sealed &&
                active.recordCount < maximumRecordsPerSegment &&
                active.byteCount + frameSize <= maximumSegmentFileBytes()
            ) {
                return active
            }
            seal(active)
        }
        val target = directory.resolve(segmentFileName(ordinal))
        check(!target.exists()) { "wear_segment_file_collision:${target.name}" }
        val output = FileOutputStream(target, false)
        DataOutputStream(output).apply {
            writeInt(SEGMENT_MAGIC)
            writeInt(SEGMENT_VERSION)
            writeLong(ordinal)
            flush()
        }
        return Segment(
            file = target,
            firstOrdinal = ordinal,
            recordCount = 0,
            pendingRecordCount = 0,
            byteCount = SEGMENT_HEADER_BYTES.toLong(),
            sealed = false,
            output = output,
            lastSyncedAtMillis = monotonicMillis(),
        ).also { segment ->
            segments += segment
            activeSegment = segment
        }
    }

    private fun sync(segment: Segment, nowMillis: Long = monotonicMillis()) {
        if (segment.unsyncedRecordCount == 0) return
        val output = requireNotNull(segment.output) { "wear_segment_sync_closed:${segment.file.name}" }
        output.flush()
        output.fd.sync()
        segment.unsyncedRecordCount = 0
        segment.lastSyncedAtMillis = nowMillis
    }

    private fun seal(segment: Segment) {
        if (segment.sealed) return
        sync(segment)
        segment.output?.close()
        segment.output = null
        segment.sealed = true
        if (activeSegment === segment) activeSegment = null
        if (segment.pendingRecordCount == 0) deleteSegment(segment)
    }

    private fun deleteSegment(segment: Segment) {
        check(segment.pendingRecordCount == 0) { "wear_segment_delete_pending:${segment.file.name}" }
        segment.output?.close()
        segment.output = null
        check(segment.file.delete() || !segment.file.exists()) {
            "wear_segment_delete_failed:${segment.file.name}"
        }
        segments.remove(segment)
        if (activeSegment === segment) activeSegment = null
    }

    private fun recover() {
        directory.listFiles().orEmpty()
            .filter { file -> file.name.endsWith(TEMP_SUFFIX) }
            .forEach { file -> if (!file.delete()) quarantine(file) }
        val recovered = directory.listFiles().orEmpty()
            .filter { file -> file.name.endsWith(SEGMENT_SUFFIX) }
            .sortedBy(File::getName)
            .mapNotNull { file ->
                runCatching { recoverSegment(file) }.fold(
                    onSuccess = { it },
                    onFailure = {
                        quarantine(file)
                        null
                    },
                )
            }
        recovered.forEachIndexed { index, recoveredSegment ->
            val segment = recoveredSegment.first
            val records = recoveredSegment.second
            val duplicate = records.any { recoveredRecord ->
                val record = recoveredRecord.decoded.record
                byOrdinal.containsKey(record.ordinal) || byBatchId.containsKey(record.batchId)
            }
            if (duplicate) {
                quarantine(segment.file)
                return@forEachIndexed
            }
            if (byOrdinal.size + records.size > maximumPendingBatches) {
                throw SpoolCapacityExceededException(
                    "wear_segment_recovery_batch_capacity:$maximumPendingBatches",
                )
            }
            val addedBytes = records.sumOf { recoveredRecord ->
                recoveredRecord.decoded.record.byteCount.toLong()
            }
            if (pendingWireBytes + addedBytes > maximumPendingWireBytes) {
                throw SpoolCapacityExceededException(
                    "wear_segment_recovery_byte_capacity:$pendingWireBytes:$addedBytes:$maximumPendingWireBytes",
                )
            }
            segment.sealed = index != recovered.lastIndex ||
                segment.recordCount >= maximumRecordsPerSegment ||
                segment.byteCount + MAX_FRAMED_RECORD_BYTES > maximumSegmentFileBytes()
            segment.pendingRecordCount = records.size
            records.forEach { recoveredRecord ->
                val decoded = recoveredRecord.decoded
                val record = decoded.record
                val metadata = Metadata(
                    batchId = record.batchId,
                    ordinal = record.ordinal,
                    payloadHash = decoded.payloadHash,
                    payloadSize = record.byteCount,
                    frameOffset = recoveredRecord.frameOffset,
                    frameSize = recoveredRecord.frameSize,
                    segment = segment,
                )
                byOrdinal[record.ordinal] = metadata
                byBatchId[record.batchId] = metadata
                pendingWireBytes += record.byteCount
                nextOrdinal = maxOf(nextOrdinal, record.ordinal + 1L)
            }
            segments += segment
            if (!segment.sealed) {
                segment.output = FileOutputStream(segment.file, true)
                segment.lastSyncedAtMillis = monotonicMillis()
                activeSegment = segment
            }
        }
    }

    private fun recoverSegment(file: File): Pair<Segment, List<RecoveredRecord>> {
        val recoveredRecords = mutableListOf<RecoveredRecord>()
        RandomAccessFile(file, "rw").use { input ->
            require(input.length() >= SEGMENT_HEADER_BYTES) { "wear_segment_header_short:${file.name}" }
            check(input.readInt() == SEGMENT_MAGIC) { "wear_segment_magic_mismatch:${file.name}" }
            check(input.readInt() == SEGMENT_VERSION) { "wear_segment_version_unsupported:${file.name}" }
            val firstOrdinal = input.readLong()
            require(firstOrdinal >= 0L) { "wear_segment_first_ordinal:${file.name}:$firstOrdinal" }
            var expectedOrdinal = firstOrdinal
            while (input.filePointer < input.length()) {
                val frameOffset = input.filePointer
                val remaining = input.length() - frameOffset
                if (remaining < Int.SIZE_BYTES) {
                    input.setLength(frameOffset)
                    corruptFileCount += 1
                    break
                }
                val bodySize = input.readInt()
                require(bodySize in 1..MAX_RECORD_BODY_BYTES) {
                    "wear_segment_record_size:${file.name}:$bodySize"
                }
                if (input.filePointer + bodySize > input.length()) {
                    input.setLength(frameOffset)
                    corruptFileCount += 1
                    break
                }
                val body = ByteArray(bodySize)
                input.readFully(body)
                val record = decodeRecord(body, file.name)
                check(record.record.ordinal == expectedOrdinal) {
                    "wear_segment_ordinal_gap:${file.name}:$expectedOrdinal:${record.record.ordinal}"
                }
                expectedOrdinal += 1L
                recoveredRecords += RecoveredRecord(
                    decoded = record,
                    frameOffset = frameOffset,
                    frameSize = bodySize + Int.SIZE_BYTES,
                )
            }
            val segment = Segment(
                file = file,
                firstOrdinal = firstOrdinal,
                recordCount = recoveredRecords.size,
                pendingRecordCount = recoveredRecords.size,
                byteCount = input.length(),
                sealed = true,
            )
            return segment to recoveredRecords
        }
    }

    private fun readRecord(metadata: Metadata): WearSpoolRecord {
        RandomAccessFile(metadata.segment.file, "r").use { input ->
            input.seek(metadata.frameOffset)
            val bodySize = input.readInt()
            check(bodySize + Int.SIZE_BYTES == metadata.frameSize) {
                "wear_segment_metadata_changed:${metadata.segment.file.name}:${metadata.batchId}"
            }
            val body = ByteArray(bodySize)
            input.readFully(body)
            val decoded = decodeRecord(body, metadata.segment.file.name)
            check(
                decoded.record.batchId == metadata.batchId &&
                    decoded.record.ordinal == metadata.ordinal &&
                    decoded.payloadHash == metadata.payloadHash
            ) { "wear_segment_record_changed:${metadata.segment.file.name}:${metadata.batchId}" }
            return decoded.record
        }
    }

    private fun encodeRecord(
        batchId: String,
        ordinal: Long,
        payloadHash: String,
        payload: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(RECORD_MAGIC)
            output.writeInt(RECORD_VERSION)
            output.writeLong(ordinal)
            output.writeBoundedString(batchId)
            output.writeBoundedString(payloadHash)
            output.writeInt(payload.size)
            output.write(payload)
        }
        buffer.toByteArray()
    }

    private fun decodeRecord(bytes: ByteArray, sourceName: String): DecodedRecord =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == RECORD_MAGIC) { "wear_segment_record_magic:$sourceName" }
            check(input.readInt() == RECORD_VERSION) { "wear_segment_record_version:$sourceName" }
            val ordinal = input.readLong()
            val batchId = input.readBoundedString()
            val expectedHash = input.readBoundedString()
            val payloadSize = input.readInt()
            require(payloadSize in 1..WearSensorWireCodec.MAX_WIRE_BYTES) {
                "wear_segment_payload_size:$sourceName:$payloadSize"
            }
            val payload = ByteArray(payloadSize)
            input.readFully(payload)
            check(input.available() == 0) { "wear_segment_trailing_bytes:$sourceName" }
            check(sha256(payload) == expectedHash) { "wear_segment_hash_mismatch:$sourceName" }
            DecodedRecord(WearSpoolRecord(batchId, ordinal, payload), expectedHash)
        }

    private fun quarantine(file: File) {
        corruptFileCount += 1
        val quarantine = directory.resolve("${file.name}.${System.nanoTime()}$CORRUPT_SUFFIX")
        if (!file.renameTo(quarantine)) {
            // Leave the source visible when quarantine itself is unavailable.
        }
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_HEADER_STRING_BYTES) { "wear_segment_string_size:${bytes.size}" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(): String {
        val length = readInt()
        require(length in 1..MAX_HEADER_STRING_BYTES) { "wear_segment_string_size:$length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun segmentFileName(firstOrdinal: Long): String =
        "%020d%s".format(firstOrdinal, SEGMENT_SUFFIX)

    private fun maximumSegmentFileBytes(): Long =
        SEGMENT_HEADER_BYTES + maximumRecordsPerSegment.toLong() * MAX_FRAMED_RECORD_BYTES

    private fun <K, V> TreeMap<K, V>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()

    private fun <K, V> TreeMap<K, V>.lastKeyOrNull(): K? = if (isEmpty()) null else lastKey()

    companion object {
        const val DEFAULT_RECORDS_PER_SEGMENT = 10
        const val DEFAULT_SYNC_EVERY_RECORDS = 5
        const val DEFAULT_SYNC_INTERVAL_MILLIS = 5_000L
        private const val SEGMENT_MAGIC = 0x4B_53_47_38
        private const val SEGMENT_VERSION = 1
        private const val RECORD_MAGIC = 0x4B_53_52_38
        private const val RECORD_VERSION = 1
        private const val SEGMENT_HEADER_BYTES = Int.SIZE_BYTES * 2 + Long.SIZE_BYTES
        private const val MAX_HEADER_STRING_BYTES = 4 * 1024
        private const val MAX_RECORD_BODY_BYTES = WearSensorWireCodec.MAX_WIRE_BYTES + 16 * 1024
        private const val MAX_FRAMED_RECORD_BYTES = MAX_RECORD_BODY_BYTES + Int.SIZE_BYTES
        private const val SEGMENT_SUFFIX = ".wseg"
        private const val TEMP_SUFFIX = ".tmp"
        private const val CORRUPT_SUFFIX = ".corrupt"
    }
}

class MigratingWearBatchSpool(
    private val legacy: WearBatchSpool,
    private val current: WearBatchSpool,
    private val maximumPendingBatches: Int = FileWearBatchSpool.DEFAULT_MAXIMUM_PENDING_BATCHES,
    private val maximumPendingWireBytes: Long = FileWearBatchSpool.DEFAULT_MAXIMUM_PENDING_WIRE_BYTES,
) : WearBatchSpool {
    @Synchronized
    override fun enqueue(batchId: String, wirePayload: ByteArray): Result<WearSpoolRecord> = runCatching {
        val snapshot = snapshot()
        if (snapshot.pendingBatchCount >= maximumPendingBatches) {
            throw SpoolCapacityExceededException("wear_migrating_batch_capacity:$maximumPendingBatches")
        }
        if (snapshot.pendingWireBytes + wirePayload.size > maximumPendingWireBytes) {
            throw SpoolCapacityExceededException(
                "wear_migrating_byte_capacity:${snapshot.pendingWireBytes}:${wirePayload.size}:" +
                    maximumPendingWireBytes,
            )
        }
        current.enqueue(batchId, wirePayload).getOrThrow()
    }

    @Synchronized
    override fun oldest(): Result<WearSpoolRecord?> = runCatching {
        legacy.oldest().getOrThrow() ?: current.oldest().getOrThrow()
    }

    @Synchronized
    override fun acknowledge(batchId: String): Result<Boolean> = runCatching {
        val oldest = oldest().getOrThrow() ?: return@runCatching false
        if (oldest.batchId != batchId) return@runCatching false
        if (legacy.acknowledge(batchId).getOrThrow()) true else current.acknowledge(batchId).getOrThrow()
    }

    @Synchronized
    override fun flush(): Result<Unit> = runCatching {
        legacy.flush().getOrThrow()
        current.flush().getOrThrow()
    }

    @Synchronized
    override fun snapshot(): WearSpoolSnapshot {
        val old = legacy.snapshot()
        val next = current.snapshot()
        return WearSpoolSnapshot(
            pendingBatchCount = old.pendingBatchCount + next.pendingBatchCount,
            pendingWireBytes = old.pendingWireBytes + next.pendingWireBytes,
            corruptFileCount = old.corruptFileCount + next.corruptFileCount,
            oldestOrdinal = old.oldestOrdinal ?: next.oldestOrdinal,
            newestOrdinal = next.newestOrdinal ?: old.newestOrdinal,
        )
    }
}
