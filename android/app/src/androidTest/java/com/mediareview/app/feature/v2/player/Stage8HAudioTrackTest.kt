package com.mediareview.app.feature.v2.player

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerScreen
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.feature.v2.player.native.tracks.*
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import kotlin.math.abs

/** 真实双AAC HTTP素材：必须真实 selected 变化、同一内核、位置继续前进。 */
@RunWith(AndroidJUnit4::class)
class Stage8HAudioTrackTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: Stage8HTrackSourceFixture? = null
    private lateinit var adapter: GsyAudioTrackAdapter
    private val sourceKey = "dual-audio"

    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos() }
        fixture?.close()
    }

    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var value: T? = null
        compose.runOnUiThread { value = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun menu(): AudioTrackSnapshot? = kernel { adapter.read(sourceKey) }
    private fun position() = kernel { it.currentPosition }

    private fun launch() {
        val live = Stage8HTrackSourceFixture().also { fixture = it }
        adapter = GsyAudioTrackAdapter { GSYVideoManager.instance().player?.mediaPlayer as? IjkExo2MediaPlayer }
        val source = V2PlaybackSource("dual", "真实双音轨", live.mp4Endpoint, durationMs = 60_000)
        val state = V2PlayerState(PlaybackContext(listOf(PlaybackQueueItem("dual", source.title))),
            V2PlaybackUiState.Ready(source, V2PlaybackStage.DIRECT, source.direct))
        compose.setContent {
            MediaReviewTheme { GsyNativePlayerScreen(state, {}, {}, {}, { _, _ -> }, {}) }
        }
        compose.waitUntil(60_000) { kernel { it.isPlaying && it.duration >= 59_000 } && menu()?.menu?.canSelect == true }
        kernel { it.seekTo(15_000) }
        compose.waitUntil(10_000) { abs(position() - 15_000) < 2_000 }
    }

    private fun showControls() {
        if (compose.onAllNodesWithTag("player_top_bar").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player_gesture_surface").performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(700)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player_top_bar").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun chooseAlternateAudioViaProductMenu(): AudioTrackKey {
        val alternate = menu()!!.menu.choices.first { !it.selected }
        showControls()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("音轨").performClick()
        compose.onNodeWithTag("audio_track_${alternate.key.groupIndex}_${alternate.key.trackIndex}").performClick()
        compose.waitUntil(10_000) { menu()?.menu?.choices?.any { it.key == alternate.key && it.selected } == true }
        return alternate.key
    }

    @Test fun realAudioSelectionChangesSelectedTrackWithoutRecreatingOrRewindingKernel() {
        launch()
        val before = menu()!!
        val positionBefore = position()
        chooseAlternateAudioViaProductMenu()
        Assert.assertSame(before.player, menu()!!.player)
        Assert.assertTrue("选择音轨不能从零重播", position() >= positionBefore - 1_500)
        val after = position()
        compose.waitUntil(10_000) { position() > after + 500 }
        Assert.assertTrue(kernel { it.isPlaying })
        // root fixture records authenticated booleans only; no credential values in assertion output.
        Assert.assertTrue(fixture!!.hits.isNotEmpty() && fixture!!.hits.all { it.authenticated })
    }

    @Test fun selectingAudioWhilePausedKeepsCommittedPositionAndPausedState() {
        launch()
        showControls()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("暂停播放").performClick()
        compose.waitUntil(5_000) { !kernel { it.isPlaying } }
        val before = position()
        chooseAlternateAudioViaProductMenu()
        Assert.assertFalse(kernel { it.isPlaying })
        Assert.assertTrue("暂停时选音轨不得seek或重播", abs(position() - before) < 1_000)
    }
}
