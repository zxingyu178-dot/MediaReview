package com.mediareview.app.feature.v2.player

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2PlaybackStage
import com.mediareview.app.feature.v2.review.V2ReviewScreen
import com.mediareview.app.feature.v2.review.V2ReviewUiState
import com.mediareview.app.feature.v2.review.V2ReviewViewModel
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/** 生产 Review 的真实同ID源替换验收；不得以 Full 的 setState 测试代替。 */
@RunWith(AndroidJUnit4::class)
class Stage8HReviewSourceReplacementTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private lateinit var track: Stage8HTrackSourceFixture
    private lateinit var live: RealHttpPlaybackFixture
    private lateinit var vm: V2ReviewViewModel
    private var direct: V2PlaybackEndpoint? = null
    private var fallback: V2PlaybackEndpoint? = null

    @Before fun setup() {
        track = Stage8HTrackSourceFixture()
        live = RealHttpPlaybackFixture(longVideo = true)
        direct = track.mp4Endpoint
    }

    @After fun cleanup() {
        // 仅清理，不能在 releaseAllVideos 后断言旧内核释放成功。
        compose.runOnUiThread {
            GSYVideoManager.releaseAllVideos()
            compose.activity.viewModelStore.clear()
        }
        if (::live.isInitialized) live.close()
        if (::track.isInitialized) track.close()
    }

    private fun launch(withFallback: Boolean): IjkExo2MediaPlayer {
        fallback = if (withFallback) track.hlsEndpoint else null
        val repository = object : MediaRepository by live.repository {
            override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource {
                // second 仍走真实 repository，只允许 P1 解析，绝不启动第二 player。
                if (mediaId != "first") return live.repository.resolvePlayback(mediaId)
                return V2PlaybackSource("first", "批阅同视频切源", direct!!, fallback, durationMs = 60_000)
            }
        }
        compose.runOnUiThread {
            vm = V2ReviewViewModel(repository, live.sessions)
            compose.activity.viewModelStore.put("stage8h-review-source", vm)
        }
        compose.setContent { MediaReviewTheme { V2ReviewScreen(vm, {}, {}) } }
        val first = moving(track.mp4Endpoint, V2PlaybackStage.DIRECT)
        compose.waitUntil(10_000) { live.secondPlaybackRequested.count == 0L }
        assertP0Only()
        return first
    }

    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun ready(stage: V2PlaybackStage, endpoint: V2PlaybackEndpoint): Boolean {
        val value = vm.playback.value as? V2PlaybackUiState.Ready ?: return false
        return value.source.mediaId == "first" && value.stage == stage && value.endpoint == endpoint
    }
    private fun moving(endpoint: V2PlaybackEndpoint, stage: V2PlaybackStage): IjkExo2MediaPlayer {
        compose.waitUntil(60_000) {
            ready(stage, endpoint) && kernel {
                val player = it.player?.mediaPlayer as? IjkExo2MediaPlayer
                player != null && player.dataSource == endpoint.url && it.isPlaying && it.currentPosition > 500
            }
        }
        val player = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        val before = kernel { it.currentPosition }
        compose.waitUntil(10_000) {
            ready(stage, endpoint) && kernel {
                it.player?.mediaPlayer === player && it.isPlaying && it.currentPosition > before + 500
            }
        }
        Assert.assertTrue("必须是当前源实际内核持续播放", ready(stage, endpoint))
        Assert.assertTrue(kernel { it.player.mediaPlayer === player && it.isPlaying && it.currentPosition > before + 500 })
        return player
    }
    private fun assertRetired(old: IjkExo2MediaPlayer, current: IjkExo2MediaPlayer) {
        compose.runOnUiThread {
            Assert.assertNotSame("同 ID 换源必须替换实际内核", old, current)
            Assert.assertFalse("旧内核不得继续 Playing", old.isPlaying)
            Assert.assertTrue("旧内核必须清空 dataSource", old.dataSource.isNullOrEmpty())
        }
    }
    private fun assertP0Only() {
        Assert.assertEquals(0, (vm.state.value as V2ReviewUiState.Ready).absoluteCurrentIndex)
        Assert.assertFalse("P1 仅预解析，不能请求下一视频流", live.videos.any { it.mediaId == "second" })
        Assert.assertTrue("所有实际媒体 HTTP 请求必须认证", track.hits.isNotEmpty() && track.hits.all { it.authenticated })
        Assert.assertTrue("真实 repository 的流请求也必须认证", live.videos.all { it.authenticated })
    }

    @Test fun reviewSameIdDirectFailureUsesRealHlsAndContinuesActualPlayback() {
        val old = launch(withFallback = true)
        compose.runOnUiThread { vm.onPlaybackFailed("first") }
        compose.waitUntil(30_000) {
            track.hits.any { it.path == "/hls/index.m3u8" && it.authenticated } &&
                track.hits.any { it.path.endsWith(".ts") && it.authenticated }
        }
        val current = moving(track.hlsEndpoint, V2PlaybackStage.HLS_FALLBACK)
        assertRetired(old, current)
        assertP0Only()
    }

    @Test fun reviewSameIdFailedThenRetryUsesNewUrlAndContinuesActualPlayback() {
        val old = launch(withFallback = false)
        compose.runOnUiThread { vm.onPlaybackFailed("first") }
        compose.waitUntil(5_000) { vm.playback.value is V2PlaybackUiState.Failed && !kernel { it.isPlaying } }
        Assert.assertTrue("无 fallback 必须进入 Failed，不能假成功", vm.playback.value is V2PlaybackUiState.Failed)
        compose.runOnUiThread {
            direct = track.replacementEndpoint
            vm.retryPlayback()
        }
        compose.waitUntil(30_000) { track.hits.any { it.path == "/replacement.mp4" && it.authenticated } }
        val current = moving(track.replacementEndpoint, V2PlaybackStage.DIRECT)
        assertRetired(old, current)
        assertP0Only()
    }
}
