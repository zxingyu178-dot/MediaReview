package com.mediareview.app.feature.v2.nsfw

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mediareview.app.feature.v2.MediaNavigator
import com.mediareview.app.feature.v2.home.V2BottomNavBar
import com.mediareview.app.feature.v2.home.V2MainTab
import com.mediareview.app.ui.theme.MediaReviewTheme
import org.junit.Rule
import org.junit.Test

/**
 * 五个一级入口的导航状态恢复：与 V2MainScreen 相同的 saveState / restoreState 选项。
 * 需要模拟器/真机执行。
 */
class NsfwBottomNavStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun routeStateRestoredWhenSwitchingTabs() {
        compose.setContent {
            MediaReviewTheme {
                val nav = rememberNavController()
                Column {
                    NavHost(navController = nav, startDestination = MediaNavigator.ROUTE_HOME) {
                        composable(MediaNavigator.ROUTE_HOME) {
                            var count by rememberSaveable { mutableStateOf(0) }
                            Button(onClick = { count += 1 }) { Text("计数:$count") }
                        }
                        composable(MediaNavigator.ROUTE_NSFW_REVIEW) {
                            Text("生图审核页")
                        }
                    }
                    V2BottomNavBar(
                        current = V2MainTab.HOME,
                        onSelect = { tab ->
                            nav.navigate(MediaNavigator.tabRoute(tab)) {
                                popUpTo(MediaNavigator.ROUTE_HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("计数:0").assertIsDisplayed()
        compose.onNodeWithText("计数:0").performClick()
        compose.onNodeWithText("计数:1").assertIsDisplayed()

        compose.onNodeWithText("生图审核").performClick()
        compose.onNodeWithText("生图审核页").assertIsDisplayed()

        compose.onNodeWithText("首页").performClick()
        compose.onNodeWithText("计数:1").assertIsDisplayed()
    }
}
