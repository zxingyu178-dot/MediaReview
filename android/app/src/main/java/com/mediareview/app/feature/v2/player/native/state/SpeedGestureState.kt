package com.mediareview.app.feature.v2.player.native.state

enum class SpeedGestureMode { Normal, Temp2x, Locked2x }

data class SpeedGestureChange(val speed: Float, val feedback: String? = null, val haptic: Boolean = false)

/** Full / Review 共用倍速状态机；正式选择与临时/锁定效果分别建模。 */
class SpeedGestureState(initialSpeed: Float = 1f) {
    companion object {
        val FORMAL_SPEEDS = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
        const val LONG_PRESS_MS = 350L
        const val LOCK_DISTANCE_DP = 48f
    }

    var formalSpeed: Float = initialSpeed
        private set
    var mode: SpeedGestureMode = SpeedGestureMode.Normal
        private set
    var ownsPointerSequence: Boolean = false
        private set
    val effectiveSpeed: Float get() = if (mode == SpeedGestureMode.Normal) formalSpeed else 2f

    init { require(initialSpeed in FORMAL_SPEEDS) }

    fun longPress(playing: Boolean): SpeedGestureChange? {
        if (ownsPointerSequence) return null
        if (mode == SpeedGestureMode.Locked2x) {
            mode = SpeedGestureMode.Normal
            ownsPointerSequence = true
            return SpeedGestureChange(formalSpeed, "已恢复 ${formalSpeed}×", haptic = true)
        }
        if (!playing) return null
        ownsPointerSequence = true
        mode = SpeedGestureMode.Temp2x
        return SpeedGestureChange(2f)
    }

    fun dragUp(distanceDp: Float): SpeedGestureChange? {
        if (!ownsPointerSequence || mode != SpeedGestureMode.Temp2x || distanceDp < LOCK_DISTANCE_DP) return null
        mode = SpeedGestureMode.Locked2x
        return SpeedGestureChange(2f, "2.0× 已锁定", haptic = true)
    }

    fun release(): SpeedGestureChange {
        ownsPointerSequence = false
        if (mode == SpeedGestureMode.Temp2x) mode = SpeedGestureMode.Normal
        return SpeedGestureChange(effectiveSpeed)
    }

    /** 切源、锁屏控制或手势取消时恢复正式选择，不把临时效果保存为偏好。 */
    fun cancel(): SpeedGestureChange {
        mode = SpeedGestureMode.Normal
        ownsPointerSequence = false
        return SpeedGestureChange(formalSpeed)
    }

    fun formalSpeedChanged(speed: Float): SpeedGestureChange {
        require(speed in FORMAL_SPEEDS)
        formalSpeed = speed
        return cancel()
    }
}
