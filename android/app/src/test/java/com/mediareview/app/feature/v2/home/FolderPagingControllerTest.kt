package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FolderPagingControllerTest {
    @Test fun requestsOnlyDirectMediaInBoundedPagesAndDoesNotPolluteHomeQuery() = runTest {
        val requests = mutableListOf<V2MediaQuery>()
        val pager = FolderPagingController(backgroundScope) { query ->
            requests += query
            V2MediaPage(emptyList(), query.page, query.pageSize, 125)
        }
        pager.open("ft_movies", V2SortSpec())
        runCurrent()
        assertEquals(1, requests.size)
        assertEquals("ft_movies", requests.single().folderId)
        assertFalse(requests.single().includeDescendants)
        assertEquals(50, requests.single().pageSize)
        pager.loadNextPage()
        runCurrent()
        assertEquals(listOf(1, 2), requests.map { it.page })
        pager.close()
        assertTrue(pager.state.value.items.isEmpty())
    }
    @Test fun lateOldFolderResponseCannotOverwriteNewFolder() = runTest {
        val oldResponse = CompletableDeferred<V2MediaPage>()
        val pager = FolderPagingController(backgroundScope) { query ->
            if (query.folderId == "old") withContext(NonCancellable) { oldResponse.await() }
            else V2MediaPage(emptyList(), 1, 50, 2)
        }
        pager.open("old", V2SortSpec())
        runCurrent()
        pager.open("new", V2SortSpec())
        runCurrent()
        oldResponse.complete(V2MediaPage(emptyList(), 1, 50, 999))
        runCurrent()
        assertEquals("new", pager.state.value.folderId)
        assertEquals(2, pager.state.value.total)
    }
}
