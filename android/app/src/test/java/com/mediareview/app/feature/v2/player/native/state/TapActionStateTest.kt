package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TapActionState：双击区域判定、连续同向累计、中央不累计。 */
class TapActionStateTest {

    @Test
    fun `区域判定 左三分之一快退 右三分之一快进 中央播放暂停`() {
        val state = TapActionState()
        assertEquals(TapActionState.DoubleTapAction.SEEK_BACK, state.actionFor(0f))
        assertEquals(TapActionState.DoubleTapAction.SEEK_BACK, state.actionFor(0.32f))
        assertEquals(TapActionState.DoubleTapAction.SEEK_FORWARD, state.actionFor(0.67f))
        assertEquals(TapActionState.DoubleTapAction.SEEK_FORWARD, state.actionFor(1f))
        assertEquals(TapActionState.DoubleTapAction.TOGGLE_PLAY_PAUSE, state.actionFor(0.5f))
    }

    @Test
    fun `连续同向双击累计 10-20-30`() {
        val state = TapActionState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        assertEquals(1, state.consecutiveCount)
        assertEquals(10, state.deltaSeconds())
        state.onDoubleTap(0.1f, nowMs = 1500L)
        assertEquals(2, state.consecutiveCount)
        assertEquals(20, state.deltaSeconds())
        state.onDoubleTap(0.1f, nowMs = 2000L)
        assertEquals(3, state.consecutiveCount)
        assertEquals(30, state.deltaSeconds())
    }

    @Test
    fun `反向双击重置累计`() {
        val state = TapActionState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        state.onDoubleTap(0.9f, nowMs = 1500L)
        assertEquals(1, state.consecutiveCount)
        assertEquals(10, state.deltaSeconds())
    }

    @Test
    fun `中央播放暂停不参与累计并重置`() {
        val state = TapActionState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        state.onDoubleTap(0.1f, nowMs = 1500L)
        assertEquals(2, state.consecutiveCount)
        val action = state.onDoubleTap(0.5f, nowMs = 2000L)
        assertEquals(TapActionState.DoubleTapAction.TOGGLE_PLAY_PAUSE, action)
        assertEquals(0, state.consecutiveCount)
    }

    @Test
    fun `超出累计窗口重新从 1 开始`() {
        val state = TapActionState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        state.onDoubleTap(0.1f, nowMs = 5000L)
        assertEquals(1, state.consecutiveCount)
    }

    @Test
    fun `reset 清除累计`() {
        val state = TapActionState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        state.reset()
        assertEquals(0, state.consecutiveCount)
        assertNull(state.lastAction)
    }
}
