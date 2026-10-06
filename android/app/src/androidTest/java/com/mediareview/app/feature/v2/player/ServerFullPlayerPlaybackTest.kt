package com.mediareview.app.feature.v2.player

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.data.AlbumCoverStore
import com.mediareview.app.feature.v2.data.DemoMediaRepository
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerScreen
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerFullPlayerPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null

    @After fun cleanup() {
        compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        fixture?.close()
    }

    private fun launch(vm: V2NativePlayerViewModel, mediaId: String) {
        compose.activity.viewModelStore.put("http-full", vm)
        compose.runOnUiThread { vm.open(PlaybackContext(listOf(PlaybackQueueItem(mediaId, "测试视频")))) }
        compose.setContent {
            val state by vm.state.collectAsState()
            MediaReviewTheme {
                GsyNativePlayerScreen(state, vm::moveTo, vm::onPlaybackFailed, vm::retry, vm::reportProgress, {})
            }
        }
    }

    @Test fun server真实HTTP经过生产解析且持续播放() {
        val live = RealHttpPlaybackFixture().also { fixture = it }
        val vm = V2NativePlayerViewModel(live.repository)
        launch(vm, "first")
        assertPlaybackAdvances()
        assertTrue("视频请求必须携带解析出的认证头", live.videos.any { it.mediaId == "first" && it.authenticated })
        assertTrue("不允许任何视频请求遗漏认证头", live.videos.all { it.authenticated })
        assertTrue("必须走生产播放源 Ready", vm.state.value.ui is V2PlaybackUiState.Ready)
    }

    @Test fun demo完整播放器快速起播回归() {
        val repository = DemoMediaRepository(compose.activity, AlbumCoverStore(compose.activity))
        launch(V2NativePlayerViewModel(repository), "ai-005")
        assertPlaybackAdvances()
    }

    private fun assertPlaybackAdvances() {
        var first = 0L
        compose.waitUntil(60_000) {
            compose.runOnUiThread {
                val player = GSYVideoManager.instance()
                // duration >0 + isPlaying + position 前进，要求内核已经 prepare，而非 Source Ready。
                first = if (player.isPlaying && player.duration > 0) player.currentPosition else 0
            }
            first > 500
        }
        val baseline = first
        compose.waitUntil(15_000) {
            var advanced = false
            compose.runOnUiThread {
                val player = GSYVideoManager.instance()
                advanced = player.isPlaying && player.currentPosition > baseline + 500
            }
            advanced
        }
    }
}
