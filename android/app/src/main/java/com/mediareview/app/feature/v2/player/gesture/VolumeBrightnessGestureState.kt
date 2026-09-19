package com.mediareview.app.feature.v2.player.gesture

/**
 * 竖向拖拽音量 / 亮度状态。
 *
 * 拖动开始记录起始值快照（startVolumePct / startBrightnessPct）；
 * 拖动过程按 (startY - currentY) / height × 100% 统一映射，快 / 慢滑动都稳定；
 * 结果始终 clamp 到 0..100。左半屏=亮度、右半屏=音量由外层手势层判定。
 */
class VolumeBrightnessGestureState {

    var isActive = false
        private set

    var startY = 0f
        private set

    var startVolumePct = 0f
        private set

    var startBrightnessPct = 0f
        private set

    var currentVolumePct = 0f
        private set

    var currentBrightnessPct = 0f
        private set

    fun onStart(startY: Float, volumePct: Float, brightnessPct: Float) {
        isActive = true
        this.startY = startY
        this.startVolumePct = volumePct.coerceIn(0f, 100f)
        this.startBrightnessPct = brightnessPct.coerceIn(0f, 100f)
        this.currentVolumePct = this.startVolumePct
        this.currentBrightnessPct = this.startBrightnessPct
    }

    /** currentY 为当前手指 Y；向上滑动（currentY < startY）为增大。 */
    fun onDrag(currentY: Float, heightPx: Float) {
        if (!isActive) return
        val h = if (heightPx > 0f) heightPx else 1f
        val deltaPct = (startY - currentY) / h * 100f
        currentVolumePct = (startVolumePct + deltaPct).coerceIn(0f, 100f)
        currentBrightnessPct = (startBrightnessPct + deltaPct).coerceIn(0f, 100f)
    }

    fun onEnd() {
        isActive = false
    }

    fun onCancel() {
        isActive = false
    }
}
