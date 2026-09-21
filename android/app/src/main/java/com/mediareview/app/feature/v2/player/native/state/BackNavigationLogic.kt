package com.mediareview.app.feature.v2.player.native.state

/**
 * Back 键优先级决策（纯逻辑）。
 * 全屏中 Back 必须先退出全屏；非全屏 Back 才退出播放器。
 */
object BackNavigationLogic {

    enum class BackAction { EXIT_FULLSCREEN, EXIT_PLAYER }

    fun resolveBackAction(isFullscreen: Boolean): BackAction =
        if (isFullscreen) BackAction.EXIT_FULLSCREEN else BackAction.EXIT_PLAYER
}
