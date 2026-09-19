package com.mediareview.app.feature.v2.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * mapPlaybackPhase：Ended / Error / 缓冲阶段映射（Ended 可重播、Error 可重试的状态来源）。
 */
class PlaybackPhaseTest {

    @Test
    fun `Error 优先于其他状态`() {
        assertEquals(PlaybackPhase.ERROR, mapPlaybackPhase(PlayerCompat.STATE_READY, true, true, hasError = true))
        assertEquals(PlaybackPhase.ERROR, mapPlaybackPhase(PlayerCompat.STATE_BUFFERING, false, true, hasError = true))
    }

    @Test
    fun `Ended 映射重播状态`() {
        assertEquals(PlaybackPhase.ENDED, mapPlaybackPhase(PlayerCompat.STATE_ENDED, false, true, hasError = false))
    }

    @Test
    fun `READY 播放中为 PLAYING 暂停为 PAUSED`() {
        assertEquals(PlaybackPhase.PLAYING, mapPlaybackPhase(PlayerCompat.STATE_READY, true, true, false))
        assertEquals(PlaybackPhase.PAUSED, mapPlaybackPhase(PlayerCompat.STATE_READY, false, true, false))
    }

    @Test
    fun `首次缓冲为 PREPARING 之后缓冲为 BUFFERING`() {
        assertEquals(PlaybackPhase.PREPARING, mapPlaybackPhase(PlayerCompat.STATE_BUFFERING, false, everReady = false, hasError = false))
        assertEquals(PlaybackPhase.BUFFERING, mapPlaybackPhase(PlayerCompat.STATE_BUFFERING, false, everReady = true, hasError = false))
    }

    @Test
    fun `IDLE 映射空闲状态`() {
        assertEquals(PlaybackPhase.IDLE, mapPlaybackPhase(PlayerCompat.STATE_IDLE, false, false, false))
        assertEquals(PlaybackPhase.IDLE, mapPlaybackPhase(0, false, false, false))
    }
}
