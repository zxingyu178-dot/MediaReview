package com.mediareview.app.feature.v2.review

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.player.RealHttpPlaybackFixture
import com.mediareview.app.feature.v2.player.Stage8HTrackSourceFixture
import com.mediareview.app.feature.v2.player.KernelPlaybackProbe
import com.mediareview.app.feature.v2.player.KernelSeekRecorder
import androidx.media3.common.Player
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/** 同一真实6秒内核的自然EOF/wrap与屏幕像素证据，绝不使用Compose capture代替Surface。 */
@RunWith(AndroidJUnit4::class)
class Stage8IReviewLoopTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private lateinit var live: RealHttpPlaybackFixture
    private lateinit var track: Stage8HTrackSourceFixture
    private lateinit var vm: V2ReviewViewModel
    private lateinit var clock: ReviewTestClock
    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        if (::live.isInitialized) live.close()
        if (::track.isInitialized) track.close()
    }
    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun launch(hls: Boolean): IjkExo2MediaPlayer {
        clock = ReviewTestClock(compose.mainClock)
        live = RealHttpPlaybackFixture(shortVideo = true)
        track = Stage8HTrackSourceFixture(shortVideo = true)
        val endpoint = if (hls) track.hlsEndpoint else track.mp4Endpoint
        val repository = if (!hls) live.repository else object : MediaRepository by live.repository {
            override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource =
                if (mediaId == "first") V2PlaybackSource("first", "六秒动态测试", endpoint, null, durationMs = 6000)
                else live.repository.resolvePlayback(mediaId)
        }
        compose.runOnUiThread {
            vm = V2ReviewViewModel(repository, live.sessions)
            compose.activity.viewModelStore.put("stage8i-loop", vm)
        }
        compose.setContent { MediaReviewTheme { V2ReviewScreen(vm, {}, {}) } }
        clock.until(60_000) { kernel { it.isPlaying && it.currentPosition > 600 && it.duration in 5000..8000 } }
        clock.until(10_000) { ReviewFrameEvidence.capture("loop-${if (hls) "hls" else "direct"}-initial").hasVideoColors }
        if (hls) {
            Assert.assertTrue(track.hits.any { it.path.endsWith("index.m3u8") && it.authenticated })
            Assert.assertTrue(track.hits.any { it.path.endsWith(".ts") && it.authenticated })
        } else Assert.assertTrue(live.videos.any { it.mediaId == "first" && it.authenticated })
        return kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
    }
    private fun verifyNaturalLoop(player: IjkExo2MediaPlayer, label: String) {
        val probe = kernel { KernelPlaybackProbe(player) }
        val source = kernel { player.dataSource }
        val started = SystemClock.elapsedRealtime()
        var previous = kernel { it.currentPosition }
        var wraps = 0
        var completed = false
        var afterWrap: ReviewFrameEvidence? = null
        var lastFrame: ReviewFrameEvidence? = null
        var changed = 0
        var nextCaptureAt = started
        // Natural playback for >1.5 actual durations, no test seek/restart.
        val duration = kernel { it.duration }
        while (SystemClock.elapsedRealtime() - started < duration * 17 / 10) {
            SystemClock.sleep(100)
            clock.sync()
            kernel {
                Assert.assertSame("不能通过第二内核冒充loop", player, it.player.mediaPlayer)
                Assert.assertEquals(source, player.dataSource)
                if (it.listener() is GSYVideoView && (it.listener() as GSYVideoView).currentState == GSYVideoView.CURRENT_STATE_AUTO_COMPLETE) completed = true
                val position = it.currentPosition
                if (previous > duration * 3 / 4 && position < duration / 3) wraps++
                previous = position
            }
            if (wraps > 0 && SystemClock.elapsedRealtime() >= nextCaptureAt) {
                nextCaptureAt = SystemClock.elapsedRealtime() + 300
                val frame = ReviewFrameEvidence.capture("loop-$label-post-wrap")
                if (frame.hasVideoColors) {
                    if (afterWrap == null) afterWrap = frame
                    else changed = maxOf(changed, frame.changedFrom(afterWrap!!))
                    lastFrame = frame
                }
            }
        }
        Assert.assertTrue("真实position必须自然wrap", wraps > 0)
        Assert.assertFalse("Review不能进入terminal Completed", completed)
        Assert.assertTrue("wrap后Surface必须有视频特有RGB帧", afterWrap?.hasVideoColors == true)
        clock.until(5000) { kernel { probe.isPlaying } && ReviewFrameEvidence.capture("loop-$label-later").hasVideoColors }
        val later = lastFrame!!
        Assert.assertTrue("视频必须真实动态而非poster/冻结帧 changed=$changed", changed > 10)
        Assert.assertTrue(kernel { it.isPlaying && it.player.mediaPlayer === player })
        Assert.assertEquals(0, (vm.state.value as V2ReviewUiState.Ready).absoluteCurrentIndex)
        Assert.assertTrue(track.hits.all { it.authenticated })
        Assert.assertTrue(live.videos.all { it.authenticated })
        Assert.assertFalse(live.videos.any { it.mediaId == "second" })
        android.util.Log.i("MRTask7Evidence", "loop label=$label real_elapsed=${SystemClock.elapsedRealtime() - started} duration=$duration wraps=$wraps rgb=${later.red}/${later.green}/${later.blue} changed=$changed sameKernel=true actualExoPlaying=${kernel { probe.isPlaying }} completed=$completed")
    }
    @Test fun directSixSecondNativeLoop() = verifyNaturalLoop(launch(false), "direct")
    @Test fun hlsSixSecondNativeLoop() = verifyNaturalLoop(launch(true), "hls")

    @Test fun actualEofThenPhysicalSeekRestartsSameKernel() {
        val player = launch(false)
        // Produce a real EOF race in the existing current kernel, not a fake UI state.
        val probe = kernel { KernelPlaybackProbe(player) }
        kernel { probe.repeatMode = Player.REPEAT_MODE_OFF; it.seekTo(it.duration - 200) }
        clock.until(15_000) { kernel { probe.state == Player.STATE_ENDED && (it.listener() as? GSYVideoView)?.currentState == GSYVideoView.CURRENT_STATE_AUTO_COMPLETE } }
        // Remove only the test-injected repeat fault. Do not start/rebind/seek the player here.
        kernel {
            probe.repeatMode = Player.REPEAT_MODE_ALL
            Assert.assertEquals(GSYVideoView.CURRENT_STATE_AUTO_COMPLETE, (it.listener() as GSYVideoView).currentState)
            Assert.assertEquals(Player.STATE_ENDED, probe.state)
            Assert.assertFalse(probe.isPlaying)
        }
        android.util.Log.i("MRTask7Evidence", "eof test_only_repeat_OFF_real_ENDED_then_ALL=true hostStillCompleted=true beforePhysicalSeek=true")
        val recorder = kernel { KernelSeekRecorder(player) }
        fun record(label: String) = kernel { manager ->
            android.util.Log.i("MRTask7Evidence", "eof_seek phase=$label seekCount=${recorder.count} targetMs=${recorder.lastTargetMs} actualExoState=${probe.state} playWhenReady=${probe.playWhenReady} actualExoPlaying=${probe.isPlaying} exoPositionMs=${probe.positionMs} exoDurationMs=${probe.durationMs} managerPositionMs=${manager.currentPosition} managerDurationMs=${manager.duration} hostState=${(manager.listener() as? GSYVideoView)?.currentState} sameKernel=${manager.player.mediaPlayer === player}")
        }
        try {
            record("before_click")
            compose.onNodeWithTag("review_seek_bar").performTouchInput { click(Offset(width * .25f, centerY)) }
            val clickedAt = SystemClock.elapsedRealtime()
            record("after_click")
            for (delayMs in listOf(100L, 350L, 800L)) {
                clock.until(5000) { SystemClock.elapsedRealtime() - clickedAt >= delayMs }
                record("after_${delayMs}ms")
            }
            Assert.assertEquals("物理seek必须恰好提交一次到真实内核", 1, recorder.count)
            Assert.assertTrue("物理25%seek目标必须约1500ms", recorder.lastTargetMs?.let { it in 1400L..1600L } == true)
            clock.until(5000) { kernel { it.player.mediaPlayer === player && probe.isPlaying && it.currentPosition in 1000..3500 } }
        } finally {
            record("final")
            kernel { recorder.close() }
        }
        Assert.assertSame(player, kernel { it.player.mediaPlayer })
        clock.until(5000) { ReviewFrameEvidence.capture("eof-seek").hasVideoColors }
        Assert.assertTrue("EOF恢复应重新启用真实内核loop", kernel { player.isLooping })
        verifyNaturalLoop(player, "eof-seek-recovered")
    }
}
