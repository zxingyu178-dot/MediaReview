package com.mediareview.app.feature.v2.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.player.RealHttpPlaybackFixture
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import org.junit.*
import org.junit.runner.RunWith
import kotlin.math.abs

/** 真实 HTTP 60 秒 MP4；每项断言同时读取 GSY 内核、生产 Review Session 状态与实际流请求。 */
@RunWith(AndroidJUnit4::class)
class Stage8HReviewGestureTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null
    private lateinit var vm: V2ReviewViewModel

    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        fixture?.close()
    }

    private fun launch(blockSecond: Boolean = false, blockFirstVideo: Boolean = false, awaitPlayback: Boolean = true) {
        val live = RealHttpPlaybackFixture(longVideo = true, blockSecondPlayback = blockSecond,
            blockFirstVideo = blockFirstVideo).also { fixture = it }
        vm = V2ReviewViewModel(live.repository, live.sessions)
        compose.activity.viewModelStore.put("stage8h-review", vm)
        compose.setContent { MediaReviewTheme { V2ReviewScreen(vm, {}, {}) } }
        if (!awaitPlayback) return
        compose.waitUntil(60_000) { kernel { it.isPlaying && it.duration >= 59_000 && it.currentPosition > 500 } }
        val first = position()
        compose.waitUntil(10_000) { position() > first + 300 }
        Assert.assertTrue(live.videos.any { it.mediaId == "first" && it.authenticated })
        Assert.assertFalse("P1 只能解析地址，不得启动下一 Player", live.videos.any { it.mediaId == "second" })
    }

    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun position() = kernel { it.currentPosition }
    private fun speed() = kernel { (it.player.mediaPlayer as IjkExo2MediaPlayer).speed }
    private fun page() = (vm.state.value as? V2ReviewUiState.Ready)?.absoluteCurrentIndex
    private fun body() = compose.onNodeWithTag("review_gesture_surface")
    private fun bar() = compose.onNodeWithTag("review_seek_bar")
    private fun near(target: Long) {
        // A no-op tap must not pass by naturally playing to a 10/12-second target.
        compose.waitUntil(2_000) { abs(position() - target) < 2_500 }
        Assert.assertTrue("内核 position=${position()} target=$target", abs(position() - target) < 2_500)
    }
    private fun tapBody() {
        body().performTouchInput { click(Offset(width * .35f, height * .4f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }

    @Test fun adaptiveHorizontalPreviewCommitsExactlyOnceWithoutFlippingPage() {
        launch()
        bar().performTouchInput { click(Offset(width * .2f, centerY)) }; near(12_000)
        tapBody(); compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        val anchor = position()
        val recorder = kernel { com.mediareview.app.feature.v2.player.KernelSeekRecorder(it.player.mediaPlayer as IjkExo2MediaPlayer) }
        try {
            body().performTouchInput {
                down(Offset(width * .2f, height * .4f))
                moveTo(Offset(width * .35f, height * .4f))
                moveTo(Offset(width * .7f, height * .4f))
            }
            Assert.assertEquals(0, recorder.count)
            Assert.assertTrue(abs(position() - anchor) < 1000)
            body().performTouchInput { up() }
            near(anchor + 15_000)
            compose.waitUntil(5_000) { recorder.count == 1 }
            Assert.assertEquals(0, page())
            Assert.assertFalse(kernel { it.isPlaying })
            compose.waitForIdle(); Assert.assertEquals(1, recorder.count)
        } finally { compose.runOnUiThread { recorder.close() } }
    }

    /** 提示仍可见时第一下真实触摸必须到达32dp进度条；不得等待提示消失或语义setProgress。 */
    @Test fun visibleReviewReminderAllowsFirstPhysicalSeekWithoutHidingMessage() {
        launch()
        val reminder = compose.onNodeWithText("还有 2 条未批阅内容，会话未完成")
        reminder.assertIsDisplayed()
        val baseline = position()
        Assert.assertTrue("首触摸前位置必须明显远离30秒，不能天然播放伪通过",
            abs(baseline - 30_000L) > 10_000L)
        val sourceBefore = kernel { (it.player.mediaPlayer as IjkExo2MediaPlayer).dataSource }
        bar().performTouchInput { click(Offset(width * .5f, centerY)) }
        // Tap must happen with the message on screen; no dismiss/delay workaround.
        reminder.assertIsDisplayed()
        near(30_000L)
        reminder.assertIsDisplayed()
        Assert.assertTrue("首次触摸seek后原视频应保持播放", kernel { it.isPlaying })
        Assert.assertEquals("首次触摸不得翻页", 0, page())
        Assert.assertTrue("首次触摸不得换源",
            kernel { (it.player.mediaPlayer as IjkExo2MediaPlayer).dataSource == sourceBefore })
        Assert.assertTrue("真实流请求必须认证", fixture!!.videos.all { it.authenticated })
        Assert.assertFalse("进度条不得启动P1 Player", fixture!!.videos.any { it.mediaId == "second" })
        // Physical More tap with the reminder still present detects moving the obstruction to the action rail.
        reminder.assertIsDisplayed()
        compose.onNodeWithContentDescription("更多").performTouchInput { click(center) }
        compose.onNodeWithText("打开完整播放器").assertIsDisplayed()
        Assert.assertFalse("打开更多应暂停当前真实内核", kernel { it.isPlaying })
    }
    @Test fun reviewTapPausesAndResumesWithoutReloading() {
        launch()
        compose.onNodeWithContentDescription("播放").assertDoesNotExist()
        bar().performTouchInput { click(Offset(width * .5f, centerY)) }
        near(30_000)
        tapBody()
        compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("播放").fetchSemanticsNodes().size == 1 }
        val paused = position()
        tapBody()
        compose.waitUntil(5_000) { kernel { it.isPlaying } }
        near(paused)
        Assert.assertEquals(0, page())
    }

    @Test fun reviewSharedBar20To70CommitsRealPositionAndPausedDragRemainsPaused() {
        launch()
        bar().performTouchInput { click(Offset(width * .2f, centerY)) }
        near(12_000)
        bar().performTouchInput { down(Offset(width * .2f, centerY)); moveTo(Offset(width * .7f, centerY)); up() }
        near(42_000)
        Assert.assertTrue(kernel { it.isPlaying })
        tapBody()
        compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        bar().performTouchInput { down(Offset(width * .7f, centerY)); moveTo(Offset(width * .5f, centerY)); up() }
        near(30_000)
        Assert.assertFalse(kernel { it.isPlaying })
        Assert.assertEquals(0, page())
    }

    @Test fun horizontalDirectionStaysHorizontalAfterLargeVerticalMovement() {
        launch()
        bar().performTouchInput { click(Offset(width * .2f, centerY)) }
        near(12_000)
        val baseline = position()
        body().performTouchInput {
            down(Offset(width * .2f, height * .55f))
            moveTo(Offset(width * .4f, height * .54f))
            moveTo(Offset(width * .7f, height * .2f))
            up()
        }
        near(baseline + 15_000)
        Assert.assertEquals("水平 owner 不得转为翻页", 0, page())
        Assert.assertFalse(fixture!!.videos.any { it.mediaId == "second" })
    }

    @Test fun verticalDirectionStaysVerticalAndChangesOnlyToAuthenticatedNeighbour() {
        launch()
        body().performTouchInput {
            down(Offset(width * .3f, height * .7f))
            moveTo(Offset(width * .31f, height * .5f))
            moveTo(Offset(width * .8f, height * .2f))
            up()
        }
        compose.waitUntil(60_000) { page() == 1 && fixture!!.videos.any { it.mediaId == "second" && it.authenticated } && kernel { it.isPlaying && it.currentPosition > 500 } }
        Assert.assertTrue("竖向不得 seek 第二项到水平预览位置", position() < 10_000)
        val baseline = position()
        compose.waitUntil(10_000) { position() > baseline + 300 }
        Assert.assertTrue(fixture!!.videos.all { it.authenticated })
    }

    @Test fun doubleTapBothSidesSeekTenSecondsWithoutPaging() {
        launch()
        bar().performTouchInput { click(Offset(width * .5f, centerY)) }
        near(30_000)
        val baseline = position()
        body().performTouchInput { val p = Offset(width * .8f, height * .4f); click(p); advanceEventTime(100); click(p) }
        near(baseline + 10_000)
        val forward = position()
        body().performTouchInput { val p = Offset(width * .2f, height * .4f); click(p); advanceEventTime(100); click(p) }
        near(forward - 10_000)
        Assert.assertEquals(0, page())
    }

    @Test fun formalSpeedHoldRestoreLockAndUnlockNeverPageOrSeek() {
        launch()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("1.5×").performClick()
        compose.waitUntil(5_000) { abs(speed() - 1.5f) < .01f && kernel { it.isPlaying } }
        body().performTouchInput { down(center); advanceEventTime(400); moveTo(center) }
        compose.waitUntil(5_000) { abs(speed() - 2f) < .01f }
        body().performTouchInput { up() }
        compose.waitUntil(5_000) { abs(speed() - 1.5f) < .01f }
        val baseline = position()
        body().performTouchInput {
            down(center); advanceEventTime(400); moveTo(center)
            advanceEventTime(50); moveTo(center.copy(y = centerY - 100 * compose.activity.resources.displayMetrics.density))
        }
        compose.waitUntil(5_000) { abs(speed() - 2f) < .01f }
        body().performTouchInput { up() }
        Assert.assertEquals(0, page())
        Assert.assertTrue("长按上滑不得 seek", position() in baseline..baseline + 5_000)
        Assert.assertEquals(2f, speed(), .01f)
        body().performTouchInput { down(center); advanceEventTime(400); moveTo(center) }
        compose.waitUntil(5_000) { abs(speed() - 1.5f) < .01f }
        body().performTouchInput { up() }
        Assert.assertEquals(0, page())
    }

    @Test fun formalSpeedSurvivesAuthenticatedNewSource() {
        launch()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("1.5×").performClick()
        compose.waitUntil(5_000) { abs(speed() - 1.5f) < .01f && kernel { it.isPlaying } }
        body().performTouchInput { swipeLeft() }
        compose.waitUntil(60_000) {
            page() == 1 && fixture!!.videos.any { it.mediaId == "second" && it.authenticated } &&
                kernel { it.isPlaying && (it.player.mediaPlayer as IjkExo2MediaPlayer).dataSource.endsWith("/video/second.mp4") }
        }
        compose.waitUntil(5_000) { abs(speed() - 1.5f) < .01f }
        val baseline = position()
        compose.waitUntil(10_000) { position() > baseline + 300 }
        Assert.assertEquals("正式倍速应在真实新视频持续播放时保留", 1.5f, speed(), .01f)
    }

    @Test fun verticalCancellationRestoresPausedSameSourceWithoutAutoplay() {
        launch()
        bar().performTouchInput { click(Offset(width * .5f, centerY)) }
        near(30_000)
        tapBody()
        compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        val baseline = position()
        body().performTouchInput { down(Offset(width * .3f, height * .6f)); moveTo(Offset(width * .3f, height * .45f)); cancel() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        Assert.assertEquals(0, page())
        Assert.assertFalse("竖向取消不得把原暂停视频自动播放", kernel { it.isPlaying })
        near(baseline)
    }

    @Test fun blockedNextEndpointStopsOldSourceUntilAuthenticatedNewVideoAdvances() {
        launch(blockSecond = true)
        body().performTouchInput { swipe(Offset(width * .35f, height * .8f), Offset(width * .35f, height * .2f), durationMillis = 140) }
        // P1 can request this endpoint before the gesture; its latch alone is not page settlement.
        compose.waitUntil(5_000) { page() == 1 && vm.playback.value is com.mediareview.app.feature.v2.player.V2PlaybackUiState.Loading && !kernel { it.isPlaying } && fixture!!.secondPlaybackRequested.count == 0L }
        Assert.assertEquals(0L, fixture!!.secondPlaybackRequested.count)
        Assert.assertEquals(1, page())
        Assert.assertFalse("P0 Loading 时旧视频必须停止", kernel { it.isPlaying })
        Assert.assertFalse(fixture!!.videos.any { it.mediaId == "second" })
        fixture!!.releaseSecondPlayback()
        compose.waitUntil(60_000) { fixture!!.videos.any { it.mediaId == "second" && it.authenticated } && kernel { it.isPlaying && it.currentPosition > 500 } }
        val baseline = position()
        compose.waitUntil(10_000) { position() > baseline + 300 }
    }

    @Test fun loadingNextEndpointStillAllowsHorizontalReturnToPreviousItem() {
        launch(blockSecond = true)
        body().performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { page() == 1 && vm.playback.value is com.mediareview.app.feature.v2.player.V2PlaybackUiState.Loading }
        Assert.assertFalse(kernel { it.isPlaying })
        body().performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { page() == 0 }
        fixture!!.releaseSecondPlayback()
        compose.waitUntil(60_000) { kernel { it.isPlaying && it.currentPosition > 500 } }
        Assert.assertFalse("跳过Loading项不得启动其流", fixture!!.videos.any { it.mediaId == "second" })
    }

    @Test fun failedNextEndpointStillAllowsHorizontalReturnToPreviousItem() {
        launch(blockSecond = true)
        body().performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { page() == 1 && vm.playback.value is com.mediareview.app.feature.v2.player.V2PlaybackUiState.Loading && fixture!!.secondPlaybackRequested.count == 0L }
        // Deliver an actual endpoint HTTP failure, not a 20s socket stall that marks Server Offline.
        fixture!!.failSecondPlayback()
        compose.waitUntil(5_000) { page() == 1 && vm.playback.value is com.mediareview.app.feature.v2.player.V2PlaybackUiState.Failed }
        Assert.assertFalse(kernel { it.isPlaying })
        body().performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { page() == 0 }
        compose.waitUntil(60_000) { kernel { it.isPlaying && it.currentPosition > 500 } }
        Assert.assertFalse(fixture!!.videos.any { it.mediaId == "second" })
    }

    @Test fun pauseBeforeFirstFrameSurvivesPreparationAndDoesNotReleaseP1() {
        launch(blockSecond = true, blockFirstVideo = true, awaitPlayback = false)
        compose.waitUntil(10_000) { fixture!!.firstVideoRequested.count == 0L }
        tapBody()
        Assert.assertEquals("首帧前不得预解析P1", 1L, fixture!!.secondPlaybackRequested.count)
        fixture!!.releaseFirstVideo()
        // Wait for a prepared real duration, then allow delayed prepared/autoplay callbacks to settle.
        compose.waitUntil(60_000) { kernel { it.duration >= 59_000 } }
        val settleUntil = android.os.SystemClock.elapsedRealtime() + 11_000
        while (android.os.SystemClock.elapsedRealtime() < settleUntil) {
            Assert.assertFalse("Preparing期间单击暂停必须保留", kernel { it.isPlaying })
            android.os.SystemClock.sleep(100)
        }
        compose.waitForIdle()
        Assert.assertFalse("Preparing期间单击暂停必须保留", kernel { it.isPlaying })
        Assert.assertEquals("未稳定播放不得预解析P1", 1L, fixture!!.secondPlaybackRequested.count)
        val paused = position()
        tapBody()
        compose.waitUntil(10_000) { kernel { it.isPlaying } && position() > paused + 500 }
        compose.waitUntil(5_000) { fixture!!.secondPlaybackRequested.count == 0L }
    }
    @Test fun preparingMoreSheetPausesUntilSystemBackClosesIt() {
        launch(blockSecond = true, blockFirstVideo = true, awaitPlayback = false)
        compose.waitUntil(10_000) { fixture!!.firstVideoRequested.count == 0L }
        compose.onNodeWithContentDescription("更多").performTouchInput { click(center) }
        compose.onNodeWithText("打开完整播放器").assertExists()
        fixture!!.releaseFirstVideo()
        compose.waitUntil(60_000) { kernel { it.duration >= 59_000 } }
        val until = android.os.SystemClock.elapsedRealtime() + 1_500
        while (android.os.SystemClock.elapsedRealtime() < until) {
            Assert.assertFalse("更多菜单不得在准备完成后开始声音", kernel { it.isPlaying })
            android.os.SystemClock.sleep(100)
        }
        Assert.assertEquals(1L, fixture!!.secondPlaybackRequested.count)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("打开完整播放器").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { kernel { it.isPlaying } && position() > 500 }
        compose.waitUntil(5_000) { fixture!!.secondPlaybackRequested.count == 0L }
    }

}
