package com.mediareview.app.feature.v2.player.native.state

import com.mediareview.app.feature.v2.player.native.state.BackNavigationLogic.BackAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** BackNavigationLogic：Sheet 优先关闭，其次退出全屏，最后退出播放器。 */
class BackNavigationLogicTest {

    @Test
    fun `Sheet 打开时 Back 先关 Sheet`() {
        assertEquals(
            BackAction.DISMISS_SHEET,
            BackNavigationLogic.resolveBackAction(sheetOpen = true, isFullscreen = true),
        )
    }

    @Test
    fun `全屏时 Back 退出全屏`() {
        assertEquals(
            BackAction.EXIT_FULLSCREEN,
            BackNavigationLogic.resolveBackAction(sheetOpen = false, isFullscreen = true),
        )
    }

    @Test
    fun `普通状态 Back 退出播放器`() {
        assertEquals(
            BackAction.EXIT_PLAYER,
            BackNavigationLogic.resolveBackAction(sheetOpen = false, isFullscreen = false),
        )
    }
}
