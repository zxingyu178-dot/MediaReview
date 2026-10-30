package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.viewer.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WindowCachePolicyTest {
    private val spec = V2SortSpec()
    private val sample = V2Media("m", "", "媒体", "f", "目录", V2MediaType.IMAGE, 0, 1, 0, false, false, "", "", null, null, 1, 1)
    private fun window(generation: Long = 1) = CachedWindow(listOf(sample), 2, 120, spec, HomeGridAnchor("m", 29), 1234, generation)
    @Test fun albumAndBrowseHaveIndependentEightEntryAccessOrderedLru() {
        for (cache in listOf(AlbumWindowCache(), BrowseWindowCache())) {
            repeat(8) { cache.put(WindowKey(1, "$it", spec), window()) }
            assertNotNull(cache.get(WindowKey(1, "0", spec)))
            cache.put(WindowKey(1, "8", spec), window())
            assertNull(cache.get(WindowKey(1, "1", spec)))
            assertEquals(29, cache.get(WindowKey(1, "0", spec))!!.anchor!!.offset)
            assertEquals(1234L, cache.get(WindowKey(1, "0", spec))!!.updatedAt)
        }
    }
    @Test fun generationSortTypeAndDirectScopeAreIsolatedAndFavoriteOnlyPatches() {
        val cache = BrowseWindowCache()
        val key = WindowKey(1, "f", spec)
        cache.put(key, window())
        assertNull(cache.get(key.copy(sourceGeneration = 2)))
        assertNull(cache.get(key.copy(sort = spec.copy(typeFilter = V2TypeFilter.VIDEO))))
        assertNull(cache.get(key.copy(includeDescendants = false)))
        cache.patchFavorite("m", true)
        assertTrue(cache.get(key)!!.items.first().isFavorite)
        assertEquals(2, cache.get(key)!!.page)
        assertEquals(120, cache.get(key)!!.total)
        cache.clear(); assertNull(cache.get(key))
    }
    @Test fun albumSWRRevalidatesLoadedPagesAtomicallyAndKeepsAnchor() = runTest {
        var refresh = false
        val seen = mutableListOf<Int>()
        val controller = AlbumPagingController(backgroundScope, { _, page, size, _ ->
            seen += page
            if (refresh) delay(3_000)
            V2MediaPage((1..50).map { sample.copy(id = "${if (refresh) "new" else "old"}-$page-$it") }, page, size, 120)
        })
        val first = controller.open("f", spec); runCurrent()
        controller.nearEnd(first, 49); runCurrent()
        controller.updateAnchor(first, HomeGridAnchor("old-2-1", 31)); controller.close(first)
        refresh = true
        controller.open("f", spec); runCurrent()
        assertEquals(100, controller.state.value!!.items.size)
        advanceTimeBy(3_000); runCurrent()
        assertEquals("old-1-1", controller.state.value!!.items.first().id)
        advanceTimeBy(3_000); runCurrent()
        assertEquals("new-1-1", controller.state.value!!.items.first().id)
        assertEquals(2, controller.state.value!!.loadedPage)
        assertEquals(31, controller.state.value!!.anchor!!.offset)
        assertEquals(listOf(1, 2, 1, 2), seen)
    }
    @Test fun changedSourceDoesNotPublishOrCacheNonCancellableLateResult() = runTest {
        var source = 1L
        val gate = CompletableDeferred<Unit>()
        val cache = BrowseWindowCache()
        val controller = FolderPagingController(backgroundScope, cache, { source }, {}, { query ->
            withContext(NonCancellable) { gate.await() }
            V2MediaPage(listOf(sample), query.page, 50, 1)
        })
        controller.open("f", spec); runCurrent(); source++
        controller.invalidate(); gate.complete(Unit); runCurrent()
        assertTrue(controller.state.value.items.isEmpty())
        assertNull(cache.get(WindowKey(1, "f", spec, false)))
    }
    @Test fun firstAndNextScreenPrefetchIsBoundedAndDoesNotNeedLayoutOrIdle() {
        assertEquals((0..15).toList(), coverPrefetchIndices(1000))
        assertEquals((998..999).toList(), coverPrefetchIndices(1000, 998))
        assertTrue(coverPrefetchIndices(0).isEmpty())
    }
    @Test fun albumWindowOnlyWarmsEightNearbyCoversAndClampsPastEnd() {
        assertEquals((50..57).toList(), coverPrefetchIndices(1000, 50, windowSize = 8))
        assertEquals(listOf(999), coverPrefetchIndices(1000, 999, windowSize = 8))
        assertTrue(coverPrefetchIndices(2, 50, windowSize = 8).isEmpty())
    }
    @Test fun prefetchRunsAtMostTwoRequestsAndNeverWarmsWholePage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val job = launch { prefetchBoundedCovers((0..99).map { "$it" }) {
            started.incrementAndGet()
            val running = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, running) }
            gate.await(); active.decrementAndGet()
        } }
        while (started.get() < 2) yield()
        assertEquals(2, started.get()); gate.complete(Unit); job.join()
        assertEquals(16, started.get()); assertTrue(maximum.get() <= 2)
    }

    @Test fun scrollingCancelsAnUnfinishedInitialWindowAndStartsTheVisibleWindow() = runBlocking<Unit> {
        val positions = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val first = CompletableDeferred<Unit>()
        val visible = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val job = launch {
            prefetchCoverWindows((0..99).map { "$it" }, positions.receiveAsFlow()) { url ->
                if (url.toInt() < 16) {
                    first.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                } else visible.complete(Unit)
            }
        }
        positions.send(0)
        withTimeout(2_000) { first.await() }
        positions.send(50)
        withTimeout(2_000) { visible.await() }
        assertTrue(cancelled.isCompleted)
        job.cancelAndJoin()
        positions.close()
    }
}
