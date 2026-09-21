package com.mediareview.app.feature.v2.player.native.state

/**
 * 双击区域 + 连续累计状态（MediaReview 播放器交互逻辑）。
 * 区域判定：左 1/3 快退、右 1/3 快进、中央播放暂停（中央建议保留单击控制层，本状态不强制）。
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

    fun actionFor(xFraction: Float): DoubleTapAction = when {
        xFraction < LEFT_THRESHOLD -> DoubleTapAction.SEEK_BACK
        xFraction > RIGHT_THRESHOLD -> DoubleTapAction.SEEK_FORWARD
        else -> DoubleTapAction.TOGGLE_PLAY_PAUSE
    }

    /** 记录一次双击：同向且在窗口内累计（10→20→30），否则重置为 1。中央播放暂停不累计。 */
    fun onDoubleTap(xFraction: Float, nowMs: Long): DoubleTapAction {
        val action = actionFor(xFraction)
        if (action == DoubleTapAction.TOGGLE_PLAY_PAUSE) {
            reset()
            return action
        }
        if (lastAction == action && nowMs - lastActionTime <= ACCUMULATE_WINDOW_MS) {
            consecutiveCount += 1
        } else {
            consecutiveCount = 1
        }
        lastAction = action
        lastActionTime = nowMs
        return action
    }

    /** 当前累计应跳转的秒数（10 / 20 / 30 ...）。 */
    fun deltaSeconds(): Int = consecutiveCount * (STEP_MS / 1000L).toInt()

    fun reset() {
        consecutiveCount = 0
        lastAction = null
        lastActionTime = 0L
    }
}
