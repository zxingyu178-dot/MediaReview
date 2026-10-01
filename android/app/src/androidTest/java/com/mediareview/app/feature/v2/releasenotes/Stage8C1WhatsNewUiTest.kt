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
 * Stage 8C.1 §59：**Version / What's New 设备侧合同**。
 *
 * - 新版本首次启动显示「本次更新」Sheet，关闭后持久化当前 versionCode；
 * - 设置（数据源 Sheet）底部显示当前 App 版本，且「本次更新」可主动再次打开；
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
        // 模拟"新版本首次安装/升级"：把上次已看版本写成 0（永远不等于当前版本）
        runBlocking { ReleaseNotesStore(context).saveLastSeenVersionCode(0) }
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
    fun newVersionShowsWhatsNewOnceAndSavesSeenVersion() {
        setContent()

        compose.waitUntilExactlyOneExists(hasTestTag("whats_new_sheet"), timeoutMillis = 15_000)
        compose.onNodeWithText("本次更新").assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertIsDisplayed()

        compose.onNodeWithText("知道了").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("whats_new_sheet"), timeoutMillis = 10_000)

        // 关闭后必须把当前版本写入 DataStore（同版本第二次启动不再弹，§39）
        val seen = runBlocking { ReleaseNotesStore(context).lastSeenVersionCode() }
        assertEquals(BuildConfig.VERSION_CODE, seen)
    }

    @Test
    fun settingsShowsVersionAndCanReopenWhatsNew() {
        // 先把当前版本标记为已看：启动时不自动弹，但设置页可以主动打开
        runBlocking { ReleaseNotesStore(context).saveLastSeenVersionCode(BuildConfig.VERSION_CODE) }
        setContent()

        compose.onNode(hasContentDescription("整理")).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("organize_page"), timeoutMillis = 15_000)
        compose.onNodeWithText("数据源").performClick()

        compose.onNodeWithText("MediaReview ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("本次更新").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("whats_new_sheet"), timeoutMillis = 10_000)
    }
}