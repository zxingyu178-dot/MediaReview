package com.mediareview.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mediareview.app.feature.connect.ConnectDestinations
import com.mediareview.app.feature.connect.replaceConnectWithMainShell
import com.mediareview.app.feature.player.PlayerTextMenuButton
import com.mediareview.app.feature.settings.SettingsDestinations
import com.mediareview.app.feature.settings.replaceShellWithConnect
import com.mediareview.app.ui.components.MediaEmptyState
import com.mediareview.app.ui.components.MediaOfflineState
import com.mediareview.app.ui.components.SyncStatusBanner
import com.mediareview.app.ui.theme.MediaReviewTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse

class MainShellComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottomBarExposesFourChineseDestinationsAndSelectedState() {
        compose.setContent {
            MediaReviewTheme {
                MainBottomBar(selectedRoot = MainRoot.Media, onSelect = {})
            }
        }

        compose.onNodeWithContentDescription("媒体导航").assertIsSelected().assertHasClickAction()
        compose.onNodeWithContentDescription("批阅导航").assertHasClickAction()
        compose.onNodeWithContentDescription("收藏导航").assertHasClickAction()
        compose.onNodeWithContentDescription("整理导航").assertHasClickAction()
    }

    @Test
    fun keyStatesHaveReadableActionsAndNonColorStatusText() {
        compose.setContent {
            MediaReviewTheme {
                Column {
                    MediaOfflineState(message = "服务器暂时离线", onRetry = {})
                    MediaEmptyState(title = "暂无媒体", message = "请先选择媒体库")
                    SyncStatusBanner(text = "媒体正在同步")
                }
            }
        }
        compose.onNodeWithText("服务器暂时离线").assertExists()
        compose.onNodeWithText("重试").assertHasClickAction()
        compose.onNodeWithText("暂无媒体").assertExists()
        compose.onNodeWithText("请先选择媒体库").assertExists()
        compose.onNodeWithText("媒体正在同步").assertExists()
    }

    @Test
    fun clickingRootsChangesRealShellContentAndRestoresSaveableState() {
        compose.setContent {
            MediaReviewTheme {
                var selectedName by rememberSaveable { mutableStateOf(MainRoot.Media.name) }
                val selected = MainRoot.valueOf(selectedName)
                MainShellScaffold(
                    selectedRoot = selected,
                    banner = null,
                    onSelect = { selectedName = it.name },
                    onOpenSettings = {},
                ) {
                    MainRootStateHost(selected) { root ->
                        var count by rememberSaveable { mutableIntStateOf(0) }
                        Button(onClick = { count += 1 }) { Text("${root.label}计数 $count") }
                    }
                }
            }
        }

        compose.onNodeWithText("媒体计数 0").performClick()
        compose.onNodeWithContentDescription("批阅导航").performClick()
        compose.onNodeWithText("批阅计数 0").assertExists()
        compose.onNodeWithContentDescription("媒体导航").performClick()
        compose.onNodeWithText("媒体计数 1").assertExists()
    }

    @Test
    fun settingsRouteHasNoMainChrome() {
        compose.setContent {
            MediaReviewTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = MainShellDestinations.ROUTE) {
                    composable(MainShellDestinations.ROUTE) {
                        MainShellScaffold(
                            selectedRoot = MainRoot.Media,
                            banner = null,
                            onSelect = {},
                            onOpenSettings = { navController.navigate(SettingsDestinations.ROUTE) },
                        ) { Box {} }
                    }
                    composable(SettingsDestinations.ROUTE) { Text("设置全屏") }
                }
            }
        }

        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("设置全屏").assertExists()
        compose.onAllNodesWithContentDescription("媒体导航").assertCountEquals(0)
    }

    @Test
    fun reconnectCycleLeavesOnlyOneMainShellBackStackEntry() {
        var canPopAfterPairing: Boolean? = null
        compose.setContent {
            MediaReviewTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = MainShellDestinations.ROUTE) {
                    composable(MainShellDestinations.ROUTE) {
                        Button(onClick = { navController.navigate(SettingsDestinations.ROUTE) }) {
                            Text("进入设置")
                        }
                    }
                    composable(SettingsDestinations.ROUTE) {
                        Button(onClick = navController::replaceShellWithConnect) { Text("重新连接") }
                    }
                    composable(ConnectDestinations.CONNECT) {
                        Button(onClick = {
                            navController.replaceConnectWithMainShell()
                            canPopAfterPairing = navController.popBackStack()
                        }) { Text("配对成功") }
                    }
                }
            }
        }

        compose.onNodeWithText("进入设置").performClick()
        compose.onNodeWithText("重新连接").performClick()
        compose.onNodeWithText("配对成功").performClick()
        compose.runOnIdle { assertFalse(canPopAfterPairing ?: true) }
    }

    @Test
    fun playerTextControlMeetsMinimumTouchTarget() {
        compose.setContent {
            MediaReviewTheme { PlayerTextMenuButton(label = "1.0x", onClick = {}) }
        }

        compose.onNodeWithText("1.0x")
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}
