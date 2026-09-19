package com.mediareview.app.feature.v2.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TapGestureState：单击 / 双击互斥、双击区域判定、连续双击累计。
 */
class TapGestureStateTest {

    // ---------- 单击 / 双击互斥 ----------

    @Test
    fun `双击窗口内两次抬手判定 DOUBLE_TAP 且不产生单击`() {
        val state = TapGestureState()
        val decision = state.onUp(firstUpMs = 1000L, secondUpMs = 1200L)
        assertEquals(TapGestureState.TapDecision.DOUBLE_TAP, decision)
        // 互斥：DOUBLE_TAP 判定时没有单独的 SINGLE_TAP 输出
        assertEquals(0, state.consecutiveCount)
    }

    @Test
    fun `超过双击窗口判定 SINGLE_TAP`() {
        val state = TapGestureState()
        val decision = state.onUp(firstUpMs = 1000L, secondUpMs = 1500L)
        assertEquals(TapGestureState.TapDecision.SINGLE_TAP, decision)
    }

    @Test
    fun `双击窗口边界 300ms 内仍判定双击`() {
        val state = TapGestureState()
        assertEquals(
            TapGestureState.TapDecision.DOUBLE_TAP,
            state.onUp(1000L, 1000L + TapGestureState.DOUBLE_TAP_WINDOW_MS),
        )
    }

    // ---------- 双击区域判定 ----------

    @Test
    fun `左三分之一快退`() {
        val state = TapGestureState()
        assertEquals(TapGestureState.DoubleTapAction.SEEK_BACK, state.actionFor(0f))
        assertEquals(TapGestureState.DoubleTapAction.SEEK_BACK, state.actionFor(0.32f))
    }

    @Test
    fun `右三分之一快进`() {
        val state = TapGestureState()
        assertEquals(TapGestureState.DoubleTapAction.SEEK_FORWARD, state.actionFor(0.67f))
        assertEquals(TapGestureState.DoubleTapAction.SEEK_FORWARD, state.actionFor(1f))
    }

    @Test
    fun `中央播放暂停`() {
        val state = TapGestureState()
        assertEquals(TapGestureState.DoubleTapAction.TOGGLE_PLAY_PAUSE, state.actionFor(0.5f))
        assertEquals(TapGestureState.DoubleTapAction.TOGGLE_PLAY_PAUSE, state.actionFor(0.4f))
        assertEquals(TapGestureState.DoubleTapAction.TOGGLE_PLAY_PAUSE, state.actionFor(0.6f))
    }

    // ---------- 连续双击累计 ----------

    @Test
    fun `连续同向双击累计 10-20-30`() {
        val state = TapGestureState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        assertEquals(1, state.consecutiveCount)
        assertEquals(10, state.deltaSeconds())
        state.onDoubleTap(0.1f, nowMs = 1500L) // 500ms < 700ms 窗口
        assertEquals(2, state.consecutiveCount)
        assertEquals(20, state.deltaSeconds())
        state.onDoubleTap(0.1f, nowMs = 2000L)
        assertEquals(3, state.consecutiveCount)
        assertEquals(30, state.deltaSeconds())
    }

    @Test
    fun `反向双击重置累计`() {
        val state = TapGestureState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        assertEquals(10, state.deltaSeconds())
        state.onDoubleTap(0.9f, nowMs = 1500L) // 换方向
        assertEquals(1, state.consecutiveCount)
        assertEquals(10, state.deltaSeconds())
    }

    @Test
    fun `中央播放暂停不参与累计并重置`() {
        val state = TapGestureState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        state.onDoubleTap(0.1f, nowMs = 1500L)
        assertEquals(2, state.consecutiveCount)
        val action = state.onDoubleTap(0.5f, nowMs = 2000L)
        assertEquals(TapGestureState.DoubleTapAction.TOGGLE_PLAY_PAUSE, action)
        assertEquals(0, state.consecutiveCount)
    }

    @Test
    fun `超出累计窗口重新从 1 开始`() {
        val state = TapGestureState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        state.onDoubleTap(0.1f, nowMs = 5000L) // 超过 700ms 窗口
        assertEquals(1, state.consecutiveCount)
    }

    @Test
    fun `reset 清除累计状态`() {
        val state = TapGestureState()
        state.onDoubleTap(0.1f, nowMs = 1000L)
        state.reset()
        assertEquals(0, state.consecutiveCount)
        assertTrue(state.lastAction == null)
    }
}
