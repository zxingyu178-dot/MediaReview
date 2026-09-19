package com.mediareview.app.feature.v2.player.state

/**
 * 横竖屏旋转状态。
 *
 * 进入播放器时记录进入前方向（savedOrientation）；点击旋转按钮切换 SENSOR_LANDSCAPE /
 * SENSOR_PORTRAIT；离开播放器时恢复进入前方向（幂等）。
 * 状态本身不持有 Activity，方向值通过 [restore] 返回，由外层桥接
 * Activity.requestedOrientation，纯逻辑可单测。
 */
class RotationState {

    enum class Orientation { PORTRAIT, LANDSCAPE }

    var orientation = Orientation.PORTRAIT
        private set

    /** 进入播放器前的方向。 */
    var savedOrientation: Int? = null
        private set

    /** 已恢复标记（幂等）。 */
    var restored = false
        private set

    /** 进入播放器：记录进入前方向（仅首次有效）。 */
    fun initialize(currentOrientation: Int) {
        if (savedOrientation == null) {
            savedOrientation = currentOrientation
            restored = false
        }
    }

    /** 点击旋转按钮：切换横 / 竖屏。 */
    fun toggle() {
        orientation = if (orientation == Orientation.PORTRAIT) Orientation.LANDSCAPE else Orientation.PORTRAIT
        restored = false
    }

    /** 当前方向对应的 ActivityInfo 常量（SENSOR_LANDSCAPE=6 / SENSOR_PORTRAIT=7）。 */
    fun requestedCode(): Int = if (orientation == Orientation.LANDSCAPE) 6 else 7

    /** 离开播放器：返回需要恢复的进入前方向；已恢复过返回 null（幂等）。 */
    fun restore(): Int? {
        if (restored) return null
        restored = true
        return savedOrientation
    }
}
