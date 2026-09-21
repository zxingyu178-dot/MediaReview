package com.mediareview.app.feature.v2.player.native.state

import com.mediareview.app.feature.v2.player.native.state.PlaybackUiMapper.CenterOverlay
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlaybackUiMapper：阶段 → 中央浮层（Completed/Error/Loading），播放状态以 snapshot 为准。 */
class PlaybackUiMapperTest {

    @Test
    fun `Preparing 与 Buffering 显示 Loading`() {
        assertEquals(CenterOverlay.LOADING, PlaybackUiMapper.overlayFor(GSYPlayState.Preparing))
        assertEquals(CenterOverlay.LOADING, PlaybackUiMapper.overlayFor(GSYPlayState.Buffering))
    }

    @Test
    fun `Completed 显示重播浮层`() {
        assertEquals(CenterOverlay.COMPLETED, PlaybackUiMapper.overlayFor(GSYPlayState.Completed))
    }

    @Test
    fun `Error 显示错误浮层`() {
        assertEquals(CenterOverlay.ERROR, PlaybackUiMapper.overlayFor(GSYPlayState.Error))
    }

    @Test
    fun `其他阶段无浮层`() {
        assertEquals(CenterOverlay.NONE, PlaybackUiMapper.overlayFor(GSYPlayState.Playing))
        assertEquals(CenterOverlay.NONE, PlaybackUiMapper.overlayFor(GSYPlayState.Paused))
        assertEquals(CenterOverlay.NONE, PlaybackUiMapper.overlayFor(GSYPlayState.Idle))
    }

    @Test
    fun `isPlaying 以 snapshot 状态为准`() {
        assertTrue(PlaybackUiMapper.isPlaying(GSYPlayState.Playing))
        assertTrue(PlaybackUiMapper.isPlaying(GSYPlayState.Buffering))
        assertFalse(PlaybackUiMapper.isPlaying(GSYPlayState.Paused))
        assertFalse(PlaybackUiMapper.isPlaying(GSYPlayState.Completed))
        assertFalse(PlaybackUiMapper.isPlaying(GSYPlayState.Error))
    }
}
