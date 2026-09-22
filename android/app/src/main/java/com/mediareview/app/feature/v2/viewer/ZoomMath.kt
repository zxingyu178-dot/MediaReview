package com.mediareview.app.feature.v2.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * 缩放 / 平移纯数学（可单测）。
 *
 * 模型：图片以 ContentScale.Fit 放入 viewport，再施加 graphicsLayer
 * scale + translation。graphicsLayer 使用默认中心 TransformOrigin，
 * 缩放围绕层中心 [origin]、再叠加 translation，因此焦点缩放公式为：
 *   p' = (p - origin) * k + origin + t
 * 以焦点 f 缩放 k 倍时保持图片坐标不变：
 *   t' = (f - origin) - (f - origin - t) * k
 */
object ZoomMath {

    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** 缩放范围 1.0x ~ 5.0x。 */
    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** 双击目标：1.0x ↔ 2.5x。 */
    fun doubleTapTarget(currentScale: Float): Float =
        if (currentScale > 1f) MIN_SCALE else DOUBLE_TAP_SCALE

    /** ContentScale.Fit 下图片在 viewport 内的渲染尺寸。 */
    fun fitImageSize(naturalWidth: Int, naturalHeight: Int, viewport: Size): Size {
        if (naturalWidth <= 0 || naturalHeight <= 0 || viewport.width <= 0f || viewport.height <= 0f) return viewport
        val scale = minOf(viewport.width / naturalWidth, viewport.height / naturalHeight)
        return Size(naturalWidth * scale, naturalHeight * scale)
    }

    /**
     * 平移边界：图片放大后不能被拖到完全离开屏幕。
     * 允许的最大平移 = (渲染尺寸 - viewport) / 2（不足时归 0）。以图片中心与视口中心重合为原点。
     */
    fun clampOffset(offset: Offset, viewport: Size, image: Size, scale: Float): Offset {
        val scaledW = image.width * scale
        val scaledH = image.height * scale
        val maxX = ((scaledW - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((scaledH - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(
            offset.x.coerceIn(-maxX, maxX),
            offset.y.coerceIn(-maxY, maxY),
        )
    }

    /**
     * 以层中心 [origin] 为 TransformOrigin、焦点 [focal] 放大 [k] 倍（k = 新scale/旧scale）。
     * 与 graphicsLayer(transformOrigin = TransformOrigin(0.5f, 0.5f)) 的坐标模型一致：
     *   t' = (focal - origin) - (focal - origin - t) * k
     */
    fun zoomAround(offset: Offset, focal: Offset, k: Float, origin: Offset = Offset.Zero): Offset {
        val rel = focal - origin
        return Offset(
            x = rel.x - (rel.x - offset.x) * k,
            y = rel.y - (rel.y - offset.y) * k,
        )
    }

    /** scale 回到 1.0 时 offset 必须归零。 */
    fun resetOffsetIfScaleOne(scale: Float, offset: Offset): Offset =
        if (scale <= MIN_SCALE + 0.001f) Offset.Zero else offset
}