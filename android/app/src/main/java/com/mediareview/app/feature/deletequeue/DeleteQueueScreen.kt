package com.mediareview.app.feature.deletequeue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import coil.compose.AsyncImage
import com.mediareview.app.core.model.DeleteQueueItemDto

/** 待删除页导航路由。 */
object DeleteQueueDestinations {
    const val DELETE_QUEUE = "delete_queue"
}

/** 注册待删除页目的地。 */
fun NavGraphBuilder.deleteQueueGraph(navController: NavController) {
    composable(DeleteQueueDestinations.DELETE_QUEUE) {
        DeleteQueueScreen(onBack = { navController.popBackStack() })
    }
}

/** 待删除页:待删除数量 + 预计释放空间 + 文件列表 + 恢复 + 最终删除确认。 */
@Composable
fun DeleteQueueScreen(
    onBack: () -> Unit,
    viewModel: DeleteQueueViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("待删除", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> Column(Modifier.fillMaxSize()) {
                // 统计卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("待删除 ${ui.pendingCount} 项", style = MaterialTheme.typography.titleMedium)
                        Text("预计释放 ${fmtBytes(ui.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { showConfirm = true },
                        enabled = ui.pendingCount > 0,
                    ) { Text("最终删除") }
                }

                if (ui.items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("待删除队列为空")
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                    ) {
                        items(ui.items, key = { it.media_id }) { item ->
                            DeleteQueueRow(item, viewModel::restore)
                        }
                    }
                }
            }
        }
    }

    // 最终删除二次确认
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("确认永久删除?") },
            text = {
                Text("将永久删除待删除队列中的 ${ui.pendingCount} 个文件,此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    viewModel.commit()
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }

    // 最终删除结果提示
    ui.commitResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.clearCommitResult() },
            title = { Text("删除结果") },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearCommitResult() }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun DeleteQueueRow(
    item: DeleteQueueItemDto,
    onRestore: (String) -> Unit,
) {
    val media = item.media
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = media?.cover_url,
            contentDescription = media?.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = media?.name ?: item.media_id,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${fmtBytes(item.size_bytes ?: 0)} · ${if (media?.isVideo == true) "视频" else "图片"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { onRestore(item.media_id) }) { Text("恢复") }
    }
}

private fun fmtBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
