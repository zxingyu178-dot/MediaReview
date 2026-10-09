package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 1000媒体/220匹配的有界fixture，所有断言驱动生产搜索控制器。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchPagingControllerTest {
    private fun media(index: Int) = V2Media(
        "m$index", "$index", if (index < 220) "abc $index" else "other $index", "f", "文件夹",
        V2MediaType.VIDEO, 1000, 100, 0, false, false, "", "", null, null, 16, 9,
    )
    private val library = (0 until 1000).map(::media)
    private fun page(q: V2MediaQuery): V2MediaPage {
        val matches = library.filter { it.name.contains(q.search.orEmpty()) }
        return V2MediaPage(matches.drop((q.page - 1) * q.pageSize).take(q.pageSize), q.page, q.pageSize, matches.size)
    }

    @Test fun `blank does not request and first page waits exactly 300ms`() = runTest {
        val requests = mutableListOf<V2MediaQuery>()
        val c = SearchPagingController(backgroundScope) { requests += it; page(it) }
        c.updateQuery("  "); advanceTimeBy(500); runCurrent(); assertTrue(requests.isEmpty())
        c.updateQuery("abc"); advanceTimeBy(299); runCurrent(); assertTrue(requests.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(1, requests.size); assertEquals(50, requests.single().pageSize)
        assertNull(requests.single().folderId); assertEquals(220, c.state.value.total)
        assertEquals(50, c.state.value.items.size); assertEquals(1, c.state.value.page)
    }
    @Test fun `rapid a ab abc only requests abc`() = runTest {
        val requests = mutableListOf<String?>()
        val c = SearchPagingController(backgroundScope) { requests += it.search; page(it) }
        c.updateQuery("a"); advanceTimeBy(100); c.updateQuery("ab"); advanceTimeBy(100)
        c.updateQuery("abc"); advanceTimeBy(300); runCurrent()
        assertEquals(listOf("abc"), requests); assertEquals("abc", c.state.value.query)
        assertEquals(50, c.state.value.items.size)
    }
    @Test fun `uncancellable older first page cannot overwrite newer query`() = runTest {
        val old = CompletableDeferred<Unit>()
        val c = SearchPagingController(backgroundScope) {
            if (it.search == "a") withContext(NonCancellable) { old.await() }
            page(it)
        }
        c.updateQuery("a"); advanceTimeBy(300); runCurrent()
        c.updateQuery("abc"); advanceTimeBy(300); runCurrent()
        old.complete(Unit); runCurrent()
        assertEquals("abc", c.state.value.query); assertEquals(220, c.state.value.total)
        assertFalse(c.state.value.loadingFirstPage)
    }
    @Test fun `large fixture loads only near tail then pages two and three`() = runTest {
        val requests = mutableListOf<Int>()
        val c = SearchPagingController(backgroundScope) { requests += it.page; page(it) }
        c.updateQuery("abc"); advanceTimeBy(300); runCurrent()
        c.nearEnd(10); runCurrent(); assertEquals(listOf(1), requests)
        c.nearEnd(34); runCurrent(); assertEquals(100, c.state.value.items.size)
        c.nearEnd(84); runCurrent(); assertEquals(150, c.state.value.items.size)
        assertEquals(listOf(1, 2, 3), requests); assertTrue(c.state.value.hasMore)
    }
    @Test fun `append failure preserves page and items and only explicit retry retries`() = runTest {
        var fail = true
        var calls = 0
        val c = SearchPagingController(backgroundScope) {
            calls++; if (it.page == 2 && fail) error("fixture503") else page(it)
        }
        c.updateQuery("abc"); advanceTimeBy(300); runCurrent()
        val first = c.state.value.items
        c.nearEnd(40); runCurrent(); assertEquals(first, c.state.value.items)
        assertEquals(1, c.state.value.page); assertNotNull(c.state.value.error)
        repeat(5) { c.nearEnd(49); runCurrent() }; assertEquals(2, calls)
        fail = false; c.retry(); runCurrent()
        assertEquals(100, c.state.value.items.size); assertEquals(2, c.state.value.page)
        assertNull(c.state.value.error)
    }
    @Test fun `duplicate IDs within and between pages are removed`() = runTest {
        val c = SearchPagingController(backgroundScope) {
            V2MediaPage(if (it.page == 1) listOf(media(0), media(0), media(1)) else listOf(media(1), media(2), media(2)), it.page, 50, 100)
        }
        c.updateQuery("abc"); advanceTimeBy(300); runCurrent(); c.loadNextPage(); runCurrent()
        assertEquals(listOf("m0", "m1", "m2"), c.state.value.items.map { it.id })
    }
    @Test fun `clear discards late page and resets query`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = SearchPagingController(backgroundScope) { withContext(NonCancellable) { gate.await() }; page(it) }
        c.updateQuery("abc"); advanceTimeBy(300); runCurrent(); c.clear(); gate.complete(Unit); runCurrent()
        assertEquals(SearchUiState(), c.state.value)
    }
    @Test fun `next page is single flight and old append cannot affect new query`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var pages = 0
        val c = SearchPagingController(backgroundScope) {
            if (it.page == 2) { pages++; withContext(NonCancellable) { gate.await() } }
            page(it)
        }
        c.updateQuery("abc"); advanceTimeBy(300); runCurrent()
        repeat(3) { c.loadNextPage() }; runCurrent(); assertEquals(1, pages)
        c.updateQuery("other"); advanceTimeBy(300); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals("other", c.state.value.query); assertEquals(780, c.state.value.total)
        assertEquals(1, c.state.value.page); assertEquals(50, c.state.value.items.size)
    }
    @Test fun `first page failure is retryable and favorite confirmation updates search only`() = runTest {
        var fail = true
        val c = SearchPagingController(backgroundScope) { if (fail) error("fixture") else page(it) }
        c.updateQuery("abc"); advanceTimeBy(300); runCurrent(); assertNotNull(c.state.value.error)
        fail = false; c.retry(); runCurrent(); assertEquals(50, c.state.value.items.size)
        c.updateFavorite("m0", true); assertTrue(c.state.value.items.first().isFavorite)
        assertEquals(1, c.state.value.page)
    }
}
