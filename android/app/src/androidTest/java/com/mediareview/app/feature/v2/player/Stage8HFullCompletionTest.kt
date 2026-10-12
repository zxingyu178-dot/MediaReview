package com.mediareview.app.feature.v2.player

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import kotlin.math.abs

/** 真实HTTP续播、自然完成、重播与显式下一条的内核验收。 */
@RunWith(AndroidJUnit4::class)
class Stage8HFullCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null
    private lateinit var vm: V2NativePlayerViewModel
    private var wallEpoch = 0L
    private var composeEpoch = 0L

    // 与FullLifecycle一致：只补给真实流逝的Compose时间，不能让delay跑快于内核。
    private fun waitWallClock(timeoutMillis: Long, condition: () -> Boolean) {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val due = composeEpoch + now - wallEpoch
            val remaining = due - compose.mainClock.currentTime
            if (remaining > 0L) compose.mainClock.advanceTimeBy(remaining, ignoreFrameDuration = true)
            if (condition()) return
            Assert.assertTrue("真实等待超时: ${timeoutMillis}ms", now - started <= timeoutMillis)
            SystemClock.sleep(10)
        }
    }
    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun position() = kernel { it.currentPosition }
    private fun host() = kernel { it.listener() as? GSYVideoView }
    private fun completed() = kernel {
        !it.isPlaying && (it.listener() as? GSYVideoView)?.currentState == GSYVideoView.CURRENT_STATE_AUTO_COMPLETE
    }
    private fun showControls() {
        if (compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player_gesture_surface").performTouchInput { click(center) }
        }
        waitWallClock(5_000) { compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun launch() {
        compose.mainClock.autoAdvance = false
        wallEpoch = SystemClock.elapsedRealtime()
        composeEpoch = compose.mainClock.currentTime
        val live = RealHttpPlaybackFixture(longVideo = true, resumeMs = 25_000L).also { fixture = it }
        vm = V2NativePlayerViewModel(live.repository)
        compose.runOnUiThread {
            compose.activity.viewModelStore.put("stage8h-full-completion", vm)
            vm.open(PlaybackContext(listOf(PlaybackQueueItem("first", "完成测试"), PlaybackQueueItem("second", "下一条测试"))))
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            MediaReviewTheme {
                GsyNativePlayerScreen(state, vm::moveTo, vm::onPlaybackFailed, vm::retry,
                    vm::reportProgress, {}, onPlaybackStable = vm::onPlaybackStable,
                    onReportLoadedProgress = vm::reportLoadedProgress)
            }
        }
        // 不直接seek、不篡改VM state：/playback下发的resume_position_ms必须使真实内核到25s。
        waitWallClock(60_000) {
            kernel { it.isPlaying && it.duration >= 59_000 && abs(it.currentPosition - 25_000) < 2_500 }
        }
        Assert.assertEquals("first", vm.state.value.currentMediaId)
        Assert.assertEquals(25_000L, (vm.state.value.ui as V2PlaybackUiState.Ready).source.resumePositionMs)
        Assert.assertTrue(live.videos.any { it.mediaId == "first" && it.authenticated })
        Assert.assertTrue(live.videos.all { it.mediaId == "first" && it.authenticated })
        val before = position()
        waitWallClock(5_000) { position() > before + 400 }
    }
    private fun dragToEndAndWaitCompleted(expectedHost: GSYVideoView, expectedPlayer: IjkExo2MediaPlayer) {
        showControls()
        val currentFraction = position().toFloat() / kernel { it.duration }.toFloat()
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * currentFraction.coerceIn(.01f, .98f), centerY))
            moveTo(Offset(width * .7f, centerY))
            moveTo(Offset(width * .999f, centerY))
            up()
        }
        // 允许最后几十毫秒已立即EOS；否则必须先读到真实尾部position。
        waitWallClock(10_000) { position() >= 57_000 || completed() }
        waitWallClock(10_000) { completed() }
        Assert.assertSame("完成前不能换host", expectedHost, host())
        Assert.assertSame("seek/完成不能启动另一个内核", expectedPlayer, kernel { it.player.mediaPlayer })
        Assert.assertEquals("first", vm.state.value.currentMediaId)
        Assert.assertFalse(fixture!!.videos.any { it.mediaId == "second" })
        Assert.assertTrue(fixture!!.videos.all { it.authenticated })
        // Completed position可能由GSY归零，不能把零误判为重播；真实AUTO_COMPLETE才是完成事实。
        val started = SystemClock.elapsedRealtime()
        waitWallClock(3_000) {
            Assert.assertTrue("完成后须保留当前媒体", completed())
            Assert.assertEquals("first", vm.state.value.currentMediaId)
            Assert.assertSame(expectedHost, host())
            Assert.assertSame(expectedPlayer, kernel { it.player.mediaPlayer })
            Assert.assertFalse("P1只许地址解析，不能视频请求/自动下一条", fixture!!.videos.any { it.mediaId == "second" })
            SystemClock.elapsedRealtime() - started >= 1_500
        }
    }

    @Test fun resumeCompleteReplayAndExplicitNextUseRealKernelAndAuthenticatedHttp() {
        launch()
        val firstHost = host() ?: error("真实GSY host未装载")
        val firstPlayer = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        val firstUrl = kernel { firstPlayer.dataSource }
        Assert.assertNotNull(firstUrl)
        Assert.assertTrue(fixture!!.videos.any { it.mediaId == "first" && it.url == firstUrl })
        dragToEndAndWaitCompleted(firstHost, firstPlayer)
        waitWallClock(5_000) { compose.onAllNodesWithContentDescription("重新播放").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("重新播放").performTouchInput { click(center) }
        // 产品重播可由GSY重建Exo实例，但必须同host/同源从near0继续，不得重复续播25s。
        waitWallClock(60_000) { kernel { it.isPlaying && it.currentPosition in 1L..2_500L } }
        Assert.assertEquals("first", vm.state.value.currentMediaId)
        Assert.assertSame(firstHost, host())
        val replayPlayer = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        Assert.assertEquals(firstUrl, kernel { replayPlayer.dataSource })
        Assert.assertFalse(fixture!!.videos.any { it.mediaId == "second" })
        val restartPosition = position()
        waitWallClock(5_000) { position() > restartPosition + 400 }
        Assert.assertSame(replayPlayer, kernel { it.player.mediaPlayer })
        dragToEndAndWaitCompleted(firstHost, replayPlayer)
        waitWallClock(5_000) { compose.onAllNodesWithText("播放下一条").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("播放下一条").performTouchInput { click(center) }
        waitWallClock(60_000) {
            vm.state.value.currentMediaId == "second" && fixture!!.videos.any { it.mediaId == "second" && it.authenticated } &&
                kernel { it.isPlaying && it.duration >= 59_000 && it.currentPosition > 500 }
        }
        Assert.assertSame("显式下一条仍复用唯一Screen host", firstHost, host())
        val secondPlayer = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        Assert.assertNotSame("切源必须释放旧内核", replayPlayer, secondPlayer)
        Assert.assertFalse(kernel { replayPlayer.isPlaying })
        Assert.assertNull("旧源释放身份", kernel { replayPlayer.dataSource })
        val secondUrl = kernel { secondPlayer.dataSource }
        Assert.assertNotEquals(firstUrl, secondUrl)
        Assert.assertTrue(fixture!!.videos.any { it.mediaId == "second" && it.authenticated && it.url == secondUrl })
        Assert.assertTrue(fixture!!.videos.all { it.authenticated })
        val secondPosition = position()
        waitWallClock(5_000) { position() > secondPosition + 400 }
        Assert.assertSame(secondPlayer, kernel { it.player.mediaPlayer })
        Assert.assertSame(firstHost, host())
    }

    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        fixture?.close()
    }
}
