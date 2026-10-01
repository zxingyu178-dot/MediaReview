package com.mediareview.app.feature.v2.organize.delete

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.organize.data.DeleteCommitPrepare
import com.mediareview.app.feature.v2.organize.data.DeleteQueueEntry
import com.mediareview.app.feature.v2.organize.formatBytes
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 待删除中心（Stage 8C §14~§25）。
 *
 * - 列表来自 `GET /delete-queue`（封面由服务端随队列下发，客户端零 N+1）；
 * - 恢复单项：Server 成功后才更新列表，失败保持原样 + Snackbar；
 * - 最终删除：**先 prepare 拿到服务端快照**，再用 prepare 的数字弹确认，
 *   确认后同一 nonce 提交一次；结果逐类显示 success/missing/failed；
 * - 完成后**停留在本页**刷新列表（失败项仍可见），并通知上层刷新 V2 内容。
 */
@Composable
fun DeleteQueueScreen(
    onBack: () -> Unit,
    onFinalDeleteCommitted: (changedMediaIds: List<String>) -> Unit,
    modifier: Modifier = Modifier,
    vm: DeleteQueueViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DeleteQueueEvent.Info -> snackbar.showSnackbar(event.text)
                is DeleteQueueEvent.FinalDeleteCompleted ->
                    onFinalDeleteCommitted(event.changedMediaIds)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MediaBackground).testTag("delete_queue_screen")) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部：返回 + 标题 + 摘要（摘要来自当前列表，实际删除数字以 prepare 为准）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = V2Spacing.Sm, vertical = V2Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MediaTextPrimary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "待删除",
                        style = MaterialTheme.typography.titleLarge,
                        color = MediaTextPrimary,
                    )
                    Text(
                        text = if (ui.pendingCount > 0) {
                            "${ui.pendingCount} 项 · 预计释放 ${formatBytes(ui.pendingBytes)}"
                        } else {
                            "没有待删除内容"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MediaTextSecondary,
                    )
                }
            }

            when {
                ui.loading && ui.entries.isEmpty() -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MediaTextSecondary)
                    }
                }
                ui.error != null && ui.entries.isEmpty() -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("待删除加载失败", color = MediaTextSecondary)
                            Text(
                                text = ui.error.orEmpty(),
                                color = MediaTextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = V2Spacing.Xs, start = V2Spacing.Lg, end = V2Spacing.Lg),
                            )
                            TextButton(onClick = { vm.load() }) {
                                Text("重新加载", color = MediaTextPrimary)
                            }
                        }
                    }
                }
                ui.entries.isEmpty() -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("暂无待删除内容", color = MediaTextSecondary)
                    }
                }
                else -> {
                    if (ui.error != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "列表刷新失败，显示的是上次结果",
                                color = MediaTextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.load() }) {
                                Text("重新加载", color = MediaTextPrimary)
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = V2Spacing.Lg,
                            end = V2Spacing.Lg,
                            top = V2Spacing.Sm,
                            bottom = V2Spacing.Xl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
                    ) {
                        items(ui.entries, key = { it.mediaId }) { entry ->
                            DeleteQueueItemRow(entry = entry, onRestore = { vm.restore(entry.mediaId) })
                        }
                    }
                }
            }

            // 底部：最终删除（先在服务端 prepare，拿到快照后才弹确认）
            if (ui.entries.isNotEmpty()) {
                val busy = ui.phase is DeleteCommitPhase.Preparing || ui.phase is DeleteCommitPhase.Committing
                Button(
                    onClick = { vm.requestFinalDelete() },
                    enabled = !busy && ui.pendingCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MediaDanger,
                        contentColor = MediaTextPrimary,
                    ),
                    shape = RoundedCornerShape(V2Radius.Button),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = V2Spacing.Lg, vertical = V2Spacing.Md),
                ) {
                    Text(
                        text = when {
                            ui.phase is DeleteCommitPhase.Preparing -> "准备中…"
                            ui.phase is DeleteCommitPhase.Committing -> "删除中…"
                            else -> "最终删除（${ui.pendingCount} 项）"
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // 两步确认：ReadyToConfirm（可取消）/ Committing（不可取消、不可重复提交）
    when (val phase = ui.phase) {
        is DeleteCommitPhase.ReadyToConfirm -> ConfirmFinalDeleteDialog(
            prepare = phase.prepare,
            committing = false,
            onConfirm = { vm.confirmFinalDelete() },
            onDismiss = { vm.cancelFinalDelete() },
        )
        is DeleteCommitPhase.Committing -> ConfirmFinalDeleteDialog(
            prepare = phase.prepare,
            committing = true,
            onConfirm = { vm.confirmFinalDelete() },
            onDismiss = { vm.cancelFinalDelete() },
        )
        is DeleteCommitPhase.Result -> DeleteResultSheet(
            result = phase.result,
            onDismiss = { vm.dismissResult() },
        )
        else -> Unit
    }
}

/** 确认弹窗：数字**必须**来自 prepare response（§19），Committing 期间不可取消（§21）。 */
@Composable
private fun ConfirmFinalDeleteDialog(
    prepare: DeleteCommitPrepare,
    committing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!committing) onDismiss() },
        containerColor = MediaSurfaceRaised,
        title = { Text("确认永久删除？", color = MediaTextPrimary) },
        text = {
            Column {
                Text(
                    text = "将永久删除：\n${prepare.count} 个文件",
                    color = MediaTextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(V2Spacing.Md))
                Text(
                    text = "预计释放：\n${formatBytes(prepare.totalBytes)}",
                    color = MediaTextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(V2Spacing.Md))
                Text(
                    text = "删除后无法恢复。",
                    color = MediaDanger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !committing) {
                Text(
                    text = if (committing) "删除中…" else "永久删除",
                    color = if (committing) MediaTextSecondary else MediaDanger,
                )
            }
        },
        dismissButton = {
            // Committing 期间不允许取消/重复提交
            TextButton(onClick = onDismiss, enabled = !committing) {
                Text("取消", color = MediaTextSecondary)
            }
        },
    )
}

/** 队列项：封面 + 名称 + 类型/大小/加入时间 + 状态 + 恢复。 */
@Composable
private fun DeleteQueueItemRow(
    entry: DeleteQueueEntry,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .padding(V2Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(V2Radius.Sm))
                .background(V2Colors.Skeleton),
        ) {
            val coverUri = entry.coverUri
            if (!coverUri.isNullOrBlank()) {
                AsyncImage(
                    model = coverUri,
                    contentDescription = entry.media?.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = V2Spacing.Md),
        ) {
            Text(
                text = entry.media?.name ?: entry.mediaId,
                style = MaterialTheme.typography.titleSmall,
                color = MediaTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val typeLabel = if (entry.media?.type == V2MediaType.IMAGE) "图片" else "视频"
            val addedLabel = entry.addedAt?.take(10)?.let { " · 加入于 $it" }.orEmpty()
            Text(
                text = "$typeLabel · ${formatBytes(entry.sizeBytes)}$addedLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MediaTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.status == "failed") {
                // §21/§22：failed 项不会进入下一次 prepare，没有"直接重试"能力 ——
                // 文案必须与实际能力一致（先恢复，再重新标记）。
                Text(
                    text = "上次删除失败，请恢复后重新标记",
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaDanger,
                )
                deleteFailureReason(entry.error)?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MediaTextSecondary,
                    )
                }
            }
        }
        TextButton(onClick = onRestore) {
            Text("恢复", color = MediaTextPrimary)
        }
    }
}