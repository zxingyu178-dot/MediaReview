package com.mediareview.app.feature.v2.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import org.junit.rules.TestName
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerScreen
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.mediareview.app.feature.v2.player.native.tracks.*
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import org.junit.*
import org.junit.runner.RunWith

/** 同ID/已attach宿主切换真实HTTP地址或MP4→HLS后，直接读取内核持续前进。 */
@RunWith(AndroidJUnit4::class)
class Stage8HSourceReplacementTest {
    @get:Rule val testName = TestName()
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private lateinit var fixture: Stage8HTrackSourceFixture
    private var state by mutableStateOf(V2PlayerState())
    private val errors = mutableListOf<String>()

    @Before fun setup() {
        val posterIdentityCase = testName.methodName == "sameMediaSameStageNewUrlRestoresActualPosterUntilNewPlaybackAdvances"
        fixture = Stage8HTrackSourceFixture(blockOriginal = posterIdentityCase, blockReplacement = posterIdentityCase)
        val source = V2PlaybackSource("same", "同视频切源", fixture.mp4Endpoint, fixture.hlsEndpoint, durationMs = 60_000)
        state = V2PlayerState(PlaybackContext(listOf(PlaybackQueueItem("same", "同视频切源", if (posterIdentityCase) fixture.coverUrl else null))),
            V2PlaybackUiState.Ready(source, V2PlaybackStage.DIRECT, source.direct))
        compose.setContent {
            MediaReviewTheme {
                GsyNativePlayerScreen(state, {}, { errors += it }, {}, { _, _ -> }, {})
            }
        }
        if (!posterIdentityCase) moving()
    }

    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos() }
        fixture.close()
    }

    private fun position(): Long {
        var value = 0L
        compose.runOnUiThread { value = GSYVideoManager.instance().currentPosition }
        return value
    }
    private fun moving() {
        compose.waitUntil(60_000) {
            var playing = false
            compose.runOnUiThread { playing = GSYVideoManager.instance().isPlaying }
            playing && position() > 500
        }
        val before = position()
        compose.waitUntil(10_000) { position() > before + 500 }
        Assert.assertTrue("已attach切源后内核必须继续前进", position() > before + 500)
        compose.runOnIdle { Assert.assertTrue("不得内核报错后假装成功", errors.isEmpty()) }
    }

    private fun adapter() = GsyAudioTrackAdapter {
        GSYVideoManager.instance().player?.mediaPlayer as? IjkExo2MediaPlayer
    }
    private fun trackSnapshot(): AudioTrackSnapshot {
        var snapshot: AudioTrackSnapshot? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread { snapshot = adapter().read("same-source") }
            snapshot?.menu?.canSelect == true
        }
        return snapshot!!
    }
    private fun assertOldTrackSelectionRejected(previous: AudioTrackSnapshot) {
        val current = trackSnapshot()
        val selectedBefore = current.menu.choices.filter { it.selected }.map { it.key }
        compose.runOnUiThread {
            Assert.assertFalse("即使app sourceKey相同，旧内核菜单不能选择新源", adapter().select(
                previous, previous.menu.choices.first().key, "same-source"))
            Assert.assertEquals(selectedBefore, adapter().read("same-source")!!.menu.choices.filter { it.selected }.map { it.key })
        }
    }

    private var posterPixelDiagnostic = "尚未截图"
    private fun greenPosterVisible(): Boolean = try {
        // Capture the actual composed window, so an existing transparent node cannot satisfy this.
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val pixel = pixels[pixels.width / 2, (pixels.height * 0.35f).toInt()]
        posterPixelDiagnostic = "pixel=$pixel size=${pixels.width}x${pixels.height}"
        pixel.green > 0.8f && pixel.red < 0.1f && pixel.blue < 0.1f
    } catch (error: Throwable) {
        posterPixelDiagnostic = "${error.javaClass.simpleName}: ${error.message}"
        false
    }

    @Test fun sameMediaSameStageNewUrlRestoresActualPosterUntilNewPlaybackAdvances() {
        compose.waitUntil(20_000) { fixture.originalRequested.count == 0L }
        compose.waitUntil(10_000) { greenPosterVisible() }
        compose.onNodeWithContentDescription("视频封面").assertIsDisplayed()
        Assert.assertTrue("原始源准备时必须真实显示绿色封面: $posterPixelDiagnostic", greenPosterVisible())
        fixture.releaseOriginal()
        moving()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("视频封面").fetchSemanticsNodes().isEmpty() }
        Assert.assertFalse("旧源真实前进后封面应消失", greenPosterVisible())
        lateinit var oldKernel: IjkExo2MediaPlayer
        compose.runOnUiThread { oldKernel = GSYVideoManager.instance().player!!.mediaPlayer as IjkExo2MediaPlayer }

        // Deliberately keep mediaId AND DIRECT stage unchanged; never pass through Loading.
        compose.runOnIdle {
            val ready = state.ui as V2PlaybackUiState.Ready
            val source = ready.source.copy(direct = fixture.replacementEndpoint)
            state = state.copy(ui = V2PlaybackUiState.Ready(source, V2PlaybackStage.DIRECT, source.direct))
        }
        compose.waitUntil(20_000) { fixture.replacementRequested.count == 0L }
        Assert.assertTrue("新 URL 必须已经真实发送认证请求", fixture.hits.any { it.path == "/replacement.mp4" && it.authenticated })
        // RED point: old playbackAdvanced must not keep the new identity's poster transparent.
        compose.waitUntil(10_000) { greenPosterVisible() }
        compose.onNodeWithContentDescription("视频封面").assertIsDisplayed()
        Assert.assertTrue("新源阻塞准备时必须恢复真实绿色封面: $posterPixelDiagnostic", greenPosterVisible())
        compose.runOnUiThread {
            val newKernel = GSYVideoManager.instance().player!!.mediaPlayer as IjkExo2MediaPlayer
            Assert.assertNotSame(oldKernel, newKernel)
            Assert.assertFalse("旧源必须已停止", oldKernel.isPlaying)
            Assert.assertNull("旧源必须已释放 URL", oldKernel.dataSource)
            Assert.assertFalse("阻塞的新源不能已经播放", newKernel.isPlaying)
        }
        fixture.releaseReplacement()
        moving()
        compose.runOnUiThread {
            val newKernel = GSYVideoManager.instance().player!!.mediaPlayer as IjkExo2MediaPlayer
            Assert.assertEquals(fixture.replacementEndpoint.url, newKernel.dataSource)
            Assert.assertNotSame(oldKernel, newKernel)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("视频封面").fetchSemanticsNodes().isEmpty() }
        Assert.assertFalse("新源实际前进后封面才消失", greenPosterVisible())
        Assert.assertTrue("封面请求不应污染媒体认证记录", fixture.hits.all { it.authenticated })
    }

    @Test fun sameMediaReadyUrlReplacementContinuesActualPlayback() {
        val previous = trackSnapshot()
        compose.runOnIdle {
            val ready = state.ui as V2PlaybackUiState.Ready
            val source = ready.source.copy(direct = fixture.replacementEndpoint)
            state = state.copy(ui = V2PlaybackUiState.Ready(source, V2PlaybackStage.DIRECT, source.direct))
        }
        compose.waitUntil(20_000) { fixture.hits.any { it.path == "/replacement.mp4" && it.authenticated } }
        moving()
        assertOldTrackSelectionRejected(previous)
        Assert.assertTrue(fixture.hits.all { it.authenticated })
    }

    @Test fun sameMediaDirectToRealHlsContinuesActualPlayback() {
        val previous = trackSnapshot()
        compose.runOnIdle {
            val ready = state.ui as V2PlaybackUiState.Ready
            state = state.copy(ui = ready.copy(stage = V2PlaybackStage.HLS_FALLBACK, endpoint = fixture.hlsEndpoint))
        }
        compose.waitUntil(30_000) {
            fixture.hits.any { it.path == "/hls/index.m3u8" && it.authenticated } &&
                fixture.hits.any { it.path.endsWith(".ts") && it.authenticated }
        }
        moving()
        compose.runOnUiThread {
            Assert.assertFalse(adapter().select(previous, previous.menu.choices.first().key, "same-source"))
        }
        Assert.assertTrue(fixture.hits.all { it.authenticated })
    }

    private fun openAudioMenu() {
        val expected = trackSnapshot().menu.choices.first().key
        if (compose.onAllNodesWithTag("player_top_bar").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player_gesture_surface").performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(700)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("更多").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("音轨").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("音轨").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("audio_track_${expected.groupIndex}_${expected.trackIndex}").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun loadingHidesExistingAudioMenuAndRejectsOldSelection() {
        val previous = trackSnapshot()
        openAudioMenu()
        val alternate = previous.menu.choices.first { !it.selected }
        val staleClick = compose.onNodeWithTag("audio_track_${alternate.key.groupIndex}_${alternate.key.trackIndex}")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { state = state.copy(ui = V2PlaybackUiState.Loading) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("音轨").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("音轨").assertDoesNotExist()
        var playing = true
        compose.waitUntil(5_000) { compose.runOnUiThread { playing = GSYVideoManager.instance().isPlaying }; !playing }
        compose.runOnUiThread {
            staleClick()
            Assert.assertEquals(previous.menu.choices.filter { it.selected }.map { it.key },
                adapter().read("same-source")!!.menu.choices.filter { it.selected }.map { it.key })
        }
    }
    @Test fun failedStateHidesExistingAudioMenu() {
        trackSnapshot()
        openAudioMenu()
        compose.runOnIdle { state = state.copy(ui = V2PlaybackUiState.Failed("测试播放失败")) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("音轨").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("音轨").assertDoesNotExist()
    }
}
