package com.mediareview.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.NavController
import com.mediareview.app.feature.library.LibraryDestinations
import com.mediareview.app.feature.mediawall.MediaWallDestinations
import com.mediareview.app.feature.review.ReviewDestinations
import com.mediareview.app.feature.favorites.FavoritesDestinations
import com.mediareview.app.feature.deletequeue.DeleteQueueDestinations
import com.mediareview.app.feature.duplicates.DuplicatesDestinations
import com.mediareview.app.feature.settings.SettingsDestinations

/** 主页导航路由。 */
object HomeDestinations {
    const val HOME = "home"
}

/** 注册首页目的地。 */
fun NavGraphBuilder.homeGraph(navController: NavController) {
    composable(HomeDestinations.HOME) {
        HomeScreen(
            onEnterLibrary = { navController.navigate(LibraryDestinations.LIBRARY) },
            onEnterMediaWall = { navController.navigate(MediaWallDestinations.MEDIA_WALL) },
            onEnterReview = { navController.navigate(ReviewDestinations.ROUTE) },
            onEnterFavorites = { navController.navigate(FavoritesDestinations.FAVORITES) },
            onEnterDeleteQueue = { navController.navigate(DeleteQueueDestinations.DELETE_QUEUE) },
            onEnterDuplicates = { navController.navigate(DuplicatesDestinations.DUPLICATES) },
            onEnterSettings = { navController.navigate(SettingsDestinations.ROUTE) },
        )
    }
}

/**
 * 首页:显示服务器配置概况(Server Profile)并提供进入媒体库选择与媒体墙的入口。
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onEnterLibrary: () -> Unit,
    onEnterMediaWall: () -> Unit,
    onEnterReview: () -> Unit,
    onEnterFavorites: () -> Unit,
    onEnterDeleteQueue: () -> Unit,
    onEnterDuplicates: () -> Unit,
    onEnterSettings: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("家庭媒体管家", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEnterSettings) { Text("设置") }
            }
            Text("服务器配置与媒体浏览", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            when {
                ui.loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.CenterHorizontally),
                )

                ui.error != null -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(ui.error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.reload() }) { Text("重试") }
                    }
                }

                else -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("服务器地址", style = MaterialTheme.typography.labelMedium)
                            Text(ui.baseUrl.ifBlank { "未配置" }, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("设备编号", style = MaterialTheme.typography.labelMedium)
                            Text(ui.deviceId.ifBlank { "未生成" }, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (ui.paired) "已配对" else "未配对",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (ui.paired) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onEnterLibrary,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("媒体库选择") }
                        Button(
                            onClick = onEnterMediaWall,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("媒体墙") }
                        Button(
                            onClick = onEnterReview,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("批阅") }
                        Button(
                            onClick = onEnterFavorites,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("喜欢") }
                        Button(
                            onClick = onEnterDeleteQueue,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("待删除") }
                        Button(
                            onClick = onEnterDuplicates,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("重复文件") }
                    }
                }
            }
        }
    }
}
