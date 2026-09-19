package com.mediareview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.mediareview.app.feature.connect.ConnectDestinations
import com.mediareview.app.feature.connect.connectGraph
import com.mediareview.app.feature.deletequeue.deleteQueueGraph
import com.mediareview.app.feature.duplicates.duplicatesGraph
import com.mediareview.app.feature.library.libraryGraph
import com.mediareview.app.feature.player.playerGraph
import com.mediareview.app.feature.settings.settingsGraph
import com.mediareview.app.feature.v2.AppMode
import com.mediareview.app.feature.v2.V2AppMode
import com.mediareview.app.feature.v2.V2MainScreen
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.viewer.imageViewerGraph
import com.mediareview.app.ui.shell.mainShellGraph
import com.mediareview.app.ui.theme.MediaReviewTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaReviewTheme {
                when (V2AppMode.CURRENT) {
                    // 2.0 Stage 1：完全离线 Demo，启动直接进入新首页
                    AppMode.DEMO -> {
                        val vm: V2HomeViewModel = hiltViewModel()
                        V2MainScreen(vm)
                    }
                    // 1.1 既有流程（连接/媒体墙等），保留备用
                    AppMode.PRODUCTION -> MediaReviewRoot()
                }
            }
        }
    }
}

/** 1.1 既有导航入口（PRODUCTION 模式使用，保留不删）。 */
@Composable
fun MediaReviewRoot() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ConnectDestinations.CONNECT,
    ) {
        connectGraph(navController)
        mainShellGraph(navController)
        libraryGraph(navController)
        imageViewerGraph(navController)
        playerGraph(navController)
        deleteQueueGraph(navController)
        duplicatesGraph(navController)
        settingsGraph(navController)
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MediaReviewTheme {
        MediaReviewRoot()
    }
}
