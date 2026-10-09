package com.mediareview.app.feature.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediareview.app.feature.v2.data.*
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class Stage8GViewModelStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("状态应在超时前到达", condition())
    }
    private fun vm(repo: LargeRepository): V2HomeViewModel {
        runBlocking { V2DataModeStore(context).set(V2DataMode.DEMO) }
        lateinit var result: V2HomeViewModel
        main { result = testHomeViewModel(repo, SearchHistoryStore(context)) }
        await { result.currentList.value.size == 50 }
        return result
    }

    @Test fun homeFilterSortLoadedPagesSurviveSearchAndClosingWithoutRequests() {
        val repo = LargeRepository(context)
        val vm = vm(repo)
        val sort = V2SortSpec(V2SortField.NAME, V2SortOrder.ASC, V2TypeFilter.ALL)
        main { vm.selectFolder("f0"); vm.setSortSpec(sort) }
        await { !vm.listLoading.value && repo.requests.last().folderId == "f0" }
        main { vm.loadNextPage() }; await { vm.currentList.value.size == 100 }
        val home = vm.currentList.value
        val homeTotal = vm.totalCount.value
        val requestCount = repo.requests.size
        main { vm.setSearchMode(true); vm.updateSearchQuery("abc") }
        await { vm.searchState.value.items.size == 50 }
        assertEquals(220, vm.searchState.value.total)
        assertSame(home, vm.currentList.value)
        assertNull(repo.requests.last().folderId)
        main { vm.loadNextSearchPage() }; await { vm.searchState.value.items.size == 100 }
        assertTrue(vm.searchState.value.items.any { it.folderId != "f0" })
        main { vm.setSearchMode(false) }
        assertEquals(requestCount + 2, repo.requests.size)
        assertSame(home, vm.currentList.value)
        assertEquals("f0", vm.selectedFolderId.value); assertEquals(sort, vm.sortSpec.value)
        assertEquals(homeTotal, vm.totalCount.value)
        main { vm.loadNextPage() }; await { vm.currentList.value.size == 150 }
        assertEquals(3, repo.requests.last().page); assertNull(repo.requests.last().search)
    }
    @Test fun favoritesRemovedDuringViewingKeepFrozenVideoAndImageQueue() {
        val repo = LargeRepository(context)
        val vm = vm(repo)
        main { vm.ensureFavoritesLoaded() }; await { vm.favorites.value.size == 6 }
        main { assertTrue(vm.openFavoriteMedia(vm.favorites.value, "m902")) }
        assertEquals(listOf("m900", "m902", "m904"), vm.contextQueue?.mediaIds)
        main { vm.setFavorite("m902", false) }; await { vm.favorites.value.size == 5 }
        assertEquals(listOf("m900", "m902", "m904"), vm.contextQueue?.mediaIds)
        assertEquals(1, vm.contextQueue?.currentIndex)
        main { assertTrue(vm.openFavoriteMedia(vm.favorites.value, "m903")) }
        assertEquals(listOf("m901", "m903", "m905"), vm.contextQueue?.mediaIds)
        main { vm.setFavorite("m903", false) }; await { vm.favorites.value.size == 4 }
        assertEquals(listOf("m901", "m903", "m905"), vm.contextQueue?.mediaIds)
        assertEquals(1, vm.contextQueue?.currentIndex)
    }
    @Test fun searchContextUsesOnlyAlreadyLoadedSameTypeWindow() {
        val repo = LargeRepository(context)
        val vm = vm(repo)
        main { vm.setSearchMode(true); vm.updateSearchQuery("abc") }
        await { vm.searchState.value.items.size == 50 }
        main { assertTrue(vm.openSearchMedia(vm.searchState.value.items, "m20")) }
        assertEquals((0 until 50 step 2).map { "m$it" }, vm.contextQueue?.mediaIds)
        assertEquals(10, vm.contextQueue?.currentIndex)
        assertEquals(2, repo.requests.size)
    }
    @Test fun lateFavoriteConfirmationCannotRepopulateStateAfterSourceInvalidation() {
        val repo = LargeRepository(context)
        val vm = vm(repo)
        repo.favoriteResponse = CompletableDeferred()
        main { vm.setFavorite("m903", false) }
        await { repo.favoriteStarted }
        main { vm.clearServerConfig() }
        repo.favoriteResponse!!.complete(true)
        await { repo.favoriteFinished }
        main { assertTrue(vm.confirmedFavoriteStates.value.isEmpty()) }
    }
}

private class LargeRepository(context: Context) : MediaRepository by DemoMediaRepository(context, AlbumCoverStore(context)) {
    val requests = CopyOnWriteArrayList<V2MediaQuery>()
    var favoriteResponse: CompletableDeferred<Boolean>? = null
    @Volatile var favoriteStarted = false
    @Volatile var favoriteFinished = false
    private val catalog = (0 until 1000).map { index ->
        V2Media("m$index", "$index", if (index < 220) "abc $index" else "other $index",
            if (index % 3 == 0) "f0" else "f1", "文件夹", if (index % 2 == 0) V2MediaType.VIDEO else V2MediaType.IMAGE,
            1000, 100, index.toLong(), index in 900..905, false, "", "", null, null, 16, 9)
    }.associateBy { it.id }.toMutableMap()
    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage {
        requests += query
        val matches = catalog.values.filter { (query.folderId == null || it.folderId == query.folderId) &&
            (query.search == null || it.name.contains(query.search)) }.let {
            if (query.spec.field == V2SortField.NAME) it.sortedBy { item -> item.name } else it
        }
        return V2MediaPage(matches.drop((query.page - 1) * query.pageSize).take(query.pageSize), query.page, query.pageSize, matches.size)
    }
    override suspend fun favorites(): List<V2Media> = catalog.values.filter { it.isFavorite }
    override fun mediaById(id: String): V2Media? = catalog[id]
    override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean {
        favoriteStarted = true
        if (favoriteResponse?.await() == false) { favoriteFinished = true; return false }
        val old = catalog[mediaId] ?: return false
        catalog[mediaId] = old.copy(isFavorite = favorite)
        favoriteFinished = true
        return true
    }
}
