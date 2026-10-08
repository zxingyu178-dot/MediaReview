package com.mediareview.app.feature.v2.viewer.state

import com.mediareview.app.feature.v2.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumPagingControllerTest {
    private val spec = V2SortSpec(typeFilter = V2TypeFilter.IMAGE)
    private fun photos(start: Int, end: Int) = (start..end).map { i ->
        V2Media("img$i", "$i", "照片$i", "album", "相册", V2MediaType.IMAGE,
            0, 1, 0, false, false, "", "", null, null, 1, 1)
    }
    @Test fun `first page failure is Error and retry reaches explicit Empty`() = runTest {
        var fail = true
        val c = AlbumPagingController(backgroundScope, { _, page, size, _ ->
            if (fail) error("offline") else V2MediaPage(emptyList(), page, size, 0)
        })
        val session = c.open("album", spec)
        assertEquals(AlbumLoadStatus.Loading, c.state.value!!.status)
        runCurrent(); assertEquals(AlbumLoadStatus.Error, c.state.value!!.status)
        assertEquals(0, c.state.value!!.loadedPage)
        fail = false; c.retry(session); runCurrent()
        assertEquals(AlbumLoadStatus.Empty, c.state.value!!.status)
    }
    @Test fun `positive total with empty window is Error never Empty`() = runTest {
        val c = AlbumPagingController(backgroundScope, { _, p, s, _ -> V2MediaPage(emptyList(), p, s, 120) })
        c.open("album", spec); runCurrent()
        assertEquals(AlbumLoadStatus.Error, c.state.value!!.status)
    }
    @Test fun `page2 failure preserves window total and page then explicit retry appends unique stable ids`() = runTest {
        var fail = true; val messages = mutableListOf<String>(); val requests = mutableListOf<Int>()
        val c = AlbumPagingController(backgroundScope, { _, p, s, _ ->
            requests += p; if (fail) error("offline")
            V2MediaPage(photos(50, 100), p, s, 120)
        }, messages::add)
        val session = c.open("album", spec, photos(1, 50), 120, 1)
        c.nearEnd(session, 47); runCurrent()
        assertEquals(50, c.state.value!!.items.size); assertEquals(120, c.state.value!!.total)
        assertEquals(1, c.state.value!!.loadedPage); assertNotNull(c.state.value!!.moreError)
        repeat(3) { c.nearEnd(session, 47); runCurrent() }; assertEquals(listOf(2), requests)
        fail = false; c.retry(session); runCurrent()
        assertEquals((1..100).map { "img$it" }, c.state.value!!.mediaIds)
        assertEquals(2, c.state.value!!.loadedPage); assertNull(c.state.value!!.moreError)
        assertEquals(listOf("加载更多失败，请重试"), messages)
    }
    @Test fun `120 images continue 48 49 50 51 with one in flight and fixed 50 page`() = runTest {
        val gate = CompletableDeferred<Unit>(); val requests = mutableListOf<Pair<Int, Int>>()
        val c = AlbumPagingController(backgroundScope, { album, p, s, receivedSpec ->
            assertEquals("album", album); assertEquals(spec, receivedSpec); requests += p to s
            gate.await(); V2MediaPage(photos(51, 100), p, s, 120)
        })
        val session = c.open("album", spec, photos(1, 50), 120, 1)
        c.nearEnd(session, 46); runCurrent(); assertTrue(requests.isEmpty())
        for (index in 47..49) { c.nearEnd(session, index); runCurrent() }
        assertEquals(listOf(2 to 50), requests)
        gate.complete(Unit); runCurrent()
        for (index in 47..50) assertEquals("img${index + 1}", c.state.value!!.mediaIds[index])
        assertEquals(100, c.state.value!!.items.size)
    }
    @Test fun `next tail movement retries failure once without automatic failure loop`() = runTest {
        var requests = 0
        val c = AlbumPagingController(backgroundScope, { _, _, _, _ -> requests++; error("offline") })
        val session = c.open("album", spec, photos(1, 50), 120, 1)
        c.nearEnd(session, 47); runCurrent(); c.nearEnd(session, 47); runCurrent()
        assertEquals(1, requests)
        c.nearEnd(session, 48); runCurrent(); assertEquals(2, requests)
    }
    @Test fun `late old source cannot overwrite new album and stale close cannot cancel it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = AlbumPagingController(backgroundScope, { id, p, s, _ ->
            if (id == "old") { withContext(NonCancellable) { gate.await() }; V2MediaPage(photos(1, 50), p, s, 120) }
            else V2MediaPage(photos(101, 120), p, s, 20)
        })
        val old = c.open("old", spec); runCurrent(); c.invalidate()
        c.open("new", spec); runCurrent(); c.close(old); gate.complete(Unit); runCurrent()
        assertEquals("new", c.state.value!!.albumId); assertEquals("img101", c.state.value!!.mediaIds.first())
    }
    @Test fun `close and cancellation do not publish fake error or late window`() = runTest {
        val gate = CompletableDeferred<Unit>(); val messages = mutableListOf<String>()
        val c = AlbumPagingController(backgroundScope, { _, p, s, _ ->
            withContext(NonCancellable) { gate.await() }; V2MediaPage(photos(1, 50), p, s, 120)
        }, messages::add)
        val session = c.open("album", spec); runCurrent(); c.close(session); gate.complete(Unit); runCurrent()
        assertNull(c.state.value); assertTrue(messages.isEmpty())
        val cancelled = AlbumPagingController(backgroundScope, { _, _, _, _ -> throw CancellationException() }, messages::add)
        cancelled.open("album", spec); runCurrent()
        assertEquals(AlbumLoadStatus.Loading, cancelled.state.value!!.status); assertTrue(messages.isEmpty())
    }
}
