package com.mediareview.app.feature.v2.review

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.player.*
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerScreen
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

@RunWith(AndroidJUnit4::class)
class Stage8IReviewToFullLoopBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private lateinit var live: RealHttpPlaybackFixture
    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        if (::live.isInitialized) live.close()
    }
    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var value: T? = null
        compose.runOnUiThread { value = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    @Test fun physicalReviewToFullTransitionUsesNonLoopingKernelAndNaturalCompletedUi() {
        val clock = ReviewTestClock(compose.mainClock)
        live = RealHttpPlaybackFixture(shortVideo = true)
        val review = V2ReviewViewModel(live.repository, live.sessions)
        val full = V2NativePlayerViewModel(live.repository)
        val inReview = mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.viewModelStore.put("review-boundary", review)
            compose.activity.viewModelStore.put("full-boundary", full)
        }
        compose.setContent {
            MediaReviewTheme {
                if (inReview.value) V2ReviewScreen(review, { id ->
                    full.open(PlaybackContext(listOf(PlaybackQueueItem(id, "短视频完整播放"))))
                    inReview.value = false
                }, {}) else {
                    val state by full.state.collectAsState()
                    GsyNativePlayerScreen(state, full::moveTo, full::onPlaybackFailed, full::retry,
                        full::reportProgress, {}, onPlaybackStable = full::onPlaybackStable,
                        onReportLoadedProgress = full::reportLoadedProgress)
                }
            }
        }
        clock.until(60_000) { kernel { it.isPlaying && it.currentPosition > 600 && (it.player?.mediaPlayer as? IjkExo2MediaPlayer)?.isLooping == true } }
        val old = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        compose.onNodeWithContentDescription("更多").performTouchInput { click(center) }
        clock.until(5000) { compose.onAllNodesWithText("打开完整播放器").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("打开完整播放器").performTouchInput { click(center) }
        clock.until(60_000) { !inReview.value && kernel { it.isPlaying && it.currentPosition > 300 && it.duration in 5000..8000 && it.player.mediaPlayer !== old } }
        val current = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        val probe = kernel { KernelPlaybackProbe(current) }
        Assert.assertFalse("Full不能继承Review loop", kernel { current.isLooping })
        Assert.assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, kernel { probe.repeatMode })
        Assert.assertFalse("Review旧内核不得串音", kernel { old.isPlaying })
        Assert.assertTrue(kernel { old.dataSource.isNullOrEmpty() })
        val duration = kernel { it.duration }
        val started = SystemClock.elapsedRealtime()
        clock.until(15_000) { kernel { (it.listener() as? GSYVideoView)?.currentState == GSYVideoView.CURRENT_STATE_AUTO_COMPLETE && !probe.isPlaying } }
        clock.until(15_000) {
            Assert.assertSame(current, kernel { it.player.mediaPlayer })
            Assert.assertEquals(GSYVideoView.CURRENT_STATE_AUTO_COMPLETE, kernel { (it.listener() as GSYVideoView).currentState })
            SystemClock.elapsedRealtime() - started > duration * 16 / 10
        }
        compose.onNodeWithContentDescription("重新播放").assertIsDisplayed()
        Assert.assertEquals("first", full.state.value.currentMediaId)
        Assert.assertTrue(live.videos.all { it.mediaId == "first" && it.authenticated })
        android.util.Log.i("MRTask7Evidence", "review_to_full_pointer=true full_actual_repeat_OFF=true natural_completed=true real_elapsed=${SystemClock.elapsedRealtime() - started} duration=$duration sameCompletedKernel=true oldAudio=false")
    }
}
