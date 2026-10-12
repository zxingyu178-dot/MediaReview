package com.mediareview.app.feature.v2.player

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
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import org.junit.*
import org.junit.runner.RunWith
import kotlin.math.abs

/** 真HTTP 60秒MP4，读取GSY/Exo内核；不以控件存在作为seek成功。 */
@RunWith(AndroidJUnit4::class)
class Stage8HFullGestureTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null

    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        fixture?.close()
    }

    private fun launch(blockSecondPlayback: Boolean = false): V2NativePlayerViewModel {
        val live = RealHttpPlaybackFixture(longVideo = true, blockSecondPlayback = blockSecondPlayback).also { fixture = it }
        val vm = V2NativePlayerViewModel(live.repository)
        compose.activity.viewModelStore.put("stage8h-full", vm)
        val queue = listOf(PlaybackQueueItem("first", "真实手势测试")) +
            if (blockSecondPlayback) listOf(PlaybackQueueItem("second", "切源测试")) else emptyList()
        compose.runOnUiThread { vm.open(PlaybackContext(queue)) }
        compose.setContent {
            val state by vm.state.collectAsState()
            MediaReviewTheme {
                GsyNativePlayerScreen(state, vm::moveTo, vm::onPlaybackFailed, vm::retry, vm::reportProgress, {},
                    onPlaybackStable = { if (!blockSecondPlayback) vm.onPlaybackStable(it) },
                    onReportLoadedProgress = vm::reportLoadedProgress)
            }
        }
        compose.waitUntil(60_000) { kernel { it.isPlaying && it.duration >= 59_000 && it.currentPosition > 500 } }
        val first = position()
        compose.waitUntil(10_000) { position() > first + 300 }
        Assert.assertTrue(live.videos.isNotEmpty() && live.videos.all { it.authenticated })
        return vm
    }

    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun position() = kernel { it.currentPosition }
    private fun speed() = kernel { (it.player.mediaPlayer as IjkExo2MediaPlayer).speed }
    private fun hostState() = kernel {
        (it.listener() as? com.shuyu.gsyvideoplayer.video.base.GSYVideoView)?.currentState
    }
    private fun body() = compose.onNodeWithTag("player_gesture_surface")
    private fun showControls() {
        if (compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isEmpty()) {
            body().performTouchInput { click(center) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun near(target: Long, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) { abs(position() - target) < 2_500 }
        Assert.assertTrue("内核position=${position()} target=$target", abs(position() - target) < 2_500)
    }

    @Test fun singleTapShowsHiddenControlsWithoutPausingAndThenHidesThem() {
        launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isEmpty() }
        body().performTouchInput { click(center) }
        // 等待双击判定期结束，不能把down造成的一瞬显示误判为单击成功。
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onNodeWithTag("player_seek_bar").assertExists()
        Assert.assertTrue("Full Player单击不得暂停内核", kernel { it.isPlaying })
        body().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.onNodeWithTag("player_seek_bar").assertDoesNotExist()
        Assert.assertTrue(kernel { it.isPlaying })
    }

    @Test fun realPointerDrag20To70ReadsKernelPosition() {
        launch()
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput { click(Offset(width * .2f, centerY)) }
        near(12_000)
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * .2f, centerY))
            moveTo(Offset(width * .45f, centerY))
            moveTo(Offset(width * .7f, centerY))
            up()
        }
        near(42_000)
        Assert.assertTrue(kernel { it.isPlaying })
    }

    @Test fun doubleTapSidesSeekPlusAndMinusTenSeconds() {
        launch()
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput { click(Offset(width * .5f, centerY)) }
        near(30_000)
        val start = position()
        body().performTouchInput {
            val point = Offset(width * .8f, height * .45f)
            click(point); advanceEventTime(100); click(point)
        }
        near(start + 10_000, timeoutMs = 2_000)
        val forward = position()
        body().performTouchInput {
            val point = Offset(width * .2f, height * .45f)
            click(point); advanceEventTime(100); click(point)
        }
        near(forward - 10_000, timeoutMs = 2_000)
    }

    @Test fun bodyHorizontalDragCommitsSeekAndResumesWithoutReloadingSource() {
        launch()
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput { click(Offset(width * .2f, centerY)) }
        near(12_000)
        val before = position()
        body().performTouchInput {
            down(Offset(width * .2f, height * .45f))
            moveTo(Offset(width * .35f, height * .45f))
            moveTo(Offset(width * .7f, height * .45f))
            up()
        }
        near(before + 30_000)
        Assert.assertTrue("横滑seek后应恢复原播放态", kernel { it.isPlaying })
    }

    @Test fun pausedSeekStaysPausedAtCommittedPosition() {
        launch()
        showControls()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("暂停播放").performClick()
        compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        compose.onNodeWithTag("player_seek_bar").performTouchInput { click(Offset(width * .5f, centerY)) }
        near(30_000)
        Assert.assertFalse("暂停态seek不得自动播放", kernel { it.isPlaying })
        // 位置先变化，seek缓冲仍可能未结束；等真实host回到Paused再测试继续播放。
        compose.waitUntil(5_000) { hostState() == com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PAUSE }
        android.util.Log.i("Stage8HKernel", "pausedSeek settled pos=${position()} host=${hostState()}")
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("播放").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("播放").performClick()
        android.util.Log.i("Stage8HKernel", "pausedSeek playClick pos=${position()} host=${hostState()} playing=${kernel { it.isPlaying }}")
        compose.waitUntil(5_000) { kernel { it.isPlaying } }
        near(30_000)
    }

    @Test fun pausedBarSeekThenImmediateBodyDragUsesActualKernelAnchor() {
        launch()
        showControls()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("暂停播放").performClick()
        compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            click(Offset(width * .5f, centerY))
        }
        near(30_000, timeoutMs = 2_000)
        val anchor = position()
        val originalPlayer = kernel { it.player.mediaPlayer }
        body().performTouchInput {
            down(Offset(width * .2f, height * .45f))
            moveTo(Offset(width * .3f, height * .45f))
            up()
        }
        near(anchor + 6_000, timeoutMs = 2_000)
        Assert.assertFalse("暂停横滑不得恢复声音", kernel { it.isPlaying })
        Assert.assertSame("横滑不得重新装载播放器", originalPlayer, kernel { it.player.mediaPlayer })
        Assert.assertTrue(fixture!!.videos.all { it.authenticated && it.mediaId == "first" })
    }

    @Test fun cancellingScrubRestoresKernelPlaybackWithoutSeeking() {
        launch()
        showControls()
        val before = position()
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * .2f, centerY))
            moveTo(Offset(width * .7f, centerY))
            cancel()
        }
        compose.waitUntil(5_000) { kernel { it.isPlaying } }
        Assert.assertTrue("取消不得跳到预览位点: before=$before actual=${position()}", position() in before..before + 3_000)
    }

    @Test fun lockingDuringScrubRestoresOriginalPlayingState() {
        launch()
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * .2f, centerY))
            moveTo(Offset(width * .7f, centerY))
        }
        compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        // 第一个pointer仍拖进度，第二个pointer真实点击锁控。
        compose.onNodeWithContentDescription("锁定").performTouchInput { down(1, center); up(1) }
        compose.waitUntil(5_000) { kernel { it.isPlaying } }
        compose.onNodeWithContentDescription("解锁").assertExists()
        Assert.assertTrue("锁控取消拖动后应恢复同源原播放态", kernel { it.isPlaying })
    }

    @Test fun switchingSourceDuringScrubNeverResumesOldVideoWhileNewEndpointIsBlocked() {
        val vm = launch(blockSecondPlayback = true)
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * .2f, centerY))
            moveTo(Offset(width * .7f, centerY))
        }
        compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        compose.runOnUiThread { vm.moveTo(1) }
        compose.waitUntil(5_000) { fixture!!.secondPlaybackRequested.count == 0L }
        compose.onNodeWithTag("player_seek_bar").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        Assert.assertFalse("新endpoint阻塞期间旧视频不得被up/cancel恢复", kernel { it.isPlaying })
        Assert.assertFalse(fixture!!.videos.any { it.mediaId == "second" })
        fixture!!.releaseSecondPlayback()
        compose.waitUntil(60_000) {
            fixture!!.videos.any { it.mediaId == "second" && it.authenticated } &&
                kernel { it.isPlaying && it.currentPosition > 500 }
        }
    }

    @Test fun holdRestoreLockReleaseAndUnlockUseKernelSpeed() {
        launch()
        showControls()
        compose.onNodeWithText("1×").performClick()
        compose.onNodeWithText("1.5x").performClick()
        compose.waitUntil(5_000) { abs(speed() - 1.5f) < .01f }
        body().performTouchInput { down(center); advanceEventTime(400); moveTo(center) }
        compose.waitUntil(5_000) { abs(speed() - 2f) < .01f }
        body().performTouchInput { up() }
        compose.waitUntil(5_000) { abs(speed() - 1.5f) < .01f }
        val brightness = compose.activity.window.attributes.screenBrightness
        body().performTouchInput {
            down(center); advanceEventTime(400); moveTo(center)
            advanceEventTime(50); moveTo(center.copy(y = centerY - 100 * compose.activity.resources.displayMetrics.density))
        }
        compose.waitUntil(5_000) { abs(speed() - 2f) < .01f }
        body().performTouchInput { up() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2.0× 已锁定").fetchSemanticsNodes().isNotEmpty() }
        Assert.assertEquals(2f, speed(), .01f)
        Assert.assertEquals("锁倍速不得改变亮度", brightness, compose.activity.window.attributes.screenBrightness, .001f)
        body().performTouchInput { down(center); advanceEventTime(400); moveTo(center) }
        compose.waitUntil(5_000) { abs(speed() - 1.5f) < .01f }
        body().performTouchInput { up() }
        Assert.assertEquals(1.5f, speed(), .01f)
    }
    @Test fun leftVerticalGestureChangesBrightnessOnlyWithoutSeeking() {
        launch()
        val audio = compose.activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val original = compose.activity.window.attributes.screenBrightness
        val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        compose.runOnUiThread {
            compose.activity.window.attributes = compose.activity.window.attributes.apply { screenBrightness = .4f }
        }
        val before = position()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            body().performTouchInput {
                down(Offset(width * .25f, height * .7f))
                moveTo(Offset(width * .25f, height * .3f))
            }
            val brightness = compose.activity.window.attributes.screenBrightness
            Assert.assertTrue("左侧上滑应提高窗口亮度", brightness > .65f && brightness <= 1f)
            Assert.assertEquals("亮度手势不能改变音量", volume, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
            val hint = compose.onNodeWithText("${kotlin.math.round(brightness * 100).toInt()}%").fetchSemanticsNode().boundsInRoot
            val bounds = body().fetchSemanticsNode().boundsInRoot
            Assert.assertTrue("亮度提示位于左侧", hint.center.x < bounds.center.x)
            body().performTouchInput { up() }
            Assert.assertTrue(kernel { it.isPlaying })
            val after = position()
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            Assert.assertTrue("竖滑不得Seek", after >= before - 500 && after <= before + elapsed + 2_500)
            compose.waitUntil(5_000) { position() > after + 300 }
        } finally {
            compose.runOnUiThread {
                compose.activity.window.attributes = compose.activity.window.attributes.apply { screenBrightness = original }
            }
        }
    }

    @Test fun rightVerticalGestureChangesMusicVolumeOnlyWithoutSeeking() {
        launch()
        val audio = compose.activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val original = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        Assert.assertTrue(max >= 2)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max / 2, 0)
        val initial = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val brightness = compose.activity.window.attributes.screenBrightness
        val before = position()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            body().performTouchInput {
                down(Offset(width * .75f, height * .7f))
                moveTo(Offset(width * .75f, height * .3f))
            }
            val changed = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            Assert.assertTrue("右侧上滑应提高媒体音量", changed > initial && changed <= max)
            Assert.assertEquals("音量手势不能改变亮度", brightness, compose.activity.window.attributes.screenBrightness, .001f)
            val hint = compose.onNodeWithText("${changed * 100 / max}%").fetchSemanticsNode().boundsInRoot
            val bounds = body().fetchSemanticsNode().boundsInRoot
            Assert.assertTrue("音量提示位于右侧", hint.center.x > bounds.center.x)
            body().performTouchInput { up() }
            Assert.assertTrue(kernel { it.isPlaying })
            val after = position()
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            Assert.assertTrue("竖滑不得Seek", after >= before - 500 && after <= before + elapsed + 2_500)
            compose.waitUntil(5_000) { position() > after + 300 }
        } finally { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, original, 0) }
    }

}
