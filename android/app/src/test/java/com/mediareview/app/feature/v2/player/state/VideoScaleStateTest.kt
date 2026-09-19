package com.mediareview.app.feature.v2.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VideoScaleState：适应 / 裁剪 / 填充 三态切换与 RESIZE_MODE 映射。
 */
class VideoScaleStateTest {

    @Test
    fun `初始为适应`() {
        val state = VideoScaleState()
        assertEquals(VideoScaleState.ScaleMode.FIT, state.mode)
    }

    @Test
    fun `循环切换 FIT-CROP-FILL-FIT`() {
        val state = VideoScaleState()
        state.cycle()
        assertEquals(VideoScaleState.ScaleMode.CROP, state.mode)
        state.cycle()
        assertEquals(VideoScaleState.ScaleMode.FILL, state.mode)
        state.cycle()
        assertEquals(VideoScaleState.ScaleMode.FIT, state.mode)
    }

    @Test
    fun `set 直接设置指定比例`() {
        val state = VideoScaleState()
        state.set(VideoScaleState.ScaleMode.FILL)
        assertEquals(VideoScaleState.ScaleMode.FILL, state.mode)
    }

    @Test
    fun `映射 RESIZE_MODE 常量`() {
        assertEquals(0, VideoScaleState.ScaleMode.FIT.toResizeMode()) // RESIZE_MODE_FIT
        assertEquals(3, VideoScaleState.ScaleMode.FILL.toResizeMode()) // RESIZE_MODE_FILL
        assertEquals(4, VideoScaleState.ScaleMode.CROP.toResizeMode()) // RESIZE_MODE_ZOOM
    }
}
