package com.mediareview.app.feature.v2.player.native.state

/**
 * 倍速状态（Stage 2.2.1 收敛版）。
 *
 * 真实生效倍速唯一来源是 GSY controller.snapshot.speed；本类不再维护
 * effectiveSpeed 这类"第二份永久倍速"。只保留：
 *
 * - [expectedSpeed]：用户正式选择的倍速，用于切源 / 重新 Prepared 后 GSY 倍速被
 *   内核重置时恢复（稳态下与 snapshot.speed 一致）；
 * - 长按临时 2x：开始时快照当前实际倍速，松手恢复，不覆盖正式选择。
 */
class SpeedState {

    companion object {
        const val TEMP_SPEED = 2.0f
    }

    /** 用户正式选择的倍速（切源恢复用），UI 选中态仍以 snapshot.speed 为准。 */
    var expectedSpeed = 1.0f
        private set

    var tempActive = false
        private set

    /** 长按开始前的实际倍速，松手恢复目标。 */
    private var restoreSpeed = 1.0f

    fun setExpected(speed: Float) {
        expectedSpeed = speed
    }

    /** 长按开始：仅播放中生效；快照当前实际倍速。 */
    fun startTemp(currentSpeed: Float, playing: Boolean) {
        if (!playing) return
        restoreSpeed = currentSpeed
        tempActive = true
    }

    /** 长按结束：返回应恢复的倍速（临时前的实际倍速）。 */
    fun endTemp(): Float {
        if (tempActive) tempActive = false
        return restoreSpeed
    }
}
