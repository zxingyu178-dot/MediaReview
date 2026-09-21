package com.mediareview.app.feature.v2.player.native.state

/**
 * 倍速状态：用户正式倍速 + 长按临时 2x。
 * 长按只对播放中生效；松手恢复用户原倍速，不覆盖正式选择。
 */
class SpeedState {

    companion object {
        const val TEMP_SPEED = 2.0f
    }

    var userSpeed = 1.0f
        private set

    var tempActive = false
        private set

    /** 当前生效倍速（临时 2x 或用户正式倍速）。 */
    val effectiveSpeed: Float
        get() = if (tempActive) TEMP_SPEED else userSpeed

    fun setSpeed(speed: Float) {
        userSpeed = speed
    }

    /** 长按开始：仅播放中生效。 */
    fun startTempSpeed(playing: Boolean) {
        if (!playing) return
        tempActive = true
    }

    /** 长按结束：恢复用户原倍速。 */
    fun endTempSpeed() {
        tempActive = false
    }
}
