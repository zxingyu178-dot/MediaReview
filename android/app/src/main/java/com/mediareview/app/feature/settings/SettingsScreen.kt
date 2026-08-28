package com.mediareview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.mediareview.app.feature.connect.ConnectDestinations
import com.mediareview.app.ui.shell.MainShellDestinations

/** 设置页导航路由。 */
object SettingsDestinations {
    const val ROUTE = "settings"
}

fun NavController.replaceShellWithConnect() {
    navigate(ConnectDestinations.CONNECT) {
        popUpTo(MainShellDestinations.ROUTE) { inclusive = true }
        launchSingleTop = true
    }
}

/** 注册设置页目的地。 */
fun NavGraphBuilder.settingsGraph(navController: NavController) {
    composable(SettingsDestinations.ROUTE) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onReconnect = navController::replaceShellWithConnect,
        )
    }
}

/** 设置页:服务器信息、连接检查、重新连接、清除配置。 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onReconnect: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Spacer(Modifier.weight(1f))
                Text("设置", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))

            if (ui.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
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

                Spacer(Modifier.height(16.dp))

                if (ui.checking) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                }
                ui.checkMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.checkConnection() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("检查连接") }
                    Button(
                        onClick = onReconnect,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重新连接 / 重新配对") }
                    OutlinedButton(
                        onClick = { viewModel.clearAndReconnect(onCleared = onReconnect) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("清除配置并重新连接") }
                }
            }
        }
    }
}
