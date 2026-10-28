package com.mediareview.app.feature.v2.nsfw

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.V2BottomNavBar
import com.mediareview.app.feature.v2.home.V2MainTab
import com.mediareview.app.ui.theme.MediaReviewTheme
import org.junit.Rule
import org.junit.Test

/**
 * 五入口在常见手机宽度（360dp）下都能显示。
 *
 * 需要模拟器/真机执行；没有设备时如实记录为"未执行"。
 */
class NsfwBottomNavFiveTabsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fiveTabsVisibleAtNarrowWidth() {
        compose.setContent {
            MediaReviewTheme {
                Box(Modifier.width(360.dp)) {
                    V2BottomNavBar(current = V2MainTab.HOME, onSelect = {})
                }
            }
        }
        listOf("首页", "批阅", "生图审核", "收藏", "整理").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
