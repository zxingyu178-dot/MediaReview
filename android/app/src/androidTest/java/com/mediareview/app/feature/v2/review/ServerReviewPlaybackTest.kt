package com.mediareview.app.feature.v2.review

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.data.AlbumCoverStore
import com.mediareview.app.feature.v2.data.DemoMediaRepository
import com.mediareview.app.feature.v2.player.RealHttpPlaybackFixture
import com.mediareview.app.feature.v2.player.V2PlaybackUiState
import com.mediareview.app.feature.v2.review.data.DemoReviewSessionRepository
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerReviewPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null

    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        fixture?.close()
    }

    private fun launch(vm: V2ReviewViewModel) {
        compose.activity.viewModelStore.put("http-review", vm)
        compose.setContent { MediaReviewTheme { V2ReviewScreen(vm, {}, {}) } }
    }

    @Test fun server批阅上滑后第二个真实Source持续播放且认证正确() {
        val live = RealHttpPlaybackFixture().also { fixture = it }
        val vm = V2ReviewViewModel(live.repository, live.sessions)
        launch(vm)
        // 内核 source 证据 = 播放器真实发出的视频请求 URL 等于生产解析出的 endpoint（GSY Compose host
        // player 无公开 URL 读取接口，不猜测内核内部字段）。
        assertPlaybackAdvances {
            val ready = vm.playback.value as? V2PlaybackUiState.Ready
            ready != null && ready.source.mediaId == "first" &&
                live.videos.any { it.url == ready.endpoint.url }
        }
        assertTrue("第一项必须实际请求 MP4", live.videos.any { it.mediaId == "first" && it.authenticated })
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 500) }
        // 不直接调用 VM 换页，必须由生产 VerticalPager 手势触发。
        compose.waitUntil(30_000) {
            (vm.state.value as? V2ReviewUiState.Ready)?.absoluteCurrentIndex == 1 &&
                live.videos.any { it.mediaId == "second" && it.authenticated }
        }
        assertPlaybackAdvances {
            val ready = vm.playback.value as? V2PlaybackUiState.Ready
            ready != null && ready.source.mediaId == "second" &&
                live.videos.any { it.url == ready.endpoint.url }
        }
        assertTrue("所有真实流请求必须携带认证头", live.videos.all { it.authenticated })
    }

    @Test fun demo批阅快速起播回归() {
        val repository = DemoMediaRepository(compose.activity, AlbumCoverStore(compose.activity))
        val vm = V2ReviewViewModel(repository, DemoReviewSessionRepository(repository))
        launch(vm)
        assertPlaybackAdvances { vm.playback.value is V2PlaybackUiState.Ready }
    }

    private fun assertPlaybackAdvances(sourceMatches: () -> Boolean) {
        var first = 0L
        compose.waitUntil(60_000) {
            compose.runOnUiThread {
                val player = GSYVideoManager.instance()
                first = if (sourceMatches() && player.isPlaying && player.duration > 0) player.currentPosition else 0
            }
            first > 500
        }
        val baseline = first
        compose.waitUntil(15_000) {
            var advanced = false
            compose.runOnUiThread {
                val player = GSYVideoManager.instance()
                advanced = sourceMatches() && player.isPlaying && player.currentPosition > baseline + 500
            }
            advanced
        }
    }
}
