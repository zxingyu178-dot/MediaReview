package com.mediareview.app.feature.v2

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediareview.app.MainActivity
import com.mediareview.app.BuildConfig
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.home.SearchUiState
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.releasenotes.ReleaseNotesStore
import com.shuyu.gsyvideoplayer.GSYVideoManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 生产 MainActivity/Repository/HTTP/导航上的1000媒体fixture。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Stage8GAppSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var fixture: Stage8FHttpFixture? = null
    private val evidence by lazy { File(context.getExternalFilesDir(null), "stage8g-smoke/${testName.methodName}").apply { mkdirs() } }

    @After fun close() {
        if (scenario != null) {
            runCatching { screenshot("99-final") }
            runCatching { File(evidence, "99-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString()) }
            runCatching { File(evidence, "http-hits.txt").writeText(fixture?.hits?.joinToString("\n").orEmpty()) }
        }
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { GSYVideoManager.releaseAllVideos() }
        runBlocking { ServerProfileStore(context).clear(); V2DataModeStore(context).set(V2DataMode.DEMO) }
        fixture?.close()
    }

    private fun launch(): Stage8FHttpFixture {
        runBlocking {
            ServerProfileStore(context).clear()
            V2DataModeStore(context).set(V2DataMode.DEMO)
            ReleaseNotesStore(context).saveLastSeenVersionCode(BuildConfig.VERSION_CODE)
            SearchHistoryStore(context).clear()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val http = Stage8FHttpFixture(largeLibrary = true).also { fixture = it }
        click("· 切换数据源"); click("我的服务器")
        field("服务器地址（如 192.168.1.10）").performTextReplacement(http.baseUrl)
        field("配对码（在服务器端查看）").performTextReplacement("123456")
        compose.onNodeWithText("连接服务器", useUnmergedTree = true).performClick()
        waitText("已配对"); back()
        if (compose.onAllNodesWithText("数据源", substring = false).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Close sheet", useUnmergedTree = true).performClick()
        }
        compose.waitUntilDoesNotExist(hasText("数据源", substring = false), 15_000)
        waitText(http.name("v1"))
        return http
    }
    private fun field(label: String): SemanticsNodeInteraction {
        val matcher = hasSetTextAction() and (hasText(label) or hasAnyDescendant(hasText(label)))
        compose.waitUntilExactlyOneExists(matcher, 15_000)
        return compose.onNode(matcher, useUnmergedTree = true)
    }
    private fun searches(http: Stage8FHttpFixture) = http.hits.filter { it.path == "/api/v1/media" && !it.search.isNullOrBlank() }
    private fun homeRequests(http: Stage8FHttpFixture) = http.hits.count {
        it.path == "/api/v1/media" && it.search.isNullOrBlank() && it.pageSize == 50
    }
    private fun searchState(): SearchUiState {
        lateinit var state: SearchUiState
        requireNotNull(scenario).onActivity { activity ->
            state = ViewModelProvider(activity)[V2HomeViewModel::class.java].searchState.value
        }
        return state
    }

    @Test fun searchPagingBackAndHomeRestore() {
        val http = launch()
        assertEquals(1, homeRequests(http))
        assertEquals(50, http.hits.first { it.path == "/api/v1/media" && it.pageSize == 50 }.pageSize)
        compose.onNodeWithTag("home_grid").performScrollToIndex(40)
        compose.waitUntil(15_000) {
            var loaded = false
            scenario!!.onActivity { loaded = ViewModelProvider(it)[V2HomeViewModel::class.java].currentList.value.size == 100 }
            loaded
        }
        compose.onNodeWithTag("home_grid").performScrollToIndex(20)
        val homeCount = homeRequests(http)
        val homeTop = compose.onNodeWithText(http.name("v21")).fetchSemanticsNode().boundsInRoot.top
        screenshot("01-home-middle")
        click("搜索媒体…")
        compose.onNodeWithText("输入关键词搜索").assertIsDisplayed()
        compose.onNodeWithTag("home_grid").assertDoesNotExist()
        val input = compose.onNode(hasSetTextAction())
        input.performTextReplacement("a"); input.performTextReplacement("ab"); input.performTextReplacement("abc")
        compose.waitUntil(15_000) { searches(http).any { it.search == "abc" } }
        waitText(http.name("v1"))
        assertEquals(listOf(1), searches(http).filter { it.search == "abc" }.map { it.page })
        assertTrue(searches(http).all { it.pageSize == 50 })
        input.performImeAction(); back() // 关闭输入法，不退出搜索
        http.failSearchPage = 2
        compose.onNodeWithTag("search_grid").performScrollToIndex(40)
        compose.waitUntil(15_000) { searchState().error != null }
        compose.onNodeWithTag("search_grid").performScrollToIndex(50)
        waitText("加载更多失败 · 重试")
        compose.onNodeWithTag("search_grid").assertExists()
        http.failSearchPage = 0
        compose.onNodeWithText("加载更多失败 · 重试").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) { searches(http).count { it.search == "abc" && it.page == 2 } == 2 }
        compose.waitUntil(15_000) { searchState().items.size == 100 }
        compose.onNodeWithTag("search_grid").performScrollToIndex(84)
        compose.waitUntil(15_000) { searchState().items.size == 150 }
        compose.onNodeWithTag("search_grid").performScrollToIndex(70)
        val searchTop = compose.onNodeWithText(http.name("v71")).fetchSemanticsNode().boundsInRoot.top
        val searchCount = searches(http).size
        compose.onNodeWithText(http.name("v71")).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("player_btn_next"), 15_000)
        compose.onNodeWithTag("player_btn_next").assertIsEnabled()
        screenshot("02-search-player")
        back()
        compose.onNodeWithTag("search_grid").assertExists()
        compose.onNodeWithText(http.name("v71")).assertIsDisplayed()
        assertEquals(searchTop, compose.onNodeWithText(http.name("v71")).fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals("abc", searchState().query)
        assertEquals(searchCount, searches(http).size)
        compose.onNodeWithContentDescription("关闭搜索").performClick()
        compose.onNodeWithText(http.name("v21")).assertIsDisplayed()
        assertEquals(homeTop, compose.onNodeWithText(http.name("v21")).fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(homeCount, homeRequests(http))
        screenshot("03-home-restored")
        click("搜索媒体…")
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
        compose.onNodeWithTag("search_grid").assertDoesNotExist()
        compose.onNodeWithTag("home_grid").assertDoesNotExist()
        assertEquals(homeCount, homeRequests(http))
        screenshot("04-only-history")
    }

    @Test fun favoriteVideoAndImageWindowsStayIndependent() {
        val http = launch()
        compose.onNodeWithContentDescription("收藏").performClick()
        waitText(http.name("v302"))
        compose.onNodeWithText(http.name("v302")).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("player_btn_previous"), 15_000)
        compose.onNodeWithTag("player_btn_previous").assertIsEnabled().performClick()
        waitText(http.name("v301"))
        compose.waitUntilExactlyOneExists(hasTestTag("player_btn_next"), 15_000)
        compose.onNodeWithTag("player_btn_previous").assertIsNotEnabled()
        compose.onNodeWithTag("player_btn_next").assertIsEnabled().performClick(); waitText(http.name("v302"))
        compose.waitUntilExactlyOneExists(hasTestTag("player_btn_next"), 15_000)
        compose.onNodeWithTag("player_btn_next").assertIsEnabled().performClick(); waitText(http.name("v303"))
        compose.waitUntilExactlyOneExists(hasTestTag("player_btn_next"), 15_000)
        compose.onNodeWithTag("player_btn_next").assertIsNotEnabled()
        screenshot("05-favorite-video-C")
        back(); waitText(http.name("p302"))
        compose.onNodeWithText(http.name("p302")).performClick()
        waitText("2 / 3")
        compose.onNodeWithTag("viewer_favorite").performClick()
        compose.waitUntil(15_000) { http.hits.any { it.path == "/api/v1/favorites/p302" && it.method == "DELETE" } }
        compose.onNodeWithText("2 / 3").assertExists()
        compose.onNodeWithTag("viewer_pager").performTouchInput { swipeRight() }
        waitText(http.name("p301"))
        compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() }
        waitText(http.name("p302"))
        compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() }
        waitText(http.name("p303"))
        screenshot("06-favorite-image-C")
        back()
        compose.onNodeWithText(http.name("p302")).assertDoesNotExist()
        compose.onNodeWithText(http.name("p301")).assertExists()
        compose.onNodeWithText(http.name("p303")).assertExists()
    }

    @Test fun favoriteImageOutsideHomeUpdatesInPlace() {
        val http = launch()
        compose.onNodeWithContentDescription("收藏").performClick()
        waitText(http.name("p302"))
        compose.onNodeWithText(http.name("p302")).performClick()
        waitText("2 / 3")
        compose.onNodeWithTag("viewer_favorite").assertContentDescriptionEquals("已收藏")

        compose.onNodeWithTag("viewer_favorite").performSemanticsAction(SemanticsActions.OnClick)
        File(evidence, "i2-after-click-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        compose.waitUntil(15_000) { http.hits.any { it.path == "/api/v1/favorites/p302" && it.method == "DELETE" } }
        compose.waitUntil(15_000) { !http.isFavorite("p302") }
        compose.waitUntilExactlyOneExists(hasTestTag("viewer_favorite") and hasContentDescription("收藏"), 15_000)
        compose.onNodeWithText("2 / 3").assertExists()

        compose.onNodeWithTag("viewer_favorite").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) { http.hits.any { it.path == "/api/v1/favorites/p302" && it.method == "POST" } }
        compose.waitUntil(15_000) { http.isFavorite("p302") }
        compose.waitUntilExactlyOneExists(hasTestTag("viewer_favorite") and hasContentDescription("已收藏"), 15_000)
        compose.onNodeWithText("2 / 3").assertExists()

        compose.onNodeWithTag("viewer_favorite").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) {
            http.hits.count { it.path == "/api/v1/favorites/p302" && it.method == "DELETE" } == 2
        }
        compose.waitUntil(15_000) { !http.isFavorite("p302") }
        compose.waitUntilExactlyOneExists(hasTestTag("viewer_favorite") and hasContentDescription("收藏"), 15_000)
        compose.onNodeWithText("2 / 3").assertExists()
        assertEquals(
            listOf("DELETE", "POST", "DELETE"),
            http.hits.filter { it.path == "/api/v1/favorites/p302" }.map { it.method },
        )
        compose.onNodeWithTag("viewer_pager").performTouchInput { swipeRight() }
        waitText(http.name("p301"))
        compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() }
        waitText(http.name("p302"))
        back()
        compose.onNodeWithText(http.name("p302")).assertDoesNotExist()
    }
    private fun click(text: String) { waitText(text); compose.onNodeWithText(text).performClick() }
    private fun waitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), 15_000)
    private fun back() { androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle() }
    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
