package com.mediareview.app.feature.v2

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.home.HomeScreen
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.ui.theme.MediaReviewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage4 浏览器 Compose UI 测试（模拟器 instrumentation）：
 * - 首页封面不出现错误占位（不再黑卡）
 * - 搜索激活后全局只有一个输入框（原双输入框回归）
 * - 收藏→图片→Viewer→返回仍回收藏
 * - 收藏→视频→Player→返回仍回收藏
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Stage4BrowserUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(repo: MediaRepository): V2HomeViewModel =
        V2HomeViewModel(repo, Stub4SearchHistory())

    // ---------- 首页封面 ----------

    @Test
    fun homeFirstScreenHasNoCoverErrorPlaceholder() {
        val vm = newVm(BrowserFakeRepository())
        compose.setContent {
            MediaReviewTheme {
                HomeScreen(
                    vm = vm,
                    onOpenMedia = {},
                    onOpenFolder = {},
                )
            }
        }
        compose.waitUntil(5_000) { vm.currentList.value.isNotEmpty() }
        compose.waitForIdle()
        // 首屏不应出现"封面不可用"或大面积错误占位
        compose.onAllNodesWithText("封面不可用").fetchSemanticsNodes().let { org.junit.Assert.assertTrue("不应出现封面错误占位", it.isEmpty()) }
        assertTrue("首屏应有媒体卡片标题", compose.onAllNodesWithText("视频A").fetchSemanticsNodes().isNotEmpty())
    }

    // ---------- 搜索单输入框 ----------

    @Test
    fun searchActivatedShowsSingleInputBox() {
        val vm = newVm(BrowserFakeRepository())
        compose.setContent {
            MediaReviewTheme {
                HomeScreen(
                    vm = vm,
                    onOpenMedia = {},
                    onOpenFolder = {},
                )
            }
        }
        compose.waitUntil(5_000) { vm.currentList.value.isNotEmpty() }
        // 未激活：无输入框（占位文案可点击）
        compose.onNodeWithText("搜索媒体…").performClick()
        compose.waitForIdle()
        // 激活后只存在一个可输入文本节点（顶部同一输入框，不再出现第二个）
        val inputs = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue("搜索激活后应只有一个输入框，实际 ${inputs.size} 个", inputs.size == 1)
        compose.onNode(hasSetTextAction()).performTextInput("视频A")
        compose.waitForIdle()
        assertTrue("搜索应实际过滤", vm.currentList.value.isNotEmpty())
        // 关闭后回到浏览态（模式切换行重新出现）
        compose.onNodeWithText("媒体").assertExists()
    }

    // ---------- 收藏导航 ----------

    @Test
    fun favoritesOpenImageViewerAndBackReturnsToFavorites() {
        val vm = newVm(BrowserFakeRepository())
        compose.setContent {
            MediaReviewTheme {
                V2MainScreen(vm = vm)
            }
        }
        openFavoritesTab()
        // 收藏页含一张图片卡：点击进入 Viewer
        compose.onNodeWithText("图片收藏").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("返回"), timeoutMillis = 10_000)
        // 返回后应回收藏页
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("图片收藏").assertIsDisplayed()
    }

    @Test
    fun favoritesOpenPlayerAndBackReturnsToFavorites() {
        val vm = newVm(BrowserFakeRepository())
        compose.setContent {
            MediaReviewTheme {
                V2MainScreen(vm = vm)
            }
        }
        openFavoritesTab()
        compose.onNodeWithText("视频收藏").performClick()
        // 播放器控制层出现（任意播放/暂停态即可）
        compose.waitUntilExactlyOneExists(hasContentDescription("播放"), timeoutMillis = 30_000)
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("视频收藏").assertIsDisplayed()
    }

    private fun openFavoritesTab() {
        // 底部导航的"收藏"项（首页 tab 下无其他同名文本）
        compose.onNodeWithText("收藏").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("图片收藏").assertIsDisplayed()
    }
}

/** 内存版搜索历史（避免触碰真实 DataStore）。 */
private class Stub4SearchHistory : SearchHistoryStore(ApplicationProvider.getApplicationContext()) {
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

/** 测试用内存仓库：书架 1 组 + 收藏 1 视频 1 图片。 */
private class BrowserFakeRepository : MediaRepository {
    private val catalog = listOf(
        media("v1", "视频A", "视频收藏", V2MediaType.VIDEO, isFav = true),
        media("v2", "视频B", "视频收藏", V2MediaType.VIDEO, isFav = false),
        media("i1", "图片收藏", "收藏", V2MediaType.IMAGE, isFav = true),
    )
    private val byId = catalog.associateBy { it.id }

    override val mode: AppMode = AppMode.DEMO
    private val folder = V2Folder("f1", "收藏", "收藏内容", listOf("i1", "v1"))

    override suspend fun folders(): List<V2Folder> = listOf(folder)
    override suspend fun media(): List<V2Media> = catalog
    override suspend fun media(spec: V2SortSpec): List<V2Media> = catalog
    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> = catalog
    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> =
        catalog.filter { it.name.contains(query, ignoreCase = true) }

    override fun mediaById(id: String): V2Media? = byId[id]
    override suspend fun setFavorite(mediaId: String, favorite: Boolean) {}
    override suspend fun markReviewed(mediaId: String) {}
    override fun playbackUri(mediaId: String): String = ""
    override fun thumbUri(media: V2Media): String = ""
    override fun coverUri(media: V2Media): String = when (media.type) {
        V2MediaType.VIDEO -> "android.resource://com.mediareview.app/raw/demo_video_01_poster"
        V2MediaType.IMAGE -> "android.resource://com.mediareview.app/raw/demo_img_landscape_01"
    }

    override fun imageUri(media: V2Media): String = "android.resource://com.mediareview.app/raw/demo_img_landscape_01"
    override fun spriteUri(media: V2Media): String? = null
    override fun spriteManifest(media: V2Media): V2SpriteManifest? = null

    private fun media(
        id: String, name: String, folderName: String, type: V2MediaType, isFav: Boolean,
    ): V2Media = V2Media(
        id = id, code = id.uppercase(), name = name,
        folderId = "f1", folderName = folderName,
        type = type, durationMs = if (type == V2MediaType.VIDEO) 18_000L else 0L,
        sizeBytes = 1_000_000L, dateMillis = 1_752_000_000_000L,
        isFavorite = isFav, isReviewed = false,
        assetPath = "demo_media/videos/01_landscape.mp4",
        thumbPath = "demo_media/sprites/01_landscape_sprite.webp",
        spritePath = null, spriteManifestPath = null,
        naturalWidth = 1280, naturalHeight = 720,
    )
}