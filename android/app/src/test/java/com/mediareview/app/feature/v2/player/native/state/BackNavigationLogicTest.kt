package com.mediareview.app.feature.v2.player.native.state

import com.mediareview.app.feature.v2.player.native.state.BackNavigationLogic.BackAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** BackNavigationLogic：全屏中 Back 必须先退出全屏。 */
class BackNavigationLogicTest {

    @Test
    fun `全屏时 Back 退出全屏`() {
        assertEquals(BackAction.EXIT_FULLSCREEN, BackNavigationLogic.resolveBackAction(isFullscreen = true))
    }

    @Test
    fun `非全屏时 Back 退出播放器`() {
        assertEquals(BackAction.EXIT_PLAYER, BackNavigationLogic.resolveBackAction(isFullscreen = false))
    }
}
