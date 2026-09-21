package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SeekGesturePreview：自适应灵敏度、边界 clamp、松手提交 / 取消丢弃。 */
class SeekGesturePreviewTest {

    @Test
    fun `拖动基于开始快照`() {
        val state = SeekGesturePreview()
        state.onStart(positionMs = 3000L, startX = 100f, widthPx = 1080f)
        state.onDrag(currentX = 640f, durationMs = 10_000L)
        assertEquals(10_000L, state.targetMs) // 30s span 右移 0.5 → 18000 clamp 到 10000
        state.onDrag(currentX = 640f, durationMs = 10_000L)
        assertEquals(10_000L, state.targetMs)
    }

    @Test
    fun `灵敏度短视频全宽 = 30s span 不越界`() {
        val state = SeekGesturePreview()
        state.onStart(0L, 0f, 1080f)
        state.onDrag(1080f, durationMs = 10_000L)
        assertEquals(10_000L, state.targetMs)
    }

    @Test
    fun `灵敏度长视频全宽 = 90s span 不越界`() {
        val state = SeekGesturePreview()
        state.onStart(0L, 0f, 1080f)
        state.onDrag(1080f, durationMs = 3_600_000L)
        assertEquals(90_000L, state.targetMs)
    }

    @Test
    fun `灵敏度中等视频全宽 = 视频时长`() {
        val state = SeekGesturePreview()
        state.onStart(0L, 0f, 1080f)
        state.onDrag(1080f, durationMs = 40_000L)
        assertEquals(40_000L, state.targetMs)
    }

    @Test
    fun `span 始终落在 30s 到 90s`() {
        assertEquals(30_000L, SeekGesturePreview.spanFor(5_000L))
        assertEquals(30_000L, SeekGesturePreview.spanFor(20_000L))
        assertEquals(40_000L, SeekGesturePreview.spanFor(40_000L))
        assertEquals(90_000L, SeekGesturePreview.spanFor(300_000L))
        assertEquals(90_000L, SeekGesturePreview.spanFor(3_600_000L))
    }

    @Test
    fun `Seek 边界不越界`() {
        val state = SeekGesturePreview()
        state.onStart(5_000L, 500f, 1000f)
        state.onDrag(-500f, durationMs = 60_000L)
        assertEquals(0L, state.targetMs)
        state.onStart(5_000L, 100f, 1000f)
        state.onDrag(2000f, durationMs = 60_000L)
        assertEquals(60_000L, state.targetMs)
    }

    @Test
    fun `onEnd 返回最终目标并结束`() {
        val state = SeekGesturePreview()
        state.onStart(0L, 0f, 1000f)
        state.onDrag(500f, durationMs = 60_000L)
        assertEquals(state.targetMs, state.onEnd())
        assertFalse(state.isActive)
    }

    @Test
    fun `onCancel 放弃拖拽`() {
        val state = SeekGesturePreview()
        state.onStart(0L, 0f, 1000f)
        state.onDrag(800f, durationMs = 60_000L)
        state.onCancel()
        assertFalse(state.isActive)
        assertNull(state.onEnd())
    }

    @Test
    fun `未开始时 onEnd 返回 null`() {
        val state = SeekGesturePreview()
        assertNull(state.onEnd())
        assertTrue(!state.isActive)
    }
}
