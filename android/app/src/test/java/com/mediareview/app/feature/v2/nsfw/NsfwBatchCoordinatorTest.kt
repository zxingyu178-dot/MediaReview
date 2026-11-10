package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NsfwBatchCoordinatorTest {
    @Test fun confirmedAtomicConflictReleasesPendingWithoutRetrying() = runTest {
        val journal = Journal()
        val data = FakeNsfwDataSource().apply {
            batchResponder = { _, _ -> throw NsfwApiException("REVIEW_CONFLICT", "整页版本已变更", 409) }
        }
        val failure = runCatching { NsfwBatchCoordinator(data, journal, origin).submit(frozen) }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertNull(journal.load(origin))
        assertEquals(1, data.batchCalls)
        assertEquals(0, data.batchReceiptCalls)
    }
    private val origin = "http://nsfw.test:8877"
    private val frozen = NsfwFrozenBatch("original", listOf(NsfwBatchReviewItem("a", "KEEP", "1")))
    private val result = NsfwBatchReviewResult(listOf(NsfwBatchReviewedImage("a", "KEEP", "2")), 1, 0)

    private class Journal : NsfwBatchJournal {
        val records = mutableMapOf<String, NsfwFrozenBatch>()
        var failSave = false
        override suspend fun load(origin: String) = records[origin]
        override suspend fun save(origin: String, batch: NsfwFrozenBatch) {
            if (failSave) throw IOException("disk full")
            records[origin] = batch
        }
        override suspend fun clear(origin: String, requestId: String) {
            if (records[origin]?.requestId == requestId) records.remove(origin)
        }
    }

    @Test fun saveFailureNeverPostsOrDiscardsPage() = runTest {
        val data = FakeNsfwDataSource()
        val journal = Journal().apply { failSave = true }
        val coordinator = NsfwBatchCoordinator(data, journal, origin)
        assertTrue(runCatching { coordinator.submit(frozen) }.isFailure)
        assertEquals(0, data.batchCalls)
        assertNull(journal.load(origin))
    }

    @Test fun unknownResultSurvivesRestartAndOnlyQueriesOriginalReceipt() = runTest {
        val journal = Journal()
        val data = FakeNsfwDataSource().apply {
            batchResponder = { id, items ->
                assertEquals(frozen.requestId, id)
                assertEquals(frozen.items, items)
                assertEquals(frozen, journal.load(origin))
                throw IOException("response lost")
            }
            batchReceiptResponder = { id -> assertEquals("original", id); result }
        }
        assertTrue(runCatching { NsfwBatchCoordinator(data, journal, origin).submit(frozen) }.isFailure)
        val restored = NsfwBatchCoordinator(data, journal, origin)
        assertTrue(runCatching { restored.acknowledge("original") }.isFailure)
        assertTrue(runCatching { restored.submit(frozen.copy(requestId = "new-id")) }.isFailure)
        assertEquals(result, restored.recover())
        assertEquals(1, data.batchCalls)
        assertEquals(1, data.batchReceiptCalls)
        assertEquals(frozen, journal.load(origin))
        restored.acknowledge("original")
        assertNull(journal.load(origin))
    }

    @Test fun wrongReceiptOrDifferentOriginCannotConsumePendingBatch() = runTest {
        val journal = Journal().apply { records[origin] = frozen }
        val data = FakeNsfwDataSource().apply {
            batchReceiptResponder = { result.copy(items = listOf(NsfwBatchReviewedImage("wrong", "KEEP", "2"))) }
        }
        assertTrue(runCatching { NsfwBatchCoordinator(data, journal, origin).recover() }.isFailure)
        assertEquals(frozen, journal.load(origin))
        assertNull(NsfwBatchCoordinator(data, journal, "http://other.test:8877").recover())
        assertEquals(1, data.batchReceiptCalls)
        assertEquals(0, data.batchCalls)
    }
}
