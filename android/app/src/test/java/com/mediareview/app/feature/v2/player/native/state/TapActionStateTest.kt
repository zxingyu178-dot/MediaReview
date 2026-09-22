package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TapActionState：双击区域判定、连续同向累计、累计模型（起始位置 + 增量）、中央不累计。 */
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
        state.onDoubleTap(0.1f, nowMs = 1000L, currentPositionMs = 30_000L)
        assertEquals(1, state.consecutiveCount)
        assertEquals(10, state.deltaSeconds())
        state.onDoubleTap(0.1f, nowMs = 1500L, currentPositionMs = 40_000L)
        assertEquals(2, state.consecutiveCount)
        assertEquals(20, state.deltaSeconds())
        state.onDoubleTap(0.1f, nowMs = 2000L, currentPositionMs = 50_000L)
        assertEquals(3, state.consecutiveCount)
        assertEquals(30, state.deltaSeconds())
    }

    @Test
    fun `连续双击目标始终基于起始位置加累计增量`() {
        val state = TapActionState()
        // 第一次双击：起始位置 30s，+10 → 目标 40s
        state.onDoubleTap(0.9f, nowMs = 1000L, currentPositionMs = 30_000L)
        assertEquals(30_000L, state.startPositionMs)
        assertEquals(40_000L, state.targetPositionMs())
        // 第二次双击（窗口内）：仍基于起始 30s，+20 → 目标 50s（不是 30+10+20=60）
        state.onDoubleTap(0.9f, nowMs = 1500L, currentPositionMs = 40_000L)
        assertEquals(30_000L, state.startPositionMs)
        assertEquals(50_000L, state.targetPositionMs())
    }

    @Test
    fun `反向双击重置累计并从新位置起步`() {
        val state = TapActionState()
        state.onDoubleTap(0.9f, nowMs = 1000L, currentPositionMs = 30_000L)
        state.onDoubleTap(0.9f, nowMs = 1500L, currentPositionMs = 40_000L)
        // 反向：新序列从当前位置 45s 起步，-10 → 35s
        state.onDoubleTap(0.1f, nowMs = 2000L, currentPositionMs = 45_000L)
        assertEquals(1, state.consecutiveCount)
        assertEquals(10, state.deltaSeconds())
        assertEquals(45_000L, state.startPositionMs)
        assertEquals(35_000L, state.targetPositionMs())
    }

    @Test
    fun `快退累计增量为负`() {
        val state = TapActionState()
        state.onDoubleTap(0.1f, nowMs = 1000L, currentPositionMs = 60_000L)
        state.onDoubleTap(0.1f, nowMs = 1500L, currentPositionMs = 50_000L)
        assertEquals(-20_000L, state.accumulatedDeltaMs)
        assertEquals(40_000L, state.targetPositionMs())
        assertEquals(20, state.deltaSeconds())
    }

    @Test
    fun `中央播放暂停不参与累计并重置`() {
        val state = TapActionState()
        state.onDoubleTap(0.9f, nowMs = 1000L, currentPositionMs = 30_000L)
        state.onDoubleTap(0.9f, nowMs = 1500L, currentPositionMs = 40_000L)
        assertEquals(2, state.consecutiveCount)
        val action = state.onDoubleTap(0.5f, nowMs = 2000L, currentPositionMs = 50_000L)
        assertEquals(TapActionState.DoubleTapAction.TOGGLE_PLAY_PAUSE, action)
        assertEquals(0, state.consecutiveCount)
    }

    @Test
    fun `超出累计窗口重新从 1 开始`() {
        val state = TapActionState()
        state.onDoubleTap(0.9f, nowMs = 1000L, currentPositionMs = 30_000L)
        state.onDoubleTap(0.9f, nowMs = 5000L, currentPositionMs = 40_000L)
        assertEquals(1, state.consecutiveCount)
        assertEquals(40_000L, state.startPositionMs)
    }

    @Test
    fun `reset 清除累计`() {
        val state = TapActionState()
        state.onDoubleTap(0.9f, nowMs = 1000L, currentPositionMs = 30_000L)
        state.reset()
        assertEquals(0, state.consecutiveCount)
        assertEquals(0L, state.targetPositionMs())
        assertNull(state.lastAction)
    }
}