package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpeedState：真实倍速以 controller.snapshot.speed 为准；本类只保存期望倍速与临时 2x 恢复点。
 */
class SpeedStateTest {

    @Test
    fun `默认期望倍速 1x`() {
        val state = SpeedState()
        assertEquals(1.0f, state.expectedSpeed, 0.001f)
        assertFalse(state.tempActive)
    }

    @Test
    fun `正式倍速选择记录为期望倍速`() {
        val state = SpeedState()
        state.setExpected(1.25f)
        assertEquals(1.25f, state.expectedSpeed, 0.001f)
    }

    @Test
    fun `播放中长按临时 2x 不改变期望倍速 松手恢复长按前实际倍速`() {
        val state = SpeedState()
        state.setExpected(1.25f)
        state.startTemp(currentSpeed = 1.25f, playing = true)
        assertTrue(state.tempActive)
        // 临时 2x 由 Screen 层下发 controller；期望倍速不变
        assertEquals(1.25f, state.expectedSpeed, 0.001f)
        val restore = state.endTemp()
        assertFalse(state.tempActive)
        assertEquals(1.25f, restore, 0.001f)
    }

    @Test
    fun `长按前实际倍速与期望倍速不同 松手恢复实际值`() {
        val state = SpeedState()
        state.setExpected(1.5f)
        // 例如切源瞬间 controller 实际仍是 1x 时开始长按
        state.startTemp(currentSpeed = 1.0f, playing = true)
        assertEquals(1.0f, state.endTemp(), 0.001f)
        assertEquals(1.5f, state.expectedSpeed, 0.001f)
    }

    @Test
    fun `暂停状态长按不触发`() {
        val state = SpeedState()
        state.startTemp(currentSpeed = 1.0f, playing = false)
        assertFalse(state.tempActive)
        assertEquals(1.0f, state.endTemp(), 0.001f)
    }
}
