package com.mediareview.app.feature.v2.player.native

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.waitUntilExactlyOneExists

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2PlaybackStage
import com.mediareview.app.feature.v2.player.V2PlaybackUiState
import com.mediareview.app.feature.v2.player.V2PlayerState
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.ui.theme.MediaReviewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 2.2.1 播放器布局 Semantic 测试（模拟器 instrumentation）。
 *
 * 目的：防止再次出现"逻辑单测 PASS、第一眼布局却是坏的"——
 * 直接挂载真实 GSY Native 播放器，验证 TOP / CENTER / BOTTOM 三层控件存在、
 * 三层边界不交叠，更多 / 倍速 / 比例三个 Sheet 可以正常打开。
 *
 * Stage8H：暂停中央单播放，导航到底栏且快进按钮不存在，
 * 且首集"上一条"、末集"下一条"按列表边界置灰禁用。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GsyNativePlayerLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<HiltTestActivity>()

    private fun demoItem(index: Int, title: String) = PlaybackQueueItem(
        mediaId = "demo-$index",
        title = title,
    )

    private fun demoSource(index: Int, rawName: String, title: String) = V2PlaybackSource(
        mediaId = "demo-$index",
        title = title,
        direct = V2PlaybackEndpoint(url = "android.resource://com.mediareview.app/raw/$rawName"),
    )

    private fun launchPlayer(startIndex: Int = 0) {
        val queue = listOf(
            demoItem(5, "AI视频-测试 005"),
            demoItem(6, "AI视频-测试 006"),
        )
        // Stage 8A：播放源由 ViewModel 异步解析后以 Ready 状态下发，测试直接构造 Ready。
        val source = if (startIndex == 0) {
            demoSource(5, "demo_05_longer", "AI视频-测试 005")
        } else {
            demoSource(6, "demo_06_wide", "AI视频-测试 006")
        }
        val endpoint = source.direct
        compose.setContent {
            MediaReviewTheme {
                GsyNativePlayerScreen(
                    state = V2PlayerState(
                        context = PlaybackContext(queue = queue, currentIndex = startIndex),
                        ui = V2PlaybackUiState.Ready(
                            source = source,
                            stage = V2PlaybackStage.DIRECT,
                            endpoint = endpoint,
                        ),
                    ),
                    onRequestIndex = {},
                    onPlaybackFailed = { _ -> },
                    onRetry = {},
                    onReportProgress = { _, _ -> },
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** 进入播放器后立即暂停，保证控制层常驻，便于断言。 */
    private fun launchAndPause(startIndex: Int = 0) {
        launchPlayer(startIndex)
        // Full正常播放时中央无按钮；通过实际更多菜单暂停，检查真实产品控制布局。
        compose.waitUntil(60_000) {
            var playing = false
            compose.runOnUiThread { playing = com.shuyu.gsyvideoplayer.GSYVideoManager.instance().isPlaying }
            playing
        }
        if (compose.onAllNodesWithTag("player_top_bar").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player_gesture_surface").performTouchInput { click(center) }
        }
        compose.waitUntilExactlyOneExists(androidx.compose.ui.test.hasContentDescription("更多"), 5_000)
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("暂停播放").performClick()
        compose.waitUntilExactlyOneExists(
            matcher = androidx.compose.ui.test.hasContentDescription("播放"),
            timeoutMillis = 20_000,
        )
    }

    @Test
    fun topCenterBottom三层控制控件全部存在() {
        launchAndPause()

        // TOP
        compose.onNodeWithTag("player_top_bar").assertExists()
        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithContentDescription("更多").assertExists()
        // CENTER（Stage6：5 键 = 上一条 / 快退 / 播放 / 快进 / 下一条）
        compose.onNodeWithTag("player_btn_play_pause").assertExists()
        compose.onNodeWithContentDescription("上一条").assertExists()
        compose.onNodeWithContentDescription("快退10秒").assertDoesNotExist()
        compose.onNodeWithContentDescription("播放").assertExists()
        compose.onNodeWithContentDescription("快进10秒").assertDoesNotExist()
        compose.onNodeWithContentDescription("下一条").assertExists()
        // BOTTOM
        compose.onNodeWithTag("player_bottom_bar").assertExists()
        compose.onNodeWithText("1×").assertExists()
        compose.onNodeWithText("适应").assertExists()
        compose.onNodeWithContentDescription("锁定").assertExists()
        compose.onNodeWithContentDescription("全屏").assertExists()
    }

    @Test
    fun 首集上一条置灰禁用而下一条可用() {
        launchAndPause(startIndex = 0)
        compose.onNodeWithContentDescription("上一条").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一条").assertIsEnabled()
    }

    @Test
    fun 末集下一条置灰禁用而上一条可用() {
        launchAndPause(startIndex = 1)
        compose.onNodeWithContentDescription("下一条").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一条").assertIsEnabled()
    }

    @Test
    fun topCenterBottom三层边界不得交叠() {
        launchAndPause()

        val top = compose.onNodeWithTag("player_top_bar").fetchSemanticsNode().boundsInRoot
        val center = compose.onNodeWithTag("player_btn_play_pause").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag("player_bottom_bar").fetchSemanticsNode().boundsInRoot

        assertTrue("TOP 底边不得侵入 CENTER：top.bottom=${top.bottom} center.top=${center.top}",
            top.bottom <= center.top + 1f)
        assertTrue("CENTER 底边不得侵入 BOTTOM：center.bottom=${center.bottom} bottom.top=${bottom.top}",
            center.bottom <= bottom.top + 1f)
    }

    @Test
    fun 更多Sheet可以打开并展示完整入口() {
        launchAndPause()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitUntilExactlyOneExists(
            matcher = androidx.compose.ui.test.hasText("播放速度"),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText("上一条").assertExists()
        compose.onNodeWithText("下一条").assertExists()
        compose.onNodeWithText("画面比例").assertExists()
        compose.onNodeWithText("字幕").assertDoesNotExist()
        compose.onNodeWithText("音轨").assertDoesNotExist()
        compose.onNodeWithText("视频信息").assertExists()
    }

    @Test
    fun 倍速Sheet可以打开并展示速度选项() {
        launchAndPause()
        compose.onNodeWithText("1×").performClick()
        // SpeedSheet 独有选项
        compose.waitUntilExactlyOneExists(
            matcher = androidx.compose.ui.test.hasText("1.5x"),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText("0.5x").assertExists()
        compose.onNodeWithText("2x").assertExists()
    }

    @Test
    fun 比例Sheet可以打开并展示画面模式() {
        launchAndPause()
        // BottomBar 的"适应"按钮（ScaleSheet 中也有"适应"选项，等待独有的"裁剪"出现即可）
        compose.onAllNodesWithText("适应")[0].performClick()
        compose.waitUntilExactlyOneExists(
            matcher = androidx.compose.ui.test.hasText("裁剪"),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText("填满").assertExists()
        compose.onNodeWithText("16:9").assertDoesNotExist()
        compose.onNodeWithText("4:3").assertDoesNotExist()
    }


    /** 仅正常确认真实可读的系统沉浸引导，不绕过焦点、不写Settings。 */
    @Suppress("DEPRECATION")
    private fun confirmVisibleSystemImmersiveGuideAndWaitForAppFocus() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val systemPackages = setOf("android", "com.android.systemui")
        var clickedGuide = false
        var observedPackage = "unavailable"
        compose.waitUntil(5_000) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    observedPackage = root.packageName?.toString() ?: "unavailable"
                    if (!clickedGuide && observedPackage in systemPackages) {
                        // 文案只有实际树存在时才参与点击；绝不点击App里的同名按钮。
                        val nodes = root.findAccessibilityNodeInfosByText("Got it")
                        try {
                            val button = nodes.singleOrNull {
                                it.packageName?.toString() in systemPackages &&
                                    it.text?.toString() == "Got it" &&
                                    it.className?.toString() == "android.widget.Button" &&
                                    it.isVisibleToUser && it.isEnabled && it.isClickable &&
                                    it.actionList.any { action -> action.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }
                            }
                            if (button != null) {
                                assertTrue("真实系统引导按钮ACTION_CLICK应成功",
                                    button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                                clickedGuide = true
                            }
                        } finally {
                            nodes.forEach { it.recycle() }
                        }
                    }
                } finally {
                    root.recycle()
                }
            }
            var appWindowFocused = false
            compose.runOnUiThread {
                appWindowFocused = android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() }
            }
            if (!appWindowFocused) {
                android.util.Log.d("MRLayoutTest", "awaitAppFocus rootPackage=$observedPackage guideClicked=$clickedGuide")
            }
            appWindowFocused
        }
    }

    @Test
    fun back优先关闭Sheet而不是退出播放器() {
        launchAndPause()
        confirmVisibleSystemImmersiveGuideAndWaitForAppFocus()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitUntilExactlyOneExists(
            matcher = androidx.compose.ui.test.hasText("视频信息"),
            timeoutMillis = 5_000,
        )
        // Title semantics arrive during the entry animation, before the dialog owns key focus.
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            var dialogFocused = false
            compose.runOnUiThread {
                dialogFocused = android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it !== compose.activity.window.decorView && it.hasWindowFocus()
                }
            }
            dialogFocused
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("视频信息").fetchSemanticsNodes().isEmpty() }
        // Sheet 关闭后，BottomBar 仍在（未退出播放器）
        compose.onNodeWithTag("player_bottom_bar").assertExists()
    }
}
