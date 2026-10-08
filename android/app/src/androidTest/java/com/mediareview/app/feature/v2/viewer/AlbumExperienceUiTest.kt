package com.mediareview.app.feature.v2.viewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.feature.v2.data.*
import com.mediareview.app.feature.v2.home.*
import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.testHomeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumExperienceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var modeStore: V2DataModeStore
    private lateinit var originalMode: V2DataMode
    @Before fun mode() = runBlocking {
        modeStore = V2DataModeStore(ApplicationProvider.getApplicationContext())
        originalMode = modeStore.bootstrap(); modeStore.set(V2DataMode.DEMO)
    }
    @After fun restore() = runBlocking { modeStore.set(originalMode) }
    private fun repo(): AlbumFixtureRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return AlbumFixtureRepository(DemoMediaRepository(context, AlbumCoverStore(context)))
    }
    private fun vm(repo: AlbumFixtureRepository): V2HomeViewModel {
        val vm = testHomeViewModel(repo, AlbumHistory())
        compose.waitUntil(10_000) { vm.albums.value.isNotEmpty() }
        return vm
    }
    @Test fun firstPageErrorDoesNotLookEmpty() {
        val repo = repo(); val vm = vm(repo); repo.pageFailure = true
        compose.setContent { AlbumScreen(vm, "album", {}, { _, _, _, _, _ -> }) }
        compose.onNodeWithText("相册加载失败").assertIsDisplayed()
        compose.onNodeWithText("暂无照片").assertDoesNotExist()
        repo.pageFailure = false
        compose.onNodeWithText("重新加载").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("album_grid").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun explicitTotalZeroShowsEmpty() {
        val repo = repo(); val vm = vm(repo); repo.total = 0
        compose.setContent { AlbumScreen(vm, "album", {}, { _, _, _, _, _ -> }) }
        compose.onNodeWithText("暂无照片").assertIsDisplayed()
        compose.onNodeWithText("相册加载失败").assertDoesNotExist()
    }
    @Test fun refreshAndCoverRefreshFailuresKeepOldShelf() {
        val repo = repo(); val vm = vm(repo); val old = vm.albums.value
        repo.shelfFailure = true
        compose.runOnIdle { vm.refreshAlbums() }
        compose.waitUntil(5_000) { vm.albumError.value != null }
        assertEquals(old, vm.albums.value)
        compose.runOnIdle { vm.setAlbumCover("album", "img1") }
        compose.waitUntil(5_000) { repo.coverWrites == 1 && repo.albumReads >= 3 }
        assertEquals(old, vm.albums.value)
    }
    @Test fun moreFailureRetainsGridAndExplicitRetryLoadsPage2() {
        val repo = repo(); val vm = vm(repo); repo.failPage2 = true
        compose.setContent { AlbumScreen(vm, "album", {}, { _, _, _, _, _ -> }) }
        compose.onNodeWithTag("album_grid").performScrollToIndex(49)
        compose.onNodeWithText("加载更多失败 · 重试").assertIsDisplayed()
        compose.onNodeWithText("120 张照片").assertIsDisplayed()
        repo.failPage2 = false
        compose.onNodeWithText("加载更多失败 · 重试").performClick()
        compose.waitUntil(5_000) { vm.albumState.value?.loadedPage == 2 }
        assertEquals(100, vm.albumState.value!!.items.size)
        assertEquals(listOf(1, 2, 2), repo.pages.map { it.first })
    }
    @Test fun viewerContinues48To51WithoutResettingPendingSession() {
        val repo = repo(); val vm = vm(repo)
        val spec = V2SortSpec(typeFilter = V2TypeFilter.IMAGE)
        compose.runOnIdle { assertTrue(vm.openAlbumImage("album", repo.photos.take(50), 120, "img48", 1, spec)) }
        compose.setContent { V2ImageViewer(vm, "img48", {}) }
        compose.waitUntil(5_000) { vm.albumViewerState.value?.loadedPage == 2 }
        compose.onNodeWithTag("viewer_count").assertTextEquals("48 / 100")
        compose.onNodeWithText("照片48").assertIsDisplayed()
        for (number in 49..51) {
            compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("viewer_count").assertTextEquals("$number / 100")
            compose.onNodeWithText("照片$number").assertIsDisplayed()
        }
        assertEquals(listOf(2 to 50), repo.pages)
        assertEquals(1, repo.pendingReads)
        assertEquals("album", vm.contextQueue!!.folderId)
        assertEquals(spec.field, vm.contextQueue!!.sortField)
    }
    @Test fun viewerPageFailureSnackbarAndRetryKeepCurrentPicture() {
        val repo = repo(); val vm = vm(repo); repo.failPage2 = true
        compose.runOnIdle { vm.openAlbumImage("album", repo.photos.take(50), 120, "img48", 1, V2SortSpec(typeFilter = V2TypeFilter.IMAGE)) }
        compose.setContent { V2ImageViewer(vm, "img48", {}) }
        compose.onNodeWithText("加载更多失败，请重试").assertIsDisplayed()
        compose.onNodeWithTag("viewer_count").assertTextEquals("48 / 50")
        repo.failPage2 = false
        compose.onNodeWithTag("viewer_album_retry").performClick()
        compose.waitUntil(5_000) { vm.albumViewerState.value?.loadedPage == 2 }
        compose.onNodeWithTag("viewer_count").assertTextEquals("48 / 100")
        compose.onNodeWithText("照片48").assertIsDisplayed()
        assertEquals(1, repo.pendingReads)
    }
    @Test fun viewerCloseClearsCurrentSessionAndOldCloseProtectsNewSource() {
        val repo = repo(); val vm = vm(repo)
        val visible = mutableStateOf(true)
        val initialId = mutableStateOf("img48")
        val spec = V2SortSpec(typeFilter = V2TypeFilter.IMAGE)
        compose.runOnIdle { vm.openAlbumImage("album", repo.photos.take(50), 120, "img48", 1, spec) }
        val oldSession = vm.albumViewerSession!!
        compose.setContent { if (visible.value) V2ImageViewer(vm, initialId.value, {}) }
        compose.waitUntil(5_000) { vm.albumViewerState.value?.loadedPage == 2 }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertNull(vm.albumViewerState.value)
        assertNull(vm.albumViewerSession)
        compose.runOnIdle {
            vm.openAlbumImage("newAlbum", repo.photos.take(50), 120, "img1", 1, spec)
            val newSession = vm.albumViewerSession
            vm.closeAlbumViewer(oldSession)
            assertEquals(newSession, vm.albumViewerSession)
            assertEquals("newAlbum", vm.albumViewerState.value!!.albumId)
            initialId.value = "img1"; visible.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle { vm.refreshAfterLibraryChange() }
        compose.waitForIdle()
        assertNull(vm.albumViewerState.value)
        assertNull(vm.albumViewerSession)
        compose.onNodeWithTag("viewer_count").assertExists()
    }
}

private class AlbumHistory : SearchHistoryStore(ApplicationProvider.getApplicationContext()) {
    override suspend fun current() = emptyList<String>()
    override suspend fun add(term: String) {}
    override suspend fun clear() {}
}
private class AlbumFixtureRepository(private val base: MediaRepository) : MediaRepository by base {
    val photos: List<V2Media> = (1..120).map { i ->
        V2Media("img$i", "$i", "照片$i", "album", "相册", V2MediaType.IMAGE, 0, 1, 0,
            false, false, "demo_media/images/img_landscape_01.jpg", "demo_media/images/img_landscape_01.jpg", null, null, 1920, 1080)
    }
    var total = 120
    var pageFailure = false
    var failPage2 = false
    var shelfFailure = false
    var coverWrites = 0
    var albumReads = 0
    var pendingReads = 0
    val pages = mutableListOf<Pair<Int, Int>>()
    override fun mediaById(id: String) = photos.find { it.id == id } ?: base.mediaById(id)
    override suspend fun albums(): List<V2Album> {
        albumReads++; if (shelfFailure) error("offline")
        return listOf(V2Album("album", "album", "相册", 120, "img1"))
    }
    override suspend fun albumPage(albumId: String, page: Int, pageSize: Int, spec: V2SortSpec): V2MediaPage {
        pages += page to pageSize
        if (pageFailure || (failPage2 && page == 2)) error("offline")
        return V2MediaPage(photos.take(total).drop((page - 1) * pageSize).take(pageSize), page, pageSize, total)
    }
    override suspend fun setAlbumCover(albumId: String, mediaId: String) { coverWrites++ }
    override suspend fun pendingDeleteIds(): Set<String> { pendingReads++; return setOf("img48") }
}
