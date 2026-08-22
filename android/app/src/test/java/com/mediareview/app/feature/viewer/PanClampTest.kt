package com.mediareview.app.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** 阶段 11:图片查看器平移边界约束。 */
class PanClampTest {

    @Test
    fun `scale 1x 时偏移强制归零`() {
        val (x, y) = clampPanOffset(100f, -200f, 1f, 1080f, 1920f)
        assertEquals(0f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    @Test
    fun `放大时偏移被限制在屏幕内`() {
        // 2x,1080x1920 -> maxX = 540, maxY = 960
        val (x, y) = clampPanOffset(2000f, -5000f, 2f, 1080f, 1920f)
        assertEquals(540f, x, 0.001f)
        assertEquals(-960f, y, 0.001f)
    }

    @Test
    fun `未超出边界时保持原值`() {
        val (x, y) = clampPanOffset(50f, -100f, 2f, 1080f, 1920f)
        assertEquals(50f, x, 0.001f)
        assertEquals(-100f, y, 0.001f)
    }
}
