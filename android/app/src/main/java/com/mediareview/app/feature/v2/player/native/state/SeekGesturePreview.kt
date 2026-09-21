package com.mediareview.app.feature.v2.player.native.state

/**
 * 横向拖拽 Seek 预览状态（MediaReview UI 逻辑）。
 *
 * GSY 原生 gsyGestureControl 的横向映射为"1 屏宽 = 整段时长"，对 60 分钟长视频
 * 稍微一滑就跳几分钟，不符合 MediaReview 手感要求。这里按 Next Player 思路：
 * 整宽映射 span = min(max(duration, 30s), 90s)，短视频与长视频都不过敏；
 * 松手后仍通过 GSY controller.seekTo 提交（不重造播放内核）。
 */
class SeekGesturePreview {

    companion object {
        const val MIN_SPAN_MS = 30_000L
        const val MAX_SPAN_MS = 90_000L

        /** 整宽映射的时间跨度：短视频向下夹到 30s，长视频向上夹到 90s。 */
        fun spanFor(durationMs: Long): Long = durationMs.coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
    }

    var isActive = false
        private set

    var startPositionMs = 0L
        private set

    var startX = 0f
        private set

    var widthPx = 1f
        private set

    /** 当前预览目标位置（已 clamp 0..duration）。 */
    var targetMs = 0L
        private set

    /** 相对起始位置增量（正=前进，负=后退），用于 +00:18 / -00:12 提示。 */
    var deltaMs = 0L
        private set

    fun onStart(positionMs: Long, startX: Float, widthPx: Float) {
        isActive = true
        this.startPositionMs = positionMs
        this.startX = startX
        this.widthPx = if (widthPx > 0f) widthPx else 1f
        this.targetMs = positionMs
        this.deltaMs = 0L
    }

    fun onDrag(currentX: Float, durationMs: Long) {
        if (!isActive) return
        val duration = durationMs.coerceAtLeast(0L)
        val ratio = (currentX - startX) / widthPx
        val span = spanFor(duration)
        val target = (startPositionMs + (ratio * span).toLong()).coerceIn(0L, duration)
        targetMs = target
        deltaMs = targetMs - startPositionMs
    }

    /** 松手：返回最终提交位置并结束。 */
    fun onEnd(): Long? {
        if (!isActive) return null
        isActive = false
        return targetMs
    }

    /** 取消：放弃本次拖拽。 */
    fun onCancel() {
        isActive = false
        targetMs = 0L
        deltaMs = 0L
    }
}
