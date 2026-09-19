package com.mediareview.app.feature.v2.player.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlaybackSpeedState：长按临时 2x、松手恢复用户原倍速、暂停不触发、不改用户正式倍速。
 */
class PlaybackSpeedStateTest {

    @Test
    fun `默认倍速 1x`() {
        val state = PlaybackSpeedState()
        assertEquals(1.0f, state.userSpeed, 0.001f)
        assertEquals(1.0f, state.effectiveSpeed, 0.001f)
    }

    @Test
    fun `播放中长按临时 2x`() {
        val state = PlaybackSpeedState()
        state.startTempSpeed(playing = true)
        assertTrue(state.tempActive)
        assertEquals(2.0f, state.effectiveSpeed, 0.001f)
        assertEquals(1.0f, state.userSpeed, 0.001f) // 用户正式倍速不变
    }

    @Test
    fun `松手恢复用户原倍速`() {
        val state = PlaybackSpeedState()
        state.setSpeed(1.5f)
        state.startTempSpeed(playing = true)
        assertEquals(2.0f, state.effectiveSpeed, 0.001f)
        state.endTempSpeed()
        assertFalse(state.tempActive)
        assertEquals(1.5f, state.effectiveSpeed, 0.001f)
    }

    @Test
    fun `暂停状态长按不触发临时 2x`() {
        val state = PlaybackSpeedState()
        state.startTempSpeed(playing = false)
        assertFalse(state.tempActive)
        assertEquals(1.0f, state.effectiveSpeed, 0.001f)
    }

    @Test
    fun `临时 2x 不修改用户正式倍速选择`() {
        val state = PlaybackSpeedState()
        state.setSpeed(0.75f)
        state.startTempSpeed(playing = true)
        state.endTempSpeed()
        assertEquals(0.75f, state.userSpeed, 0.001f)
        assertEquals(0.75f, state.effectiveSpeed, 0.001f)
    }

    @Test
    fun `未激活时结束临时倍速无副作用`() {
        val state = PlaybackSpeedState()
        state.endTempSpeed()
        assertFalse(state.tempActive)
        assertEquals(1.0f, state.effectiveSpeed, 0.001f)
    }
}
