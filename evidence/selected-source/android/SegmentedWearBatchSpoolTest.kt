package com.kullchip.next.wear.spool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentedWearBatchSpoolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun tenBatchesShareOneSegmentAndSurviveReopenInFifoOrder() {
        val directory = temporaryFolder.newFolder("segments")
        val spool = SegmentedWearBatchSpool(directory)
        repeat(10) { index ->
            spool.enqueue("batch-$index", byteArrayOf(index.toByte())).getOrThrow()
        }

        assertEquals(1, segmentFiles(directory).size)
        val reopened = SegmentedWearBatchSpool(directory)
        assertEquals(10, reopened.snapshot().pendingBatchCount)

        repeat(10) { index ->
            val oldest = requireNotNull(reopened.oldest().getOrThrow())
            assertEquals("batch-$index", oldest.batchId)
            assertArrayEquals(byteArrayOf(index.toByte()), oldest.copyPayload())
            assertTrue(reopened.acknowledge(oldest.batchId).getOrThrow())
        }

        assertEquals(0, reopened.snapshot().pendingBatchCount)
        assertTrue(segmentFiles(directory).isEmpty())
    }

    @Test
    fun explicitFlushMakesAPartialSegmentRecoverableAndDeletableAfterAck() {
        val directory = temporaryFolder.newFolder("segments")
        val spool = SegmentedWearBatchSpool(directory)
        spool.enqueue("batch-1", byteArrayOf(1, 2)).getOrThrow()
        spool.enqueue("batch-2", byteArrayOf(3, 4)).getOrThrow()

        spool.flush().getOrThrow()
        val reopened = SegmentedWearBatchSpool(directory)

        assertEquals(2, reopened.snapshot().pendingBatchCount)
        assertEquals("batch-1", reopened.oldest().getOrThrow()?.batchId)
        assertTrue(reopened.acknowledge("batch-1").getOrThrow())
        assertTrue(reopened.acknowledge("batch-2").getOrThrow())
        reopened.flush().getOrThrow()
        assertTrue(segmentFiles(directory).isEmpty())
    }

    @Test
    fun incompleteTailIsTruncatedWithoutDiscardingCompleteRecords() {
        val directory = temporaryFolder.newFolder("segments")
        val spool = SegmentedWearBatchSpool(directory)
        spool.enqueue("batch-1", byteArrayOf(1)).getOrThrow()
        spool.enqueue("batch-2", byteArrayOf(2)).getOrThrow()
        spool.flush().getOrThrow()
        val segment = segmentFiles(directory).single()
        segment.appendBytes(byteArrayOf(0x01, 0x02))

        val reopened = SegmentedWearBatchSpool(directory)

        assertEquals(2, reopened.snapshot().pendingBatchCount)
        assertEquals(1, reopened.snapshot().corruptFileCount)
        assertEquals("batch-1", reopened.oldest().getOrThrow()?.batchId)
    }

    @Test
    fun duplicateBatchIsIdempotentAndDifferentPayloadIsRejected() {
        val spool = SegmentedWearBatchSpool(temporaryFolder.newFolder("segments"))
        val first = spool.enqueue("batch-1", byteArrayOf(1, 2, 3)).getOrThrow()
        val duplicate = spool.enqueue("batch-1", byteArrayOf(1, 2, 3)).getOrThrow()

        assertEquals(first.ordinal, duplicate.ordinal)
        assertEquals(1, spool.snapshot().pendingBatchCount)
        assertTrue(
            spool.enqueue("batch-1", byteArrayOf(9)).exceptionOrNull()
                ?.message.orEmpty().contains("batch_id_collision"),
        )
    }

    @Test
    fun applicationAckCannotRemoveABatchAheadOfTheFifoHead() {
        val spool = SegmentedWearBatchSpool(temporaryFolder.newFolder("fifo-ack"))
        spool.enqueue("batch-1", byteArrayOf(1)).getOrThrow()
        spool.enqueue("batch-2", byteArrayOf(2)).getOrThrow()

        assertFalse(spool.acknowledge("batch-2").getOrThrow())
        assertEquals("batch-1", spool.oldest().getOrThrow()?.batchId)
        assertEquals(2, spool.snapshot().pendingBatchCount)
    }

    @Test
    fun migrationSpoolDrainsLegacyFilesBeforeNewSegments() {
        val root = temporaryFolder.newFolder("root")
        val legacy = FileWearBatchSpool(root)
        val current = SegmentedWearBatchSpool(root.resolve("segments"))
        legacy.enqueue("legacy", byteArrayOf(1)).getOrThrow()
        current.enqueue("current", byteArrayOf(2)).getOrThrow()
        val spool = MigratingWearBatchSpool(legacy, current)

        assertEquals(2, spool.snapshot().pendingBatchCount)
        assertEquals("legacy", spool.oldest().getOrThrow()?.batchId)
        assertFalse(spool.acknowledge("current").getOrThrow())
        assertTrue(spool.acknowledge("legacy").getOrThrow())
        assertEquals("current", spool.oldest().getOrThrow()?.batchId)
        assertFalse(spool.acknowledge("missing").getOrThrow())
    }

    private fun segmentFiles(directory: java.io.File): List<java.io.File> =
        directory.listFiles().orEmpty().filter { file -> file.name.endsWith(".wseg") }
}
