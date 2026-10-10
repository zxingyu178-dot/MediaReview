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
}
