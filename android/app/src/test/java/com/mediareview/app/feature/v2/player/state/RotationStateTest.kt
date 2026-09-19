package com.mediareview.app.feature.v2.player.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RotationState：进入记录方向、切换横竖屏、离开恢复一次（幂等）。
 */
class RotationStateTest {

    @Test
    fun `进入播放器记录进入前方向`() {
        val state = RotationState()
        state.initialize(currentOrientation = -1) // UNSPECIFIED
        assertEquals(-1, state.savedOrientation)
        assertTrue(!state.restored)
    }

    @Test
    fun `进入方向仅记录一次`() {
        val state = RotationState()
        state.initialize(currentOrientation = -1)
        state.initialize(currentOrientation = 1) // 第二次忽略
        assertEquals(-1, state.savedOrientation)
    }

    @Test
    fun `点击旋转切换横竖屏并给出对应方向码`() {
        val state = RotationState()
        state.initialize(currentOrientation = -1)
        assertEquals(RotationState.Orientation.PORTRAIT, state.orientation)
        state.toggle()
        assertEquals(RotationState.Orientation.LANDSCAPE, state.orientation)
        assertEquals(6, state.requestedCode()) // SENSOR_LANDSCAPE
        state.toggle()
        assertEquals(RotationState.Orientation.PORTRAIT, state.orientation)
        assertEquals(7, state.requestedCode()) // SENSOR_PORTRAIT
    }

    @Test
    fun `离开播放器恢复进入前方向且幂等`() {
        val state = RotationState()
        state.initialize(currentOrientation = -1)
        state.toggle() // 切到横屏
        assertEquals(-1, state.restore())
        assertNull(state.restore()) // 第二次返回 null
        assertTrue(state.restored)
    }

    @Test
    fun `未初始化也能安全恢复返回 null`() {
        val state = RotationState()
        assertNull(state.restore())
    }
}
