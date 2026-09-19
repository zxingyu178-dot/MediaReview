package com.mediareview.app.feature.v2.player.gesture

/**
 * 单击 / 双击决策状态。
 *
 * 运行时手势检测由 [androidx.compose.foundation.gestures.detectTapGestures] 完成，
 * 由平台保证"单击与双击互斥、双击不会先触发单击"；本状态负责可测试的逻辑部分：
 * - 双击区域判定（左 1/3 快退、右 1/3 快进、中央播放暂停）；
 * - 连续双击累计（+10s → +20s → +30s）；
 * - 模拟仲裁语义（[onUp]）供单测验证互斥。
 *
 * 纯逻辑，不依赖 Compose / 不依赖任何全局变量。
 */
class TapGestureState {

    enum class DoubleTapAction { SEEK_BACK, TOGGLE_PLAY_PAUSE, SEEK_FORWARD }

    enum class TapDecision { SINGLE_TAP, DOUBLE_TAP }

    companion object {
        /** 双击判定窗口（与 detectTapGestures 默认一致）。 */
        const val DOUBLE_TAP_WINDOW_MS = 300L
        /** 连续同向双击累计窗口。 */
        const val ACCUMULATE_WINDOW_MS = 700L
        /** 每次双击步进毫秒。 */
        const val STEP_MS = 10_000L
        /** 左 / 右区域阈值（x 比例）。 */
        const val LEFT_THRESHOLD = 0.33f
        const val RIGHT_THRESHOLD = 0.66f
    }

    /** 连续同向双击次数（1 起步）。 */
    var consecutiveCount = 0
        private set

    /** 上一次双击动作（中央播放暂停不累计）。 */
    var lastAction: DoubleTapAction? = null
        private set

    /** 上一次双击发生时间。 */
    var lastActionTime = 0L
        private set

    /** 双击区域判定：左 1/3 快退、右 1/3 快进、中央播放暂停。 */
    fun actionFor(xFraction: Float): DoubleTapAction = when {
        xFraction < LEFT_THRESHOLD -> DoubleTapAction.SEEK_BACK
        xFraction > RIGHT_THRESHOLD -> DoubleTapAction.SEEK_FORWARD
        else -> DoubleTapAction.TOGGLE_PLAY_PAUSE
    }

    /**
     * 记录一次双击。同方向且在累计窗口内则累计（10→20→30），否则重置为 1。
     * 中央播放暂停操作不参与累计。
     */
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

    /** 手势结束 / 提示消失 / 其他交互时重置累计。 */
    fun reset() {
        consecutiveCount = 0
        lastAction = null
        lastActionTime = 0L
    }

    /**
     * 一次手势仲裁（与 detectTapGestures 语义一致）：
     * 第一次抬手后，若在双击窗口内出现第二次抬手则判定 [TapDecision.DOUBLE_TAP]，
     * 否则判定 [TapDecision.SINGLE_TAP]。单测据此验证"单击与双击互斥"。
     */
    fun onUp(firstUpMs: Long, secondUpMs: Long): TapDecision {
        reset()
        return if (secondUpMs - firstUpMs <= DOUBLE_TAP_WINDOW_MS) {
            TapDecision.DOUBLE_TAP
        } else {
            TapDecision.SINGLE_TAP
        }
    }
}
