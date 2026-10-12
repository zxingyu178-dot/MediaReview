package com.mediareview.app.feature.v2.review

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.player.RealHttpPlaybackFixture
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import kotlin.math.abs

/** 真实 Activity STOP/RESUME 与 HTTP 视频；测试不调用任何播放器暂停或恢复入口。 */
@RunWith(AndroidJUnit4::class)
class Stage8HReviewBackgroundTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null
    private lateinit var vm: V2ReviewViewModel

    @After fun cleanup() {
        // A failing background assertion can leave Activity STOPPED. Release first on the real
        // main thread; Compose idle synchronization is intentionally not used while stopped.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { GSYVideoManager.releaseAllVideos() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.viewModelStore.clear() }
        fixture?.close()
    }

    private fun launch(blockFirst: Boolean = false) {
        val live = RealHttpPlaybackFixture(longVideo = true,
            blockFirstVideo = blockFirst).also { fixture = it }
        vm = V2ReviewViewModel(live.repository, live.sessions)
        compose.activity.viewModelStore.put("stage8h-review-background", vm)
        compose.setContent { MediaReviewTheme { V2ReviewScreen(vm, {}, {}) } }
        if (blockFirst) {
            awaitKernel(10_000, "首个真实视频请求未到达") { live.firstVideoRequested.count == 0L }
        } else {
            awaitKernel(60_000, "Review 真实视频未开始播放") {
                kernel { it.isPlaying && it.duration >= 59_000 && it.currentPosition > 500 }
            }
            Assert.assertTrue(live.videos.any { it.mediaId == "first" && it.authenticated })
        }
    }

    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var value: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun position() = kernel { it.currentPosition }
    private fun player() = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
    private fun awaitKernel(timeoutMs: Long, message: String, pumpFrames: Boolean = true, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            Assert.assertTrue(message, SystemClock.elapsedRealtime() < deadline)
            if (pumpFrames) compose.mainClock.advanceTimeByFrame()
            SystemClock.sleep(50)
        }
    }
    private fun assertSilentFor(durationMs: Long = 1_500): Long {
        val paused = position()
        val deadline = SystemClock.elapsedRealtime() + durationMs
        do {
            Assert.assertFalse("Activity STOP 后内核不得继续播放", kernel { it.isPlaying })
            Assert.assertTrue("后台暂停位置不得继续推进", abs(position() - paused) < 750)
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return paused
    }
    private fun seekThirtySeconds() {
        // The kernel can become prepared before the polled Compose snapshot has published its
        // duration. SetProgress is installed by the production bar only when enabled && duration>0.
        // Read this real UI readiness marker before delivering the original physical 50% tap.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("review_seek_bar").fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(androidx.compose.ui.semantics.SemanticsActions.SetProgress) == true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("review_seek_bar").performTouchInput { click(Offset(width * .5f, centerY)) }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (abs(position() - 30_000) >= 2_500) {
            Assert.assertTrue("真实内核未到30秒: position=${position()} duration=${kernel { it.duration }} playing=${kernel { it.isPlaying }}",
                SystemClock.elapsedRealtime() < deadline)
            compose.mainClock.advanceTimeByFrame()
            SystemClock.sleep(50)
        }
    }
    private fun pauseWithProductGesture() {
        compose.onNodeWithTag("review_gesture_surface").performTouchInput { click(Offset(width * .35f, height * .4f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        awaitKernel(5_000, "产品暂停手势未暂停真实内核") { !kernel { it.isPlaying } }
    }
    private fun assertResumesSameSource(original: IjkExo2MediaPlayer, paused: Long) {
        awaitKernel(10_000, "恢复前台未继续原视频") { kernel { it.isPlaying } && position() > paused + 500 }
        Assert.assertSame("恢复前台不得重新 setup 或换内核", original, player())
        Assert.assertTrue("恢复前台不得回零或跳到别的位置", position() in paused..paused + 5_000)
        Assert.assertEquals(0, (vm.state.value as V2ReviewUiState.Ready).absoluteCurrentIndex)
        Assert.assertFalse("恢复前台不得启动 P1 视频", fixture!!.videos.any { it.mediaId == "second" })
    }

    @Test fun playingReviewStopsInBackgroundAndResumesSamePositionAndKernel() {
        launch()
        seekThirtySeconds()
        val original = player()
        val before = position()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // moveToState returns only after Activity STOP. Test the kernel before any UI wait.
        Assert.assertFalse("Review 退出前台必须立即停止声音", kernel { it.isPlaying })
        val paused = assertSilentFor()
        Assert.assertTrue(abs(paused - before) < 2_500)
        Assert.assertSame(original, player())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertResumesSameSource(original, paused)
    }

    @Test fun explicitlyPausedReviewStaysPausedAcrossBackgroundAndForeground() {
        launch()
        seekThirtySeconds()
        pauseWithProductGesture()
        val original = player()
        val paused = position()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertSilentFor()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertSilentFor()
        Assert.assertSame("用户暂停后前后台不得重新 setup", original, player())
        Assert.assertTrue(abs(position() - paused) < 750)
        compose.onNodeWithContentDescription("播放").assertExists()
    }

    @Test fun preparingReviewCannotStartSoundInBackgroundThenResumesOnForeground() {
        launch(blockFirst = true)
        val original = player()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        fixture!!.releaseFirstVideo()
        // Inspect continuously while the real delayed HTTP source prepares: waiting solely for
        // duration could miss sound that starts briefly during preparation.
        awaitKernel(60_000, "后台真实视频未准备完成", pumpFrames = false) {
            Assert.assertFalse("首帧在后台完成不得启动声音", kernel { it.isPlaying })
            kernel { it.duration >= 59_000 }
        }
        val paused = assertSilentFor()
        Assert.assertSame(original, player())
        Assert.assertEquals("未稳定播放不得预解析 P1", 1L, fixture!!.secondPlaybackRequested.count)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertResumesSameSource(original, paused)
        awaitKernel(5_000, "前台稳定播放后应释放 P1 地址预解析") {
            fixture!!.secondPlaybackRequested.count == 0L
        }
    }
}
