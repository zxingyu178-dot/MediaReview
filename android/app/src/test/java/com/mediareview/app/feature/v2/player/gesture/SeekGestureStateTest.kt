package com.mediareview.app.feature.v2.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SeekGestureState：基于拖动开始快照、Seek 边界 clamp、灵敏度自适应、取消不提交。
 */
class SeekGestureStateTest {

    @Test
    fun `拖动基于开始快照不逐帧累加`() {
        val state = SeekGestureState()
        // 10s 视频，宽 1080，从 3000ms 开始向右拖动 540px
        state.onStart(positionMs = 3000L, startX = 100f, widthPx = 1080f)
        state.onDrag(currentX = 640f, durationMs = 10_000L)
        val target = state.currentTargetMs
        // 灵敏度：span = 30s，ratio = 0.5 → 3000 + 15000 = 18000，clamp 到 10000
        assertEquals(10_000L, target)
        // 再次拖动同一 X，结果与快照一致（不依赖上一次 currentTarget 累加）
        state.onDrag(currentX = 640f, durationMs = 10_000L)
        assertEquals(target, state.currentTargetMs)
    }

    @Test
    fun `Seek 左边界不越界`() {
        val state = SeekGestureState()
        state.onStart(positionMs = 5000L, startX = 500f, widthPx = 1000f)
        state.onDrag(currentX = -500f, durationMs = 60_000L) // 大幅左拖
        assertEquals(0L, state.currentTargetMs)
        assertTrue(state.deltaMs < 0L)
    }

    @Test
    fun `Seek 右边界不越界`() {
        val state = SeekGestureState()
        state.onStart(positionMs = 5000L, startX = 100f, widthPx = 1000f)
        state.onDrag(currentX = 2000f, durationMs = 60_000L) // 大幅右拖
        assertEquals(60_000L, state.currentTargetMs)
        assertTrue(state.deltaMs > 0L)
    }

    @Test
    fun `灵敏度短视频全宽映射不低于 30 秒`() {
        val state = SeekGestureState()
        state.onStart(positionMs = 0L, startX = 0f, widthPx = 1080f)
        state.onDrag(currentX = 1080f, durationMs = 10_000L)
        // 10s 视频全宽 = 30s span → 已 clamp 到 10s（整段可快速刷完）
        assertEquals(10_000L, state.currentTargetMs)
    }

    @Test
    fun `灵敏度长视频全宽映射不超过 90 秒`() {
        val state = SeekGestureState()
        state.onStart(positionMs = 0L, startX = 0f, widthPx = 1080f)
        state.onDrag(currentX = 1080f, durationMs = 60 * 60_000L) // 60 分钟
        // 60min 视频全宽 = 90s span
        assertEquals(SeekGestureState.MAX_SPAN_MS, state.currentTargetMs)
    }

    @Test
    fun `灵敏度中等视频全宽映射为视频时长`() {
        val state = SeekGestureState()
        state.onStart(positionMs = 0L, startX = 0f, widthPx = 1080f)
        state.onDrag(currentX = 1080f, durationMs = 40_000L)
        assertEquals(40_000L, state.currentTargetMs)
    }

    @Test
    fun `有效跨度随时长自适应且始终落在 30 到 90 秒`() {
        assertEquals(30_000L, SeekGestureState.effectiveSpanMs(10_000L))
        assertEquals(30_000L, SeekGestureState.effectiveSpanMs(5_000L))
        assertEquals(40_000L, SeekGestureState.effectiveSpanMs(40_000L))
        assertEquals(90_000L, SeekGestureState.effectiveSpanMs(300_000L))
        assertEquals(90_000L, SeekGestureState.effectiveSpanMs(3_600_000L))
    }

    @Test
    fun `onEnd 返回最终目标并结束拖拽`() {
        val state = SeekGestureState()
        state.onStart(positionMs = 0L, startX = 0f, widthPx = 1000f)
        state.onDrag(currentX = 500f, durationMs = 60_000L)
        val target = state.onEnd()
        assertEquals(state.currentTargetMs, target)
        assertFalse(state.isActive)
    }

    @Test
    fun `onCancel 放弃拖拽不提交`() {
        val state = SeekGestureState()
        state.onStart(positionMs = 0L, startX = 0f, widthPx = 1000f)
        state.onDrag(currentX = 800f, durationMs = 60_000L)
        state.onCancel()
        assertFalse(state.isActive)
        // 不 active 时再次 onDrag 不改变状态
        state.onDrag(currentX = 900f, durationMs = 60_000L)
        assertEquals(0L, state.currentTargetMs)
    }
}
