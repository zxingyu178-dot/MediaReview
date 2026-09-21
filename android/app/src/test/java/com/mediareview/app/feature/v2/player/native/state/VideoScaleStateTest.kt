package com.mediareview.app.feature.v2.player.native.state

import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import org.junit.Assert.assertEquals
import org.junit.Test

/** VideoScaleState：比例模式与 GSY SCREEN_TYPE 映射。 */
class VideoScaleStateTest {

    @Test
    fun `初始为适应`() {
        assertEquals(VideoScaleState.ScaleMode.FIT, VideoScaleState().mode)
    }

    @Test
    fun `映射 GSY SCREEN_TYPE 常量`() {
        assertEquals(GSYVideoType.SCREEN_TYPE_DEFAULT, VideoScaleState.ScaleMode.FIT.gsyShowType)
        assertEquals(GSYVideoType.SCREEN_TYPE_FULL, VideoScaleState.ScaleMode.CROP.gsyShowType)
        assertEquals(GSYVideoType.SCREEN_MATCH_FULL, VideoScaleState.ScaleMode.FILL.gsyShowType)
        assertEquals(GSYVideoType.SCREEN_TYPE_16_9, VideoScaleState.ScaleMode.R16_9.gsyShowType)
        assertEquals(GSYVideoType.SCREEN_TYPE_4_3, VideoScaleState.ScaleMode.R4_3.gsyShowType)
    }

    @Test
    fun `set 切换比例`() {
        val state = VideoScaleState()
        state.set(VideoScaleState.ScaleMode.R16_9)
        assertEquals(VideoScaleState.ScaleMode.R16_9, state.mode)
    }

    @Test
    fun `标签中文`() {
        assertEquals("适应", VideoScaleState.ScaleMode.FIT.label)
        assertEquals("裁剪", VideoScaleState.ScaleMode.CROP.label)
        assertEquals("填充", VideoScaleState.ScaleMode.FILL.label)
    }
}
