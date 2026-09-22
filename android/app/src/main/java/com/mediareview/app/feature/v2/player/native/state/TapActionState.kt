package com.mediareview.app.feature.v2.player.native.state

/**
 * 双击区域 + 连续累计状态（MediaReview 播放器交互逻辑）。
 * 区域判定：左 1/3 快退、右 1/3 快进、中央播放暂停（中央建议保留单击控制层，本状态不强制）。
 *
 * 连续双击累计采用"手势起始位置 + 累计增量"模型：
 * 每次同向连续双击保存第一次双击时的播放位置 [startPositionMs]，
 * 最终 seek 目标始终 = startPositionMs + accumulatedDeltaMs，
 * 避免"当前位置 + 增量"逐次叠加导致的真实偏移放大（+10 → +30 而非 +20）。
 */
class TapActionState {

    enum class DoubleTapAction { SEEK_BACK, TOGGLE_PLAY_PAUSE, SEEK_FORWARD }

    companion object {
        /** 连续同向双击累计窗口。 */
        const val ACCUMULATE_WINDOW_MS = 700L
        const val STEP_MS = 10_000L
        const val LEFT_THRESHOLD = 0.33f
        const val RIGHT_THRESHOLD = 0.66f
    }

    var consecutiveCount = 0
        private set

    var lastAction: DoubleTapAction? = null
        private set

    var lastActionTime = 0L
        private set

    /** 连续序列起始播放位置（第一次双击时的位置）。 */
    var startPositionMs = 0L
        private set

    /** 累计增量（带符号：正=快进，负=快退）。 */
    var accumulatedDeltaMs = 0L
        private set

    fun actionFor(xFraction: Float): DoubleTapAction = when {
        xFraction < LEFT_THRESHOLD -> DoubleTapAction.SEEK_BACK
        xFraction > RIGHT_THRESHOLD -> DoubleTapAction.SEEK_FORWARD
        else -> DoubleTapAction.TOGGLE_PLAY_PAUSE
    }

    /**
     * 记录一次双击：同向且在窗口内累计（10→20→30），否则重置为 1。
     * [currentPositionMs] 为本次双击发生时的播放位置，仅作为序列第一击的起始点。
     * 中央播放暂停不累计。
     */
    fun onDoubleTap(xFraction: Float, nowMs: Long, currentPositionMs: Long): DoubleTapAction {
        val action = actionFor(xFraction)
        if (action == DoubleTapAction.TOGGLE_PLAY_PAUSE) {
            reset()
            return action
        }
        if (lastAction == action && nowMs - lastActionTime <= ACCUMULATE_WINDOW_MS) {
            consecutiveCount += 1
        } else {
            consecutiveCount = 1
            startPositionMs = currentPositionMs
            accumulatedDeltaMs = 0L
        }
        lastAction = action
        lastActionTime = nowMs
        val step = if (action == DoubleTapAction.SEEK_FORWARD) STEP_MS else -STEP_MS
        accumulatedDeltaMs += step
        return action
    }

    /** 当前累计应跳转的秒数（绝对值：10 / 20 / 30 ...）。 */
    fun deltaSeconds(): Int = kotlin.math.abs((accumulatedDeltaMs / 1000L).toInt())

    /** 最终 seek 目标：序列起始位置 + 累计增量。 */
    fun targetPositionMs(): Long = startPositionMs + accumulatedDeltaMs

    fun reset() {
        consecutiveCount = 0
        lastAction = null
        lastActionTime = 0L
        startPositionMs = 0L
        accumulatedDeltaMs = 0L
    }
}