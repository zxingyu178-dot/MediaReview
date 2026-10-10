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
    private fun media(favorite: Boolean = false) = V2Media("cached", "", "照片", "folder", "目录", V2MediaType.IMAGE,
        0, 1, 0, favorite, false, "", "", null, null, 1, 1)
    @Test fun cachedFolderReopensSynchronouslyWhileRefreshWaitsThreeSeconds() = runTest {
        var calls = 0
        val pager = FolderPagingController(backgroundScope) { query ->
            calls++
            if (calls > 1) kotlinx.coroutines.delay(3_000)
            V2MediaPage(listOf(media()), query.page, query.pageSize, 1)
        }
        pager.open("folder", V2SortSpec()); runCurrent(); pager.close()
        pager.open("folder", V2SortSpec())
        assertEquals(listOf("cached"), pager.state.value.items.map { it.id })
        runCurrent(); advanceTimeBy(2_999); runCurrent()
        assertEquals(1, pager.state.value.items.size)
    }
    @Test fun cachedFolderRefreshFailureRetainsWindow() = runTest {
        var fail = false
        val pager = FolderPagingController(backgroundScope) { query ->
            if (fail) error("offline")
            V2MediaPage(listOf(media()), query.page, query.pageSize, 1)
        }
        pager.open("folder", V2SortSpec()); runCurrent(); pager.close()
        fail = true; pager.open("folder", V2SortSpec()); runCurrent()
        assertEquals(1, pager.state.value.items.size)
        assertEquals(1, pager.state.value.page)
    }
    @Test fun favoriteConfirmationPatchesClosedFolderCacheWithoutEvicting() = runTest {
        val pager = FolderPagingController(backgroundScope) { query ->
            kotlinx.coroutines.delay(3_000)
            V2MediaPage(listOf(media()), query.page, query.pageSize, 1)
        }
        pager.open("folder", V2SortSpec()); runCurrent(); advanceTimeBy(3_000); runCurrent(); pager.close()
        pager.updateFavorite("cached", true); pager.open("folder", V2SortSpec())
        assertEquals(true, pager.state.value.items.firstOrNull()?.isFavorite)
    }}
