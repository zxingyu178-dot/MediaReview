package com.mediareview.app.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mediareview.app.feature.connect.discovery.DiscoveredServer
import com.mediareview.app.feature.home.HomeDestinations

/** 连接功能导航目的路由。 */
object ConnectDestinations {
    const val CONNECT = "connect"
}

internal fun continueWhenPaired(
    paired: Boolean,
    onContinue: () -> Unit,
) {
    if (paired) onContinue()
}

/** 注册连接相关目的地;配对成功后跳转到首页。 */
fun NavGraphBuilder.connectGraph(
    navController: androidx.navigation.NavController,
) {
    composable(ConnectDestinations.CONNECT) {
        ConnectScreen(
            onContinue = {
                navController.navigate(HomeDestinations.HOME) {
                    popUpTo(ConnectDestinations.CONNECT) { inclusive = true }
                }
            },
        )
    }
}

/**
 * 连接/配对页:自动发现 / 手动 IP / 配对 三合一。
 */
@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel = hiltViewModel(),
    onContinue: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(ui.paired) {
        continueWhenPaired(ui.paired, onContinue)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("连接服务器", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("家庭媒体管家 · 局域网个人工具", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { viewModel.setStep(ConnectStep.Discover) }) {
                    Text("自动发现")
                }
                OutlinedButton(onClick = { viewModel.setStep(ConnectStep.ManualIp) }) {
                    Text("手动 IP")
                }
            }
            Spacer(Modifier.height(24.dp))

            when (ui.step) {
                ConnectStep.Discover -> DiscoverPanel(
                    ui = ui,
                    onDiscover = viewModel::startDiscovery,
                    onPick = viewModel::pickServer,
                )
                ConnectStep.ManualIp -> ManualIpPanel(
                    ui = ui,
                    onChange = viewModel::onManualIpChange,
                    onTest = viewModel::testManualServer,
                )
                ConnectStep.Pairing -> PairingPanel(
                    ui = ui,
                    onChange = viewModel::onCodeChange,
                    onSubmit = viewModel::submitCode,
                )
            }

            ui.error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            ui.message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            if (ui.busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun DiscoverPanel(
    ui: ConnectUiState,
    onDiscover: () -> Unit,
    onPick: (DiscoveredServer) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onDiscover, enabled = !ui.discovering) {
            Text(if (ui.discovering) "搜索中…" else "搜索服务器")
        }
        ui.servers.forEach { server ->
            OutlinedButton(
                onClick = { onPick(server) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(server.baseUrl)
            }
        }
    }
}

@Composable
private fun ManualIpPanel(
    ui: ConnectUiState,
    onChange: (String) -> Unit,
    onTest: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = ui.manualIp,
            onValueChange = onChange,
            label = { Text("服务器地址(如 192.168.1.10)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onTest, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
            Text("连接并输入配对码")
        }
    }
}

@Composable
private fun PairingPanel(
    ui: ConnectUiState,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column {
        Text(
            "服务器:${ui.selectedBaseUrl}\n" +
                "请在服务器电脑上的管理界面查看 6 位配对码,填写后完成配对。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = ui.code,
            onValueChange = onChange,
            label = { Text("6 位配对码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSubmit, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
            Text("完成配对")
        }
    }
}
