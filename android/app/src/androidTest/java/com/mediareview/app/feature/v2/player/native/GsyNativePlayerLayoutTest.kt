package com.mediareview.app.feature.v2.player.native

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists

import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.feature.v2.player.gsy.GsyPlaybackRequest
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.ui.theme.MediaReviewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 2.2.1 播放器布局 Semantic 测试（模拟器 instrumentation）。
 *
 * 目的：防止再次出现"逻辑单测全 PASS、第一眼布局却是坏的"——
 * 直接挂载真实 GSY Native 播放器，验证 TOP / CENTER / BOTTOM 三层控件存在、
 * 三层边界不交叠，以及更多 / 倍速 / 比例三个 Sheet 可以正常打开。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GsyNativePlayerLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun demoRequest(index: Int, rawName: String, title: String) = GsyPlaybackRequest(
        mediaId = "demo-$index",
        title = title,
        url = "android.resource://com.mediareview.app/raw/$rawName",
    )

    private fun launchPlayer() {
        val list = listOf(
            demoRequest(5, "demo_05_longer", "AI视频-测试 005"),
            demoRequest(6, "demo_06_wide", "AI视频-测试 006"),
        )
        compose.setContent {
            MediaReviewTheme {
                GsyNativePlayerScreen(
                    playbackContext = PlaybackContext(mediaList = list, currentIndex = 0),
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** 进入播放器后立即暂停，保证控制层常驻，便于断言。 */
    private fun launchAndPause() {
        launchPlayer()
        // swiftshader 软解 + GSY 起播时序较慢：中央主按钮渲染出来即可
        // （播放中 contentDescription 为「暂停」，尚未起播 / 已暂停为「播放」），
        // 不把软解环境的起播延迟误判为布局失败。
        compose.waitUntil(30_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("暂停")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("播放")).fetchSemanticsNodes().isNotEmpty()
        }
        // 若已在播放则点中央按钮暂停，使控制层常驻，便于布局断言
        if (compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("暂停")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("暂停").performClick()
        }
        compose.waitUntilExactlyOneExists(
            matcher = androidx.compose.ui.test.hasContentDescription("播放"),
            timeoutMillis = 10_000,
        )
    }

    @Test
    fun topCenterBottom三层控制控件全部存在() {
        launchAndPause()

        // TOP
        compose.onNodeWithTag("player_top_bar").assertExists()
        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithContentDescription("更多").assertExists()
        // CENTER
        compose.onNodeWithTag("player_center_controls").assertExists()
        compose.onNodeWithContentDescription("快退10秒").assertExists()
        compose.onNodeWithContentDescription("播放").assertExists()
        compose.onNodeWithContentDescription("快进10秒").assertExists()
        // BOTTOM
        compose.onNodeWithTag("player_bottom_bar").assertExists()
        compose.onNodeWithText("1x").assertExists()
        compose.onNodeWithText("适应").assertExists()
        compose.onNodeWithContentDescription("锁定").assertExists()
        compose.onNodeWithContentDescription("全屏").assertExists()
    }

    @Test
    fun topCenterBottom三层边界不得交叠() {
        launchAndPause()

        val top = compose.onNodeWithTag("player_top_bar").fetchSemanticsNode().boundsInRoot
        val center = compose.onNodeWithTag("player_center_controls").fetchSemanticsNode().boundsInRoot
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
        compose.onNodeWithText("字幕").assertExists()
        compose.onNodeWithText("音轨").assertExists()
        compose.onNodeWithText("视频信息").assertExists()
    }

    @Test
    fun 倍速Sheet可以打开并展示速度选项() {
        launchAndPause()
        compose.onNodeWithText("1x").performClick()
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
        compose.onNodeWithText("填充").assertExists()
        compose.onNodeWithText("16:9").assertExists()
        compose.onNodeWithText("4:3").assertExists()
    }

    @Test
    fun back优先关闭Sheet而不是退出播放器() {
        launchAndPause()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitUntilExactlyOneExists(
            matcher = androidx.compose.ui.test.hasText("视频信息"),
            timeoutMillis = 5_000,
        )
        Espresso.pressBack()
        // Sheet 关闭后，BottomBar 仍在（未退出播放器）
        compose.onNodeWithTag("player_bottom_bar").assertExists()
    }
}
