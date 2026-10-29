package com.mediareview.app.feature.v2.nsfw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mediareview.app.ui.theme.MediaBackground

@Composable
fun GeneratedReviewScreen(vm: GeneratedReviewViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val address by vm.address.collectAsState()
    val settingsMessage by vm.settingsMessage.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(vm, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.start()
            if (event == Lifecycle.Event.ON_PAUSE) vm.stop()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.start()
        onDispose { owner.lifecycle.removeObserver(observer); vm.stop() }
    }
    LazyColumn(Modifier.fillMaxSize().background(MediaBackground).statusBarsPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("生图审核", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton({ settingsOpen = true }) { Text("连接设置") }
            }
            Text("新生成的图片追加到队尾，保留当前查看位置", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (state.phase) {
                        GeneratedPhase.IDLE, GeneratedPhase.LOADING -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在检查图片审核能力…")
                        }
                        GeneratedPhase.UNAVAILABLE -> {
                            Text("生图审核待接入", style = MaterialTheme.typography.titleMedium)
                            Text("当前服务尚未提供此能力。接入后可查看生成图片、提示词、生成时间和尺寸，并按提示词版本评分。")
                            Text("不会把视频服务的配对凭据用于生图服务。", style = MaterialTheme.typography.bodySmall)
                        }
                        GeneratedPhase.ERROR -> Text(state.message ?: "更新失败，已保留已有内容", color = MaterialTheme.colorScheme.error)
                        GeneratedPhase.READY -> Text(if (state.queue.items.isEmpty()) "当前没有可审核的生成图片" else "已加载 ${state.queue.items.size} 张图片")
                    }
                    if (state.phase != GeneratedPhase.LOADING) {
                        TextButton(vm::start) { Text(if (state.hasMore) "加载更多" else "重新检查") }
                    }
                }
            }
        }
        settingsMessage?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
        state.queue.items.find { it.id == state.queue.currentImageId }?.let { image ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("图片信息", style = MaterialTheme.typography.titleMedium)
                        Text("生成时间：${image.createdAt ?: "未提供"}")
                        Text("尺寸：${if (image.width != null && image.height != null) "${image.width} × ${image.height}" else "未提供"}")
                        Text("提示词：${image.prompt ?: "未提供"}")
                        Text("负面提示词：${image.negativePrompt ?: "未提供"}")
                        Text("生成版本：${image.promptVersionId ?: "未提供"}")
                        Text("提示词评分尚未开放；同一提示词版本的多张图片共享评分。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(state.queue.items, key = { it.id }) { image ->
            OutlinedButton({ vm.select(image.id) }, Modifier.fillMaxWidth()) {
                Text(image.createdAt ?: "生成时间未提供", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (settingsOpen) {
        var newAddress by remember { mutableStateOf(address) }
        // 凭据只驻留当前对话框内存，不存入SavedState，也不回填已有密文。
        var token by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { settingsOpen = false },
            title = { Text("生图服务连接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("填写生图审核桥的地址和独立凭据。保存配置不会启用尚未提供的接口。")
                    OutlinedTextField(newAddress, { newAddress = it }, label = { Text("服务根地址") }, singleLine = true)
                    OutlinedTextField(token, { token = it }, label = { Text("生图服务凭据") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            },
            confirmButton = { TextButton({ vm.saveConnection(newAddress, token); settingsOpen = false },
                enabled = newAddress.isNotBlank() && token.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton({ settingsOpen = false }) { Text("取消") } })
    }
}
