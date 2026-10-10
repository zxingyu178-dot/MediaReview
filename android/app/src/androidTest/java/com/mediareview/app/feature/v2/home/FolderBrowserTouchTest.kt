package com.mediareview.app.feature.v2.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.mediareview.app.feature.v2.data.*
import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.testHomeViewModel
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 本地合成树的实际 FolderBrowser pointer / 面包屑 / 媒体分页，无真实 Server 或 Jellyfin。 */
class FolderBrowserTouchTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedViewModels = ViewModelStore()
    private var originalMode = V2DataMode.DEMO

    @Before fun setFixtureSource() = runBlocking {
        val store = V2DataModeStore(context)
        originalMode = store.current()
        store.set(V2DataMode.DEMO)
    }
    @After fun restoreFixtureSource() {
        compose.runOnIdle { ownedViewModels.clear() }
        runBlocking { V2DataModeStore(context).set(originalMode) }
    }

    @Test fun actualFolderClicksUseParentTreeBreadcrumbsAndBoundedDirectPages() {
        val queries = CopyOnWriteArrayList<V2MediaQuery>()
        val sample = DemoMediaCatalog.buildMedia().first { !it.isVideo }
        val nodes = listOf(
            V2FolderNode("root", null, "library", "媒体库", 1, 0, 76, 0),
            V2FolderNode("movies", "root", "library", "电影", 1, 1, 76, 1),
            V2FolderNode("action", "movies", "library", "动作", 0, 75, 75, 75),
        )
        val demo = DemoMediaRepository(context, AlbumCoverStore(context))
        val repository = object : MediaRepository by demo {
            override suspend fun folderTree() = nodes
            override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage {
                queries += query
                val all = when (query.folderId) {
                    "root" -> emptyList()
                    "movies" -> listOf(sample.copy(id = "movie-direct", name = "电影直接媒体", folderId = "movies"))
                    "action" -> (1..75).map { sample.copy(id = "action-$it", name = "动作照片$it", folderId = "action") }
                    else -> listOf(sample)
                }
                return V2MediaPage(all.drop((query.page - 1) * query.pageSize).take(query.pageSize), query.page, query.pageSize, all.size)
            }
        }
        val vm = testHomeViewModel(repository, SearchHistoryStore(context))
        compose.runOnIdle { ownedViewModels.put("folder-fixture", vm) }
        compose.setContent { FolderBrowserScreen(vm, null, {}, { _, _, _ -> }) }
        compose.waitUntil(10_000) { vm.folderTree.value.size == 3 }
        compose.waitForIdle()
        compose.onNodeWithTag("folder_movies").assertIsDisplayed().performTouchInput { click() }
        compose.waitUntil(10_000) { vm.folderBrowserState.value.folderId == "movies" && !vm.folderBrowserState.value.loading }
        compose.onNodeWithTag("folder_action").assertIsDisplayed()
        compose.onNodeWithText("电影直接媒体").assertExists()
        compose.onNodeWithTag("folder_action").performTouchInput { click() }
        compose.waitUntil(10_000) { vm.folderBrowserState.value.folderId == "action" && vm.folderBrowserState.value.items.size == 50 }
        compose.onNodeWithText("动作照片1").assertExists()
        compose.onAllNodesWithText("电影直接媒体").assertCountEquals(0)
        compose.onNodeWithTag("home_grid").performScrollToIndex(40)
        compose.waitUntil(10_000) { vm.folderBrowserState.value.items.size == 75 }
        assertEquals(listOf(1, 2), queries.filter { it.folderId == "action" }.map { it.page })
        assertTrue(queries.filter { it.folderId != null }.all { !it.includeDescendants && it.pageSize == 50 })
        compose.onNodeWithText("电影", substring = false).performTouchInput { click() }
        compose.waitUntil(10_000) { vm.folderBrowserState.value.folderId == "movies" && !vm.folderBrowserState.value.loading }
        compose.onNodeWithTag("folder_action").assertIsDisplayed()
        compose.onNodeWithTag("folder_back").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithTag("folder_movies").assertIsDisplayed()
        compose.onAllNodesWithTag("folder_action").assertCountEquals(0)
        android.util.Log.i("Stage8HHome", "FOLDER_POINTER movies>action pages=[1,2] pageSize=50 direct=true; breadcrumb/back preserve parent-child")
    }

    @Test fun folderChildrenDoNotShiftMediaCoverPrefetchWindow() {
        val paths = CopyOnWriteArrayList<String>()
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val bytes = java.io.ByteArrayOutputStream().apply {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        bitmap.recycle()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                paths += request.requestUrl!!.encodedPath
                return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(bytes))
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        try {
            val sample = DemoMediaCatalog.buildMedia().first { !it.isVideo }
            val media = (0 until 50).map { sample.copy(id = "prefetch-$it", folderId = "root") }
            val tree = listOf(V2FolderNode("root", null, "lib", "媒体库", 40, 50, 50, 50)) +
                (0 until 40).map { V2FolderNode("child-$it", "root", "lib", "子目录$it", 0, 0, 0, 0) }
            val demo = DemoMediaRepository(context, AlbumCoverStore(context))
            val repo = object : MediaRepository by demo {
                override suspend fun folderTree() = tree
                override suspend fun mediaPage(query: V2MediaQuery) = V2MediaPage(media, 1, 50, 50)
                override fun coverUri(media: V2Media) = "$base/cover/${media.id}"
            }
            val vm = testHomeViewModel(repo, SearchHistoryStore(context))
            compose.runOnIdle { ownedViewModels.put("prefetch-fixture", vm) }
            compose.setContent { FolderBrowserScreen(vm, "root", {}, { _, _, _ -> }) }
            compose.waitUntil(10_000) { vm.folderTree.value.size == 41 && vm.folderBrowserState.value.items.size == 50 }
            compose.waitUntil(10_000) { paths.contains("/cover/prefetch-15") }
            compose.onNodeWithTag("home_grid").performScrollToIndex(60)
            // 40目录+media20：末端35处于预热窗口且不在当前可见卡片内。
            compose.waitUntil(10_000) { (20..35).all { paths.contains("/cover/prefetch-$it") } }
        } finally { server.shutdown() }
    }
}
