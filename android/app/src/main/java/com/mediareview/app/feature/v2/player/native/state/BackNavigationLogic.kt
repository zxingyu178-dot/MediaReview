package com.mediareview.app.feature.v2.player.native.state

/**
 * Back 键 / 顶部返回按钮统一决策（纯逻辑）。
 *
 * Stage 2.2.1：顶部返回与 Android Back 必须走同一套逻辑，优先级：
 * 1. 底部 Sheet 打开 → 先关 Sheet；
 * 2. 全屏中 → 先退出全屏；
 * 3. 否则 → 退出播放器。
 */
object BackNavigationLogic {

    enum class BackAction { DISMISS_SHEET, EXIT_FULLSCREEN, EXIT_PLAYER }

    fun resolveBackAction(sheetOpen: Boolean, isFullscreen: Boolean): BackAction = when {
        sheetOpen -> BackAction.DISMISS_SHEET
        isFullscreen -> BackAction.EXIT_FULLSCREEN
        else -> BackAction.EXIT_PLAYER
    }
}
