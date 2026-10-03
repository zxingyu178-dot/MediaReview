package com.mediareview.app.feature.v2.releasenotes

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.BuildConfig
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.V2MainScreen
import com.mediareview.app.feature.v2.data.AlbumCoverStore
import com.mediareview.app.feature.v2.data.DemoMediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.testHomeViewModel
import com.mediareview.app.ui.theme.MediaReviewTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 8C.1 §59 + Stage 8C.2 §44：**Version / What's New 设备侧合同**。
 *
 * - 新版本首次启动显示「本次更新」Sheet，关闭后持久化当前 versionCode；
 * - 跨多个未安装版本升级时，**一个** Sheet 同时包含所有未读版本的更新内容；
 * - 设置（数据源 Sheet）底部显示当前 App 版本，且「本次更新」可主动再次打开
 *   （主动打开只展示当前版本）；
 * - 同版本第二次启动不再自动弹出。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Stage8C1WhatsNewUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<HiltTestActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun setLastSeen(versionCode: Int) {
        runBlocking { ReleaseNotesStore(context).saveLastSeenVersionCode(versionCode) }
    }

    /**
     * 关闭 Sheet 后 `saveLastSeenVersionCode` 是**异步**写入（viewModelScope），
     * 因此断言前必须等待持久化真正落盘，否则是读旧值的竞态（Stage 8D 修复测试脆弱性）。
     */
    private fun awaitSeenVersion(expected: Int) {
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { ReleaseNotesStore(context).lastSeenVersionCode() } == expected
        }
    }

    private fun setContent() {
        val repository = DemoMediaRepository(context, AlbumCoverStore(context))
        val vm = testHomeViewModel(repository, SearchHistoryStore(context))
        compose.setContent {
            MediaReviewTheme {
                V2MainScreen(vm = vm)
            }
        }
    }

    @Test
    fun upgradeShowsSingleSheetAndSavesSeenVersion() {
        // 相邻版本升级（上一版 = 当前版本 - 1）
        setLastSeen(BuildConfig.VERSION_CODE - 1)
        setContent()

        compose.waitUntilExactlyOneExists(hasTestTag("whats_new_sheet"), timeoutMillis = 15_000)
        compose.onNodeWithText("本次更新").assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertIsDisplayed()

        compose.onNodeWithText("知道了").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("whats_new_sheet"), timeoutMillis = 10_000)

        awaitSeenVersion(BuildConfig.VERSION_CODE)
        assertEquals(
            BuildConfig.VERSION_CODE,
            runBlocking { ReleaseNotesStore(context).lastSeenVersionCode() },
        )
    }

    @Test
    fun multiVersionUpgradeShowsAllUnreadNotesInOneSheet() {
        // 跨多个未安装版本（如 8 → 12，中间 9、10、11 未安装）
        setLastSeen(8)
        setContent()

        // 仍然只有一个 Sheet，且同时包含所有未读版本的内容（§21）
        compose.waitUntilExactlyOneExists(hasTestTag("whats_new_sheet"), timeoutMillis = 15_000)
        // 多版本聚合时内容可能超出一屏，统一用 assertExists（tree 中存在即通过）。
        compose.onNodeWithText("来自 2.0.0-alpha2").assertExists()
        compose.onNodeWithText("来自 2.0.0-alpha3").assertExists()
        compose.onNodeWithText("来自 2.0.0-alpha4").assertExists()
        compose.onNodeWithText("来自 2.0.0-alpha5").assertExists()
        compose.onNodeWithText("来自 2.0.0-alpha6").assertExists()

        compose.onNodeWithText("知道了").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("whats_new_sheet"), timeoutMillis = 10_000)
        awaitSeenVersion(BuildConfig.VERSION_CODE)
        assertEquals(
            BuildConfig.VERSION_CODE,
            runBlocking { ReleaseNotesStore(context).lastSeenVersionCode() },
        )
    }

    @Test
    fun settingsShowsVersionAndCanReopenWhatsNew() {
        // 当前版本已看过：启动不自动弹，但设置页可主动打开
        setLastSeen(BuildConfig.VERSION_CODE)
        setContent()

        compose.onNode(hasContentDescription("整理")).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("organize_page"), timeoutMillis = 15_000)
        compose.onNodeWithText("数据源").performClick()

        compose.onNodeWithText("MediaReview ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("本次更新").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("whats_new_sheet"), timeoutMillis = 10_000)
        // 设置页主动打开只展示当前版本（不出现其它版本分区标题）
        compose.onNodeWithText("来自 ${BuildConfig.VERSION_NAME}").assertDoesNotExist()
    }
}