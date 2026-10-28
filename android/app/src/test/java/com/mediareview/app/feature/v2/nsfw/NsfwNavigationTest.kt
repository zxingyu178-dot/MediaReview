package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.MediaNavigator
import com.mediareview.app.feature.v2.home.V2MainTab
import com.mediareview.app.feature.v2.home.V2_MAIN_TAB_ITEMS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 五个一级入口的顺序、文案与路由映射（JVM 级；布局真机测试见 androidTest）。 */
class NsfwNavigationTest {

    @Test
    fun `五个底部入口顺序与文案固定`() {
        assertEquals(
            listOf("首页", "批阅", "生图审核", "收藏", "整理"),
            V2_MAIN_TAB_ITEMS.map { it.second },
        )
        assertEquals(5, V2MainTab.entries.size)
    }

    @Test
    fun `生图审核与批阅并列且路由唯一`() {
        val tabs = V2_MAIN_TAB_ITEMS.map { it.first }
        assertEquals(listOf(V2MainTab.HOME, V2MainTab.REVIEW, V2MainTab.NSFW_REVIEW, V2MainTab.FAVORITES, V2MainTab.ORGANIZE), tabs)
        val routes = tabs.map { MediaNavigator.tabRoute(it) }
        assertEquals(routes.size, routes.toSet().size)
        assertEquals("nsfw-review", MediaNavigator.ROUTE_NSFW_REVIEW)
        assertEquals("review", MediaNavigator.ROUTE_REVIEW)
    }

    @Test
    fun `每个一级入口都被识别为TabRoute`() {
        V2_MAIN_TAB_ITEMS.forEach { (tab, _, _) ->
            assertTrue(MediaNavigator.isTabRoute(MediaNavigator.tabRoute(tab)))
        }
        assertTrue(!MediaNavigator.isTabRoute(MediaNavigator.ROUTE_PLAYER))
        assertTrue(!MediaNavigator.isTabRoute(MediaNavigator.ROUTE_VIEWER))
    }
}
