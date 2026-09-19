package com.mediareview.app.feature.v2.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VolumeBrightnessGestureState：起始值快照、统一灵敏度、0..100 不越界、取消结束状态。
 */
class VolumeBrightnessGestureStateTest {

    @Test
    fun `拖动开始记录起始快照`() {
        val state = VolumeBrightnessGestureState()
        state.onStart(startY = 500f, volumePct = 40f, brightnessPct = 60f)
        assertTrue(state.isActive)
        assertEquals(500f, state.startY, 0.001f)
        assertEquals(40f, state.startVolumePct, 0.001f)
        assertEquals(60f, state.startBrightnessPct, 0.001f)
    }

    @Test
    fun `上滑增大下滑减小`() {
        val state = VolumeBrightnessGestureState()
        state.onStart(startY = 500f, volumePct = 50f, brightnessPct = 50f)
        state.onDrag(currentY = 400f, heightPx = 1000f) // 上滑 100px = +10%
        assertEquals(60f, state.currentVolumePct, 0.001f)
        assertEquals(60f, state.currentBrightnessPct, 0.001f)
        state.onDrag(currentY = 600f, heightPx = 1000f) // 下滑 100px = -10%
        assertEquals(40f, state.currentVolumePct, 0.001f)
        assertEquals(40f, state.currentBrightnessPct, 0.001f)
    }

    @Test
    fun `越界 clamp 到 0 与 100`() {
        val state = VolumeBrightnessGestureState()
        state.onStart(startY = 500f, volumePct = 10f, brightnessPct = 90f)
        state.onDrag(currentY = -500f, heightPx = 1000f) // 大幅上滑
        assertEquals(100f, state.currentVolumePct, 0.001f)
        assertEquals(100f, state.currentBrightnessPct, 0.001f)
        state.onDrag(currentY = 5000f, heightPx = 1000f) // 大幅下滑
        assertEquals(0f, state.currentVolumePct, 0.001f)
        assertEquals(0f, state.currentBrightnessPct, 0.001f)
    }

    @Test
    fun `快速慢速滑动结果一致`() {
        val state = VolumeBrightnessGestureState()
        state.onStart(startY = 500f, volumePct = 50f, brightnessPct = 50f)
        // 慢速分两次 vs 快速一次，位移相同结果相同
        state.onDrag(currentY = 500f - 100f, heightPx = 1000f)
        state.onDrag(currentY = 500f - 200f, heightPx = 1000f)
        assertEquals(70f, state.currentVolumePct, 0.001f)

        val state2 = VolumeBrightnessGestureState()
        state2.onStart(startY = 500f, volumePct = 50f, brightnessPct = 50f)
        state2.onDrag(currentY = 500f - 200f, heightPx = 1000f)
        assertEquals(70f, state2.currentVolumePct, 0.001f)
    }

    @Test
    fun `起始值越界被归一化`() {
        val state = VolumeBrightnessGestureState()
        state.onStart(startY = 100f, volumePct = 150f, brightnessPct = -20f)
        assertEquals(100f, state.startVolumePct, 0.001f)
        assertEquals(0f, state.startBrightnessPct, 0.001f)
    }

    @Test
    fun `onEnd 与 onCancel 都结束状态`() {
        val state = VolumeBrightnessGestureState()
        state.onStart(startY = 100f, volumePct = 50f, brightnessPct = 50f)
        state.onEnd()
        assertFalse(state.isActive)
        state.onStart(startY = 100f, volumePct = 50f, brightnessPct = 50f)
        state.onCancel()
        assertFalse(state.isActive)
    }
}
