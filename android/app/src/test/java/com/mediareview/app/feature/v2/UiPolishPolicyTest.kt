package com.mediareview.app.feature.v2

import com.mediareview.app.feature.v2.ui.components.UiErrorKind
import com.mediareview.app.feature.v2.ui.components.classifyUiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPolishPolicyTest {
    @Test fun `immersive routes hide bottom navigation while ordinary tabs retain it`() {
        assertTrue(shouldShowBottomNavigation(MediaNavigator.ROUTE_HOME))
        assertTrue(shouldShowBottomNavigation(MediaNavigator.ROUTE_FAVORITES))
        assertFalse(shouldShowBottomNavigation(MediaNavigator.ROUTE_REVIEW))
        assertFalse(shouldShowBottomNavigation(MediaNavigator.ROUTE_VIEWER))
        assertFalse(shouldShowBottomNavigation(MediaNavigator.ROUTE_ALBUM))
    }

    @Test fun `review releases bottom navigation inset before native surface is composed`() {
        assertEquals(
            BottomNavigationExitMode.IMMEDIATE,
            bottomNavigationExitMode(MediaNavigator.ROUTE_REVIEW),
        )
        assertEquals(
            BottomNavigationExitMode.ANIMATED,
            bottomNavigationExitMode(MediaNavigator.ROUTE_PLAYER),
        )
        assertEquals(
            BottomNavigationExitMode.ANIMATED,
            bottomNavigationExitMode(MediaNavigator.ROUTE_HOME),
        )
    }

    @Test fun `ui errors distinguish network source and permission failures`() {
        assertEquals(UiErrorKind.NETWORK, classifyUiError("网络连接超时，请稍后再试"))
        assertEquals(UiErrorKind.SOURCE, classifyUiError("媒体源不可用"))
        assertEquals(UiErrorKind.PERMISSION, classifyUiError("没有访问权限"))
        assertEquals(UiErrorKind.UNKNOWN, classifyUiError("未知异常"))
    }
}
