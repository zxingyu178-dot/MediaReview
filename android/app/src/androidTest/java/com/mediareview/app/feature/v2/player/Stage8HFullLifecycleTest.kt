package com.mediareview.app.feature.v2.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerScreen
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** 真实60s认证HTTP：方向切换、进度回调与离开Screen后的内核停止。 */
@RunWith(AndroidJUnit4::class)
class Stage8HFullLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null
    private val visible = mutableStateOf(true)
    private var exits = 0
    private data class Progress(val id: String, val position: Long, val paused: Boolean,
        val kernelPosition: Long, val elapsed: Long)
    private val reports = CopyOnWriteArrayList<Progress>()
    private var wallEpoch = 0L
    private var composeEpoch = 0L

    /** 只把真实流逝时间补给Compose；禁止waitUntil默认每10ms推进16ms虚拟delay。 */
    private fun waitWallClock(timeoutMillis: Long, condition: () -> Boolean) {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val due = composeEpoch + now - wallEpoch
            val remaining = due - compose.mainClock.currentTime
            if (remaining > 0L) compose.mainClock.advanceTimeBy(remaining, ignoreFrameDuration = true)
            if (condition()) return
            Assert.assertTrue("真实时间等待超时: ${timeoutMillis}ms", now - started <= timeoutMillis)
            SystemClock.sleep(10)
        }
    }

    private fun assertProgressPosted(event: Progress, after: Int = 0) {
        waitWallClock(5_000) {
            fixture!!.progresses.drop(after).any {
                it.method == "POST" && it.mediaId == event.id && it.paused == event.paused &&
                    abs(it.position - event.position) <= 50L
            }
        }
    }

    @After fun cleanup() {
        // 仅在测试断言完成/失败后收尾；不能用这里的release制造退出验收通过。
        compose.runOnUiThread {
            GSYVideoManager.releaseAllVideos()
            compose.activity.viewModelStore.clear()
        }
        fixture?.close()
    }

    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun position() = kernel { it.currentPosition }
    private fun body() = compose.onNodeWithTag("player_gesture_surface")
    private fun showControls() {
        if (compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isEmpty()) {
            body().performTouchInput { click(center) }
        }
        waitWallClock(5_000) { compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun button(description: String) = compose.onNodeWithContentDescription(description)
        .performTouchInput { click(center) }

    private fun launch() {
        compose.mainClock.autoAdvance = false
        wallEpoch = SystemClock.elapsedRealtime()
        composeEpoch = compose.mainClock.currentTime
        val activity = compose.activity
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getActivityInfo(activity.componentName, 0)
        val required = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_KEYBOARD_HIDDEN
        Assert.assertEquals("先集成debug-only配置；不接受重建宿主后重新setContent", required, info.configChanges and required)
        compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        waitWallClock(10_000) { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        val live = RealHttpPlaybackFixture(longVideo = true).also { fixture = it }
        val vm = V2NativePlayerViewModel(live.repository)
        compose.runOnUiThread {
            activity.viewModelStore.put("stage8h-full-lifecycle", vm)
            vm.open(PlaybackContext(listOf(PlaybackQueueItem("first", "真实生命周期测试"))))
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            MediaReviewTheme {
                if (visible.value) GsyNativePlayerScreen(
                    state = state,
                    onRequestIndex = vm::moveTo,
                    onPlaybackFailed = vm::onPlaybackFailed,
                    onRetry = vm::retry,
                    onReportProgress = { _, _ -> Assert.fail("应调用携带装载mediaId的进度回调") },
                    onBack = { exits++; visible.value = false },
                    onPlaybackStable = vm::onPlaybackStable,
                    onReportLoadedProgress = { id, ms, paused ->
                        reports += Progress(id, ms, paused, GSYVideoManager.instance().currentPosition, SystemClock.elapsedRealtime())
                        vm.reportLoadedProgress(id, ms, paused)
                    },
                )
            }
        }
        waitWallClock(60_000) { kernel { it.isPlaying && it.duration >= 59_000 && it.currentPosition > 500 } }
        val before = position()
        waitWallClock(10_000) { position() > before + 300 }
        Assert.assertTrue(live.videos.isNotEmpty())
        Assert.assertTrue(live.videos.all { it.mediaId == "first" && it.authenticated })
    }

    @Test fun manualFullscreenBackKeepsSameActivityHostKernelAndPosition() {
        launch()
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput { click(Offset(width * .3f, centerY)) }
        waitWallClock(10_000) { abs(position() - 18_000) < 2_500 }
        val activity = compose.activity
        val host = kernel { it.listener() }
        val player = kernel { it.player.mediaPlayer }
        Assert.assertNotNull(host)
        Assert.assertTrue(player is IjkExo2MediaPlayer)
        val before = position()
        val begun = SystemClock.elapsedRealtime()
        showControls()
        button("全屏")
        waitWallClock(10_000) {
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                activity.resources.displayMetrics.widthPixels > activity.resources.displayMetrics.heightPixels
        }
        Assert.assertSame(activity, compose.activity)
        Assert.assertSame(host, kernel { it.listener() })
        Assert.assertSame(player, kernel { it.player.mediaPlayer })
        Assert.assertTrue(kernel { it.isPlaying })
        Assert.assertEquals(0, exits)
        showControls()
        button("返回")
        waitWallClock(10_000) {
            activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                activity.resources.displayMetrics.heightPixels > activity.resources.displayMetrics.widthPixels
        }
        Assert.assertSame(activity, compose.activity)
        Assert.assertSame(host, kernel { it.listener() })
        Assert.assertSame(player, kernel { it.player.mediaPlayer })
        Assert.assertEquals("首次返回只退全屏", 0, exits)
        val after = position()
        val elapsed = SystemClock.elapsedRealtime() - begun
        Assert.assertTrue("旋转不能归零/重seek: before=$before after=$after elapsed=$elapsed",
            after >= before - 500 && after <= before + elapsed + 2_500)
        Assert.assertTrue(kernel { it.isPlaying })
        waitWallClock(5_000) { position() > after + 300 }
        // HTTP可能因Range再次请求，不能用请求次数断言重复setUp；身份和position才是内核依据。
        Assert.assertTrue(fixture!!.videos.all { it.mediaId == "first" && it.authenticated })
    }

    @Test fun periodicPauseAndExitReportRealIdentityPositionAndReleaseSound() {
        launch()
        // 不seek；禁用虚拟autoAdvance并仅按真实时间补帧，真实15s且内核约15s均须成立。
        val initial = reports.first { !it.paused && it.position == 0L }
        waitWallClock(30_000) { reports.any { !it.paused && it.position > 0L } }
        val periodic = reports.first { !it.paused && it.position > 0L }
        Assert.assertTrue("15s周期不能立即上报", periodic.elapsed - initial.elapsed >= 14_500)
        Assert.assertTrue("真实播放应走过约15秒", periodic.position >= 14_000)
        assertRealProgress(periodic)
        assertProgressPosted(periodic)
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput { click(Offset(width * .5f, centerY)) }
        waitWallClock(10_000) { abs(position() - 30_000) < 2_500 }
        showControls()
        button("更多")
        // autoAdvance=false：先按真实流逝补Sheet重组/动画帧，再定位暂停按钮。
        waitWallClock(5_000) {
            compose.onAllNodesWithText("暂停播放").fetchSemanticsNodes().size == 1
        }
        val pauseStart = reports.size
        compose.onNodeWithText("暂停播放").performTouchInput { click(center) }
        waitWallClock(5_000) { !kernel { it.isPlaying } && reports.drop(pauseStart).any { it.paused } }
        val paused = reports.drop(pauseStart).first { it.paused }
        assertRealProgress(paused)
        assertProgressPosted(paused)
        Assert.assertTrue("暂停回调不能用零位置", abs(paused.position - 30_000) < 2_500)
        waitWallClock(5_000) { compose.onAllNodesWithContentDescription("播放").fetchSemanticsNodes().size == 1 }
        button("播放")
        waitWallClock(5_000) { kernel { it.isPlaying } }
        val resumed = position()
        waitWallClock(5_000) { position() > resumed + 500 }
        val oldPlayer = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        val oldHost = kernel { it.listener() }
        val beforeExit = position()
        val exitStart = reports.size
        val exitPostStart = fixture!!.progresses.size
        showControls()
        button("返回") // 真实导航按钮触发移除Screen，禁止直接手调controller.release/pause。
        waitWallClock(5_000) { exits == 1 && !visible.value && reports.drop(exitStart).any { it.paused } }
        val exited = reports.drop(exitStart).last { it.paused }
        Assert.assertEquals("first", exited.id)
        assertProgressPosted(exited, exitPostStart)
        Assert.assertTrue("退出回调须保留最后真实位置: before=$beforeExit callback=${exited.position}",
            abs(exited.position - beforeExit) < 2_500 && exited.position > 25_000)
        waitWallClock(5_000) {
            kernel { !it.isPlaying && it.player?.mediaPlayer !== oldPlayer && it.listener() !== oldHost }
        }
        Assert.assertFalse("已离开Screen不能继续声音", kernel { it.isPlaying })
        Assert.assertFalse("被移除的Exo内核也必须停止", kernel { oldPlayer.isPlaying })
        Assert.assertNull("释放后旧Exo装载身份须清除", kernel { oldPlayer.dataSource })
        Assert.assertNotSame(oldPlayer, kernel { it.player?.mediaPlayer })
        Assert.assertNotSame(oldHost, kernel { it.listener() })
        val silentStart = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - silentStart < 1_500) {
            Assert.assertFalse(kernel { it.isPlaying })
            Assert.assertFalse(kernel { oldPlayer.isPlaying })
            Assert.assertNull("释放后旧Exo装载身份须清除", kernel { oldPlayer.dataSource })
            Assert.assertNotSame(oldPlayer, kernel { it.player?.mediaPlayer })
            SystemClock.sleep(100)
        }
        Assert.assertTrue("每个回调都属于实际装载媒体", reports.all { it.id == "first" })
    }

    private fun assertRealProgress(event: Progress) {
        Assert.assertEquals("first", event.id)
        Assert.assertTrue("回调不得用零/预览位点", event.position > 0L)
        Assert.assertTrue("snapshot允许轮询延迟但须贴近真实内核: $event",
            abs(event.position - event.kernelPosition) <= 1_500)
    }
}
