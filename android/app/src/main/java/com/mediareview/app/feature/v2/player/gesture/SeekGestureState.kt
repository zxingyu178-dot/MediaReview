package com.mediareview.app.feature.v2.player.gesture

/**
 * 横向拖拽 Seek 状态。
 *
 * 基于"拖动开始快照"（startPositionMs / startX），拖动过程用
 * (currentX - startX) × 灵敏度映射目标位置，不做逐帧累加（不再是 1px = 500ms）。
 * 灵敏度随视频时长自适应：整宽映射 span = min(max(duration, 30s), 90s)，
 * 短视频与长视频都不会过敏。
 */
class SeekGestureState {

    companion object {
        const val MIN_SPAN_MS = 30_000L
        const val MAX_SPAN_MS = 90_000L
        /** 完整宽度映射的时间跨度：短视频向下夹到 30s，长视频向上夹到 90s。 */
        fun effectiveSpanMs(durationMs: Long): Long = durationMs.coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
    }

    var isActive = false
        private set

    var startPositionMs = 0L
        private set

    var startX = 0f
        private set

    var widthPx = 1f
        private set

    /** 当前拖拽目标位置（已 clamp 到 0..duration）。 */
    var currentTargetMs = 0L
        private set

    /** 相对起始位置的增量（正=前进，负=后退），用于 +00:15 / -00:12 提示。 */
    var deltaMs = 0L
        private set

    fun onStart(positionMs: Long, startX: Float, widthPx: Float) {
        isActive = true
        this.startPositionMs = positionMs
        this.startX = startX
        this.widthPx = if (widthPx > 0f) widthPx else 1f
        this.currentTargetMs = positionMs
        this.deltaMs = 0L
    }

    fun onDrag(currentX: Float, durationMs: Long) {
        if (!isActive) return
        val ratio = (currentX - startX) / widthPx
        val spanMs = effectiveSpanMs(durationMs.coerceAtLeast(0L))
        val target = (startPositionMs + (ratio * spanMs).toLong()).coerceIn(0L, durationMs.coerceAtLeast(0L))
        currentTargetMs = target
        deltaMs = currentTargetMs - startPositionMs
    }

    /** 松手：结束拖拽并返回最终提交位置。 */
    fun onEnd(): Long {
        isActive = false
        return currentTargetMs
    }

    /** 手势取消：放弃本次拖拽，不提交 Seek 并清空预览。 */
    fun onCancel() {
        isActive = false
        currentTargetMs = 0L
        deltaMs = 0L
    }

    fun reset() {
        onCancel()
    }
}
