package com.mediareview.app.feature.v2.organize.duplicates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupType
import com.mediareview.app.feature.v2.organize.formatBytes
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaAccent
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 重复媒体中心（Stage 8C §27~§30）。
 *
 * - 完全重复 = `type=exact`（完整 SHA-256 byte-identical）；疑似重复 = `type=similar`；
 *   high / candidate 不进入用户页面（§27）；
 * - 扫描按钮触发真实后台扫描，pending/running 时 1.5s 轮询，暂停/继续/取消均走服务端任务接口；
 * - **绝不自动删除重复文件**：本页只发现 / 对比 / 记录保留选择。
 */
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onOpenCompare: (groupId: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: DuplicatesViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // §29：进入先 GET status 恢复；离开页面（含切后台）停止轮询
    DisposableEffect(Unit) {
        vm.enterScreen()
        onDispose { vm.leaveScreen() }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(modifier = modifier.fillMaxSize().background(MediaBackground).testTag("duplicates_screen")) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                        text = "重复媒体",
                        style = MaterialTheme.typography.titleLarge,
                        color = MediaTextPrimary,
                    )
                    Text(
                        text = "完全重复 ${ui.exactTotal} 组 · 疑似重复 ${ui.similarTotal} 组",
                        style = MaterialTheme.typography.bodySmall,
                        color = MediaTextSecondary,
                    )
                }
                TextButton(
                    onClick = { vm.startScan() },
                    enabled = !ui.scanBusy && !ui.demoUnavailable && !ui.scan.isActive,
                ) {
                    Text("扫描", color = if (ui.scanBusy) MediaTextSecondary else MediaAccent)
                }
            }

            when {
                ui.demoUnavailable -> CenteredHint(
                    title = "重复媒体仅在服务器模式可用",
                    subtitle = "切换到「我的服务器」后可扫描完全重复与疑似重复",
                    modifier = Modifier.weight(1f),
                )
                ui.loading -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MediaTextSecondary)
                }
                ui.error != null && ui.exact.isEmpty() && ui.similar.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("重复媒体加载失败", color = MediaTextSecondary)
                        Text(
                            text = ui.error.orEmpty(),
                            color = MediaTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(
                                top = V2Spacing.Xs,
                                start = V2Spacing.Lg,
                                end = V2Spacing.Lg,
                            ),
                        )
                        TextButton(onClick = { vm.refresh() }) {
                            Text("重新加载", color = MediaTextPrimary)
                        }
                    }
                }
                else -> Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // §28/§29：扫描已完成但结果刷新失败 —— 保留旧列表，明确提示 + 重新加载
                    if (ui.scanReloadFailed) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "扫描已完成，但结果刷新失败",
                                color = MediaDanger,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.retryReloadAfterScan() }) {
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
                        item {
                            ScanStatusCard(
                                ui = ui,
                                onStart = { vm.startScan() },
                                onPause = { vm.pauseScan() },
                                onResume = { vm.resumeScan() },
                                onCancel = { vm.cancelScan() },
                            )
                        }
                        item { SectionHeader("完全重复", ui.exactTotal) }
                        if (ui.exact.isEmpty()) {
                            item { SectionEmpty("未发现完全重复（需要完整 SHA-256 一致）") }
                        } else {
                            items(ui.exact, key = { "exact-${it.groupId}" }) { group ->
                                GroupCard(group = group, onOpen = { onOpenCompare(group.groupId) })
                            }
                            if (ui.exactTotal > ui.exact.size) {
                                item(key = "exact-load-more") {
                                    LoadMoreRow(
                                        loading = ui.loadingMore == DuplicateGroupType.EXACT,
                                        loadedCount = ui.exact.size,
                                        onLoad = { vm.loadNext(DuplicateGroupType.EXACT) },
                                    )
                                }
                            }
                        }
                        item { SectionHeader("疑似重复", ui.similarTotal) }
                        if (ui.similar.isEmpty()) {
                            item { SectionEmpty("未发现疑似重复") }
                        } else {
                            items(ui.similar, key = { "similar-${it.groupId}" }) { group ->
                                GroupCard(group = group, onOpen = { onOpenCompare(group.groupId) })
                            }
                            if (ui.similarTotal > ui.similar.size) {
                                item(key = "similar-load-more") {
                                    LoadMoreRow(
                                        loading = ui.loadingMore == DuplicateGroupType.SIMILAR,
                                        loadedCount = ui.similar.size,
                                        onLoad = { vm.loadNext(DuplicateGroupType.SIMILAR) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** 扫描状态卡：状态 / 进度 / 可控按钮（全部走服务端任务接口）。 */
@Composable
private fun ScanStatusCard(
    ui: DuplicatesUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val scan = ui.scan
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .padding(V2Spacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DuplicateScanUi.statusLabel(scan.status),
                style = MaterialTheme.typography.titleSmall,
                color = MediaTextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (scan.status != null) {
                Text(
                    text = "${scan.progress}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MediaTextSecondary,
                )
            }
        }
        if (scan.status == "pending" || scan.status == "running" || scan.status == "paused") {
            Spacer(modifier = Modifier.height(V2Spacing.Sm))
            LinearProgressIndicator(
                progress = { (scan.progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = MediaAccent,
                trackColor = MediaBackground,
            )
        }
        if (scan.status == "failed" && !scan.error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(V2Spacing.Xs))
            Text(
                text = scan.error,
                style = MaterialTheme.typography.labelSmall,
                color = MediaDanger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(V2Spacing.Md))
        Row(horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm)) {
            when (scan.status) {
                "pending", "running" -> {
                    OutlinedButton(onClick = onPause, enabled = !ui.scanBusy) { Text("暂停") }
                    TextButton(onClick = onCancel, enabled = !ui.scanBusy) {
                        Text("取消", color = MediaTextSecondary)
                    }
                }
                "paused" -> {
                    Button(onClick = onResume, enabled = !ui.scanBusy) { Text("继续") }
                    TextButton(onClick = onCancel, enabled = !ui.scanBusy) {
                        Text("取消", color = MediaTextSecondary)
                    }
                }
                else -> Button(
                    onClick = onStart,
                    enabled = !ui.scanBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MediaAccent,
                        contentColor = MediaBackground,
                    ),
                ) {
                    Text(if (scan.status == null) "开始扫描" else "重新扫描")
                }
            }
            if (ui.scanBusy) {
                Text(
                    text = "处理中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextSecondary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            if (scan.status == "succeeded" && !ui.scanBusy) {
                Text(
                    text = "扫描完成，以下分组来自最近一次扫描",
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextSecondary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        text = "$title（$count 组）",
        style = MaterialTheme.typography.titleSmall,
        color = MediaTextPrimary,
        modifier = Modifier.padding(top = V2Spacing.Sm),
    )
}

@Composable
private fun SectionEmpty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MediaTextSecondary,
        modifier = Modifier.padding(vertical = V2Spacing.Xs),
    )
}

/**
 * 分区列表底部：滚动接近底部时自动加载下一页（Stage 8C.1 §12：PAGE_SIZE=50）。
 *
 * `LaunchedEffect(loading, loadedCount)` 保证：进入视野触发一次、失败后可点击重试，
 * 每加载一页后（loadedCount 变化）若仍可见会继续取下一页。
 */
@Composable
private fun LoadMoreRow(loading: Boolean, loadedCount: Int, onLoad: () -> Unit) {
    LaunchedEffect(loading, loadedCount) {
        if (!loading) onLoad()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .clickable(enabled = !loading, onClick = onLoad)
            .padding(vertical = V2Spacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MediaTextSecondary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = "加载更多…",
                style = MaterialTheme.typography.labelSmall,
                color = MediaTextSecondary,
            )
        }
    }
}

/** 分组卡：详情文案 + 份数/大小；点击进入对比（对照数据一次请求返回）。 */
@Composable
private fun GroupCard(group: DuplicateGroupSummary, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable(onClick = onOpen)
            .padding(V2Spacing.Lg),
    ) {
        Text(
            text = if (group.type == "exact") "完全重复 · ${group.count} 份" else "疑似重复 · ${group.count} 份",
            style = MaterialTheme.typography.titleSmall,
            color = MediaTextPrimary,
        )
        if (group.detail.isNotBlank()) {
            Text(
                text = group.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MediaTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = V2Spacing.Xs),
            )
        }
        Text(
            text = "每份约 ${formatBytes(group.sizeBytes)} · 点击对比",
            style = MaterialTheme.typography.labelSmall,
            color = MediaTextSecondary,
            modifier = Modifier.padding(top = V2Spacing.Xs),
        )
    }
}

@Composable
private fun CenteredHint(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = MediaTextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                color = MediaTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = V2Spacing.Xs, start = V2Spacing.Xl, end = V2Spacing.Xl),
            )
        }
    }
}