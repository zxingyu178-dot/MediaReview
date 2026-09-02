package com.mediareview.app.feature.duplicates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import coil.compose.AsyncImage
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.model.DuplicateMemberDto
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.ui.theme.MediaSpacing

/** 重复文件页导航路由。 */
object DuplicatesDestinations {
    const val DUPLICATES = "duplicates"
}

/** 注册重复文件页目的地。 */
fun NavGraphBuilder.duplicatesGraph(navController: NavController) {
    composable(DuplicatesDestinations.DUPLICATES) {
        DuplicatesScreen(onBack = { navController.popBackStack() })
    }
}

/** 重复文件页:完全重复 + 疑似重复分组 + 后台扫描 + 双栏对比/保留选择(绝不自动删除)。 */
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(viewModel) { viewModel.loadIfNeeded() }

    val compareGroup = ui.compareGroup
    if (compareGroup != null) {
        BackHandler(onBack = viewModel::closeCompare)
        DuplicateCompareView(
            group = compareGroup,
            summaries = ui.compareSummaries,
            loading = ui.compareLoading,
            onKeepChange = { mediaId, keep ->
                viewModel.setKeep(compareGroup.group_id, mediaId, keep)
            },
            onBack = viewModel::closeCompare,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = MediaSpacing.Regular)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = MediaSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("重复文件", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::triggerScan, enabled = !ui.scanActive) { Text("扫描") }
            TextButton(onClick = viewModel::refresh) { Text("刷新") }
            TextButton(onClick = onBack) { Text("返回") }
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(ui.error!!)
            }

            else -> Column(Modifier.fillMaxSize()) {
                val showScanCard = ui.scanTaskId != null && ui.scanStatus != null && !ui.scanSucceeded
                if (showScanCard) {
                    ScanStatusCard(
                        status = ui.scanStatus!!,
                        progress = ui.scanProgress,
                        error = ui.scanError,
                        onPause = viewModel::pauseScan,
                        onResume = viewModel::resumeScan,
                        onCancel = viewModel::cancelScan,
                        onRescan = viewModel::triggerScan,
                    )
                }
                if (ui.exact.isEmpty() && ui.similar.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无重复文件,可先点击\"扫描\"检测")
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(MediaSpacing.Small)) {
                        if (ui.exact.isNotEmpty()) {
                            item { SectionHeader("完全重复(${ui.exact.size} 组)") }
                            items(ui.exact, key = { it.group_id }) { DuplicateGroupRow(it, viewModel::openCompare) }
                        }
                        if (ui.similar.isNotEmpty()) {
                            item { SectionHeader("疑似重复(${ui.similar.size} 组)") }
                            items(ui.similar, key = { it.group_id }) { DuplicateGroupRow(it, viewModel::openCompare) }
                        }
                    }
                }
            }
        }
    }
}

/** 后台扫描任务状态卡:进度/暂停/继续/取消。 */
@Composable
private fun ScanStatusCard(
    status: String,
    progress: Int,
    error: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MediaSpacing.Small)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(MediaSpacing.Regular),
    ) {
        when (status) {
            "pending", "running" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正在扫描重复文件… $progress%", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onPause) { Text("暂停") }
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = MediaSpacing.Small),
                )
            }
            "paused" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("扫描已暂停($progress%)", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onResume) { Text("继续") }
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
            "failed" -> {
                Text(
                    text = "扫描失败:${error ?: "未知错误"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRescan) { Text("重新扫描") }
            }
            "cancelled" -> {
                Text("扫描已取消", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRescan) { Text("重新扫描") }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = MediaSpacing.Small),
    )
}

@Composable
private fun DuplicateGroupRow(group: DuplicateGroupDto, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(group.group_id) }
            .padding(MediaSpacing.Regular),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${group.count} 个文件 · ${fmtBytes(group.size_bytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "对比选择",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        group.duration_ms?.let { Text("时长 ${it / 1000}s", style = MaterialTheme.typography.labelSmall) }
        val kept = group.members.count { it.keep }
        if (kept > 0) {
            Text("已标记保留 $kept 份", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        group.names.forEach { name ->
            Text(
                text = "· $name",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (group.detail.isNotBlank()) {
            Text(
                text = group.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 双栏对比页:两列并排展示各成员(封面/元数据) + 人工"保留"选择。 */
@Composable
private fun DuplicateCompareView(
    group: DuplicateGroupDto,
    summaries: Map<String, MediaSummary>,
    loading: Boolean,
    onKeepChange: (mediaId: String, keep: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = MediaSpacing.Regular)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = MediaSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("重复对比", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text(
            text = group.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = MediaSpacing.Small),
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            group.members.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("分组无成员")
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(MediaSpacing.Regular),
                verticalArrangement = Arrangement.spacedBy(MediaSpacing.Regular),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(group.members, key = { it.media_id }) { member ->
                    MemberCompareCard(
                        member = member,
                        summary = summaries[member.media_id],
                        onToggleKeep = { onKeepChange(member.media_id, !member.keep) },
                    )
                }
            }
        }
        Text(
            text = "提示:重复文件不会自动删除;此处\"保留\"仅作整理记录,删除仍需在待删除页确认。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = MediaSpacing.Small),
        )
    }
}

@Composable
private fun MemberCompareCard(
    member: DuplicateMemberDto,
    summary: MediaSummary?,
    onToggleKeep: () -> Unit,
) {
    val borderColor = if (member.keep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 2.dp, color = borderColor, shape = MaterialTheme.shapes.medium)
            .padding(MediaSpacing.Small),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            val cover = summary?.cover_url
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无封面", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            text = member.name.ifBlank { member.media_id },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MediaSpacing.XSmall),
        )
        summary?.let { s ->
            val duration = s.duration_ms?.let { "${it / 1000}s" }
            val size = s.size_bytes?.let { fmtBytes(it) }
            val resolution = if (s.width != null && s.height != null) "${s.width}×${s.height}" else null
            val meta = listOfNotNull(size, duration, resolution).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleKeep).padding(top = MediaSpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = member.keep, onCheckedChange = { onToggleKeep() })
            Text(
                text = if (member.keep) "已保留" else "保留此文件",
                style = MaterialTheme.typography.labelLarge,
                color = if (member.keep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
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
