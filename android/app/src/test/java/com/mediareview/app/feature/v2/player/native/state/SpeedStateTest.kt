package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SpeedState：倍速状态 + 长按临时 2x 恢复，不覆盖正式选择。 */
class SpeedStateTest {

    @Test
    fun `默认 1x`() {
        val state = SpeedState()
        assertEquals(1.0f, state.userSpeed, 0.001f)
        assertEquals(1.0f, state.effectiveSpeed, 0.001f)
    }

    @Test
    fun `播放中长按临时 2x 不改变正式倍速`() {
        val state = SpeedState()
        state.setSpeed(1.25f)
        state.startTempSpeed(playing = true)
        assertTrue(state.tempActive)
        assertEquals(2.0f, state.effectiveSpeed, 0.001f)
        assertEquals(1.25f, state.userSpeed, 0.001f)
    }

    @Test
    fun `松手恢复用户原倍速`() {
        val state = SpeedState()
        state.setSpeed(1.5f)
        state.startTempSpeed(playing = true)
        assertEquals(2.0f, state.effectiveSpeed, 0.001f)
        state.endTempSpeed()
        assertFalse(state.tempActive)
        assertEquals(1.5f, state.effectiveSpeed, 0.001f)
    }

    @Test
    fun `暂停状态长按不触发`() {
        val state = SpeedState()
        state.startTempSpeed(playing = false)
        assertFalse(state.tempActive)
        assertEquals(1.0f, state.effectiveSpeed, 0.001f)
    }

    @Test
    fun `正式倍速选择不会被临时 2x 覆盖`() {
        val state = SpeedState()
        state.setSpeed(0.75f)
        state.startTempSpeed(playing = true)
        state.endTempSpeed()
        assertEquals(0.75f, state.userSpeed, 0.001f)
        assertEquals(0.75f, state.effectiveSpeed, 0.001f)
    }
}
