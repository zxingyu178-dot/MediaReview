package com.mediareview.app.feature.v2.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.mediareview.app.feature.v2.AppMode
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 图片查看器 Compose UI 测试（在模拟器/真机上运行）：
 * 顶部返回/计数、收藏/信息/待删除存在；Info Sheet 打开/关闭；收藏与待删除点击状态变化。
 */
@RunWith(AndroidJUnit4::class)
class ImageViewerUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(): V2HomeViewModel {
        val vm = V2HomeViewModel(FakeViewerRepository(), StubSearchHistory())
        compose.waitUntil(5_000) { vm.currentList.value.isNotEmpty() }
        vm.openMedia("img2")
        return vm
    }

    private fun setViewer(vm: V2HomeViewModel) {
        compose.setContent {
            V2ImageViewer(vm = vm, initialMediaId = "img2", onBack = {})
        }
    }

    @Test
    fun viewerCoreElementsExistWithFilteredCount() {
        val vm = newVm()
        setViewer(vm)
        compose.onNodeWithTag("viewer_back").assertExists()
        compose.onNodeWithTag("viewer_count").assertExists()
        compose.onNodeWithTag("viewer_favorite").assertExists()
        compose.onNodeWithTag("viewer_info").assertExists()
        compose.onNodeWithTag("viewer_delete").assertExists()
        // 队列含 3 张图 + 1 个视频，过滤后 3 张，打开 img2 → "2 / 3"
        compose.onNodeWithTag("viewer_count").assertTextEquals("2 / 3")
    }

    @Test
    fun infoSheetOpensAndCloses() {
        val vm = newVm()
        setViewer(vm)
        compose.onNodeWithTag("viewer_info").performClick()
        compose.onNodeWithText("编号").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("编号").assertDoesNotExist()
    }

    @Test
    fun favoriteToggleChangesState() {
        val vm = newVm()
        setViewer(vm)
        compose.onNodeWithText("收藏").assertIsDisplayed()
        compose.onNodeWithTag("viewer_favorite").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已收藏").assertIsDisplayed()
    }

    @Test
    fun pendingDeleteToggleChangesLabel() {
        val vm = newVm()
        setViewer(vm)
        compose.onNodeWithText("待删除").assertIsDisplayed()
        compose.onNodeWithTag("viewer_delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已标记").assertIsDisplayed()
    }
}

/** 内存版搜索历史（避免 androidTest 触碰真实 DataStore）。 */
private class StubSearchHistory : SearchHistoryStore(ApplicationProvider.getApplicationContext()) {
    private val memory = mutableListOf<String>()
    override suspend fun current(): List<String> = memory.toList()
    override suspend fun add(term: String) {
        memory.remove(term)
        memory.add(0, term)
    }

    override suspend fun clear() {
        memory.clear()
    }
}

/** 测试用内存数据仓库：3 图 + 1 视频。 */
private class FakeViewerRepository : MediaRepository {
    private val catalog = listOf(
        media("img1", "IMG-001", V2MediaType.IMAGE, 1280, 720),
        media("img2", "IMG-002", V2MediaType.IMAGE, 720, 1280),
        media("vid1", "VID-001", V2MediaType.VIDEO, 1280, 720),
        media("img3", "IMG-003", V2MediaType.IMAGE, 1080, 1080),
    )
    private val byId = catalog.associateBy { it.id }
    private val favoriteState = mutableMapOf<String, Boolean>()

    override val mode: AppMode = AppMode.DEMO
    override suspend fun folders(): List<V2Folder> = emptyList()
    override suspend fun media(): List<V2Media> = applyOverrides(catalog)
    override suspend fun media(spec: V2SortSpec): List<V2Media> = applyOverrides(catalog)
    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> = applyOverrides(catalog)
    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> = applyOverrides(catalog)
    override fun mediaById(id: String): V2Media? = byId[id]?.let { applyOverrides(listOf(it)).firstOrNull() }
    override suspend fun setFavorite(mediaId: String, favorite: Boolean) {
        favoriteState[mediaId] = favorite
    }
    override suspend fun markReviewed(mediaId: String) {}
    override suspend fun pendingDeleteIds(): Set<String> = emptySet()
    override suspend fun setPendingDelete(mediaId: String, pending: Boolean) {}
    override suspend fun unmarkReviewed(mediaId: String) {}
    override fun playbackUri(mediaId: String): String = ""
    override fun thumbUri(media: V2Media): String = ""
    override fun coverUri(media: V2Media): String = ""
    override fun imageUri(media: V2Media): String = "asset:///demo_media/images/${media.id}.jpg"
    override fun spriteUri(media: V2Media): String? = null
    override fun spriteManifest(media: V2Media): V2SpriteManifest? = null

    override suspend fun albums(): List<com.mediareview.app.feature.v2.model.V2Album> = emptyList()
    override suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media> = emptyList()
    override suspend fun setAlbumCover(albumId: String, mediaId: String) {}

    private fun applyOverrides(source: List<V2Media>): List<V2Media> =
        source.map { m -> if (favoriteState[m.id] != null) m.copy(isFavorite = favoriteState[m.id]!!) else m }

    private fun media(id: String, name: String, type: V2MediaType, w: Int, h: Int): V2Media = V2Media(
        id = id, code = id.uppercase(), name = name,
        folderId = "f_demo", folderName = "Demo",
        type = type, durationMs = if (type == V2MediaType.VIDEO) 10_000L else 0L,
        sizeBytes = 1_000_000L, dateMillis = 1_752_000_000_000L,
        isFavorite = false, isReviewed = false,
        assetPath = "demo_media/images/$id.jpg",
        thumbPath = "demo_media/images/$id.jpg",
        spritePath = null, spriteManifestPath = null,
        naturalWidth = w, naturalHeight = h,
    )
}
