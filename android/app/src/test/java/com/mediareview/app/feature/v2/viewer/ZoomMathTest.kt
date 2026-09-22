package com.mediareview.app.feature.v2.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZoomMath：缩放 clamp、双击目标、Fit 尺寸、平移边界、焦点缩放、scale=1 归零。 */
class ZoomMathTest {

    @Test
    fun `缩放范围 clamp 在 1 到 5`() {
        assertEquals(1f, ZoomMath.clampScale(0.2f), 0.001f)
        assertEquals(2.5f, ZoomMath.clampScale(2.5f), 0.001f)
        assertEquals(5f, ZoomMath.clampScale(99f), 0.001f)
        assertEquals(1f, ZoomMath.clampScale(1f), 0.001f)
    }

    @Test
    fun `双击目标 1x 到 2_5x 再回 1x`() {
        assertEquals(2.5f, ZoomMath.doubleTapTarget(1f), 0.001f)
        assertEquals(1f, ZoomMath.doubleTapTarget(2.5f), 0.001f)
        assertEquals(1f, ZoomMath.doubleTapTarget(4f), 0.001f)
    }

    @Test
    fun `fitImageSize 按比例适配 viewport`() {
        // 横图 1280x720 放入 1080x1920 竖屏：宽度受限
        val size = ZoomMath.fitImageSize(1280, 720, Size(1080f, 1920f))
        assertEquals(1080f, size.width, 0.1f)
        assertEquals(607.5f, size.height, 0.1f)
        // 竖图 720x1280 放入 1080x1920：高度受限
        val p = ZoomMath.fitImageSize(720, 1280, Size(1080f, 1920f))
        assertEquals(1080f, p.width, 0.1f)
        assertEquals(1920f, p.height, 0.1f)
    }

    @Test
    fun `平移边界：放大 2 倍后不能拖出屏幕`() {
        val viewport = Size(1000f, 1000f)
        val image = Size(1000f, 500f)
        // scale=2 → 渲染 2000x1000，可平移 x ±500、y 0
        val clamped = ZoomMath.clampOffset(Offset(9999f, -9999f), viewport, image, 2f)
        assertEquals(500f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
        val clamped2 = ZoomMath.clampOffset(Offset(-9999f, 9999f), viewport, image, 2f)
        assertEquals(-500f, clamped2.x, 0.001f)
        assertEquals(0f, clamped2.y, 0.001f)
    }

    @Test
    fun `scale 1 时不允许任何平移`() {
        val clamped = ZoomMath.clampOffset(Offset(100f, 100f), Size(1000f, 1000f), Size(1000f, 500f), 1f)
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
    }

    @Test
    fun `zoomAround 保持焦点下的图片点不动（默认原点）`() {
        val focal = Offset(300f, 400f)
        val offset = Offset(50f, 60f)
        val k = 2f
        val newOffset = ZoomMath.zoomAround(offset, focal, k)
        // 焦点处图片坐标 (p - offset)/scale 缩放前后一致：
        // (focal - offset) == (focal - newOffset) / k
        assertEquals((focal.x - offset.x) * k, focal.x - newOffset.x, 0.001f)
        assertEquals((focal.y - offset.y) * k, focal.y - newOffset.y, 0.001f)
    }

    @Test
    fun `zoomAround 支持中心 TransformOrigin 焦点模型`() {
        // 与 graphicsLayer 默认 TransformOrigin.Center 对齐：
        // t' = (focal - origin) - (focal - origin - t) * k
        val origin = Offset(540f, 960f)
        val focal = Offset(300f, 400f)
        val offset = Offset(50f, 60f)
        val k = 2f
        val newOffset = ZoomMath.zoomAround(offset, focal, k, origin)
        assertEquals(
            (focal.x - origin.x) - (focal.x - origin.x - offset.x) * k,
            newOffset.x, 0.001f,
        )
        assertEquals(
            (focal.y - origin.y) - (focal.y - origin.y - offset.y) * k,
            newOffset.y, 0.001f,
        )
    }

    @Test
    fun `scale 回到 1 时 offset 归零`() {
        assertEquals(Offset.Zero, ZoomMath.resetOffsetIfScaleOne(1f, Offset(300f, 200f)))
        assertEquals(Offset.Zero, ZoomMath.resetOffsetIfScaleOne(0.999f, Offset(300f, 200f)))
        val keep = ZoomMath.resetOffsetIfScaleOne(2f, Offset(300f, 200f))
        assertEquals(300f, keep.x, 0.001f)
    }

    @Test
    fun `fitImageSize 对非法输入安全`() {
        val size = ZoomMath.fitImageSize(0, 0, Size(1080f, 1920f))
        assertTrue(size.width > 0f)
        assertEquals(1080f, size.width, 0.001f)
    }
}
