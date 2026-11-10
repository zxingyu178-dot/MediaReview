package com.mediareview.app.feature.v2.nsfw

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediareview.app.feature.v2.nsfw.data.*
import com.mediareview.app.feature.v2.nsfw.di.NsfwEntryPoint
import com.mediareview.app.feature.v2.nsfw.di.NsfwImageLoader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** 只对当前页加载成功的NEW图提交；放大查看不影响勾选。 */
@Composable
internal fun NsfwBatchReviewScreen(
    state: NsfwUiState, loader: NsfwImageLoader, onClose: () -> Unit,
    onRefresh: () -> Unit, onLoadMore: () -> Unit,
    onOpenOriginal: (NsfwFeedItem) -> Unit, originalOpen: Boolean,
    pageSize: Int = 9,
) {
    val context = LocalContext.current
    val ports = remember(context) { EntryPointAccessors.fromApplication(context, NsfwEntryPoint::class.java) }
    val coordinator = remember(state.baseUrl) { NsfwBatchCoordinator(ports.nsfwBatchSource(), ports.nsfwBatchStore(), state.baseUrl) }
    val scope = rememberCoroutineScope()
    var remaining by remember(state.baseUrl) { mutableStateOf(state.items.filter { it.status == "NEW" }.distinctBy { it.imageId }) }
    var completedIds by remember(state.baseUrl) { mutableStateOf(emptySet<String>()) }
    var draft by remember(state.baseUrl) { mutableStateOf(NsfwBatchDraft.open(remaining, pageSize)) }
    var pending by remember(state.baseUrl) { mutableStateOf<NsfwFrozenBatch?>(null) }
    var locked by remember(state.baseUrl) { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<NsfwFrozenBatch?>(null) }
    var confirmationSkipped by remember { mutableIntStateOf(0) }
    var rejectAll by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }
    var retryImages by remember { mutableIntStateOf(0) }
    var refreshRequired by remember { mutableStateOf(false) }
    var refreshingPage by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val supported = state.batchReviewEnabled

    LaunchedEffect(grid, draft.items, draft.loadedIds) {
        snapshotFlow {
            val layout = grid.layoutInfo
            layout.visibleItemsInfo.filter { item ->
                val height = (minOf(item.offset.y + item.size.height, layout.viewportEndOffset) - maxOf(item.offset.y, layout.viewportStartOffset)).coerceAtLeast(0)
                val width = (minOf(item.offset.x + item.size.width, layout.viewportSize.width) - maxOf(item.offset.x, 0)).coerceAtLeast(0)
                item.size.width > 0 && item.size.height > 0 && height.toLong() * width * 2 >= item.size.width.toLong() * item.size.height
            }.mapNotNull { it.key as? String }
        }.collect { visible -> visible.forEach { draft = draft.displayed(it) } }
    }

    // 已完成的身份不重新加入；仅空页接收用户加载的后续内容，活动页不被更新替换。
    LaunchedEffect(state.items, completedIds) {
        if (draft.items.isEmpty() && !locked) {
            remaining = state.items.filter { it.status == "NEW" && it.imageId !in completedIds }.distinctBy { it.imageId }
            draft = NsfwBatchDraft.open(remaining, pageSize)
        }
    }
    LaunchedEffect(coordinator) {
        try { pending = coordinator.pending(); locked = pending != null }
        catch (error: Exception) { message = "待核对批次读取失败，请重试"; locked = true }
    }

    fun refreshPage() {
        if (working || locked || refreshingPage) return
        refreshingPage = true; working = true
        scope.launch {
            try {
                // 最多9个既有ID按需取权威详情，不受图库当前已加载窗口限制。
                val pageIds = draft.items.map { it.imageId }.toSet()
                val latest = draft.items.mapNotNull { old ->
                    try {
                        val detail = ports.nsfwBatchSource().details(old.imageId)
                        if (detail.status !in setOf("NEW", "KEEP", "REJECT", "ARCHIVED", "FAVORITE")) {
                            throw NsfwApiException(NsfwErrorCodes.RESPONSE_MISMATCH, "详情缺少权威审核状态，不能刷新本页")
                        }
                        old.copy(status = detail.status, revision = detail.revision, updatedAt = detail.updatedAt)
                    } catch (error: NsfwApiException) { if (error.httpStatus == 404) null else throw error }
                }
                draft = draft.refreshed(latest)
                remaining = latest + remaining.filterNot { it.imageId in pageIds }
                completedIds = completedIds + (pageIds - latest.filter { it.status == "NEW" }.map { it.imageId }.toSet())
                refreshRequired = false; retryImages++
                message = "本页已刷新，请重新查看并确认；仍待审核的勾选已保留"
                onRefresh()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = "本页刷新失败：${error.message ?: "请重试"}" }
            finally { refreshingPage = false; working = false }
        }
    }

    suspend fun accept(result: NsfwBatchReviewResult, batch: NsfwFrozenBatch) {
        coordinator.acknowledge(batch.requestId)
        val ids = result.items.map { it.imageId }.toSet()
        completedIds = completedIds + ids
        remaining = remaining.filterNot { it.imageId in ids }
        pending = null; locked = false
        confirmation = null
        draft = NsfwBatchDraft.open(remaining, pageSize)
        message = "已保留 ${result.keptCount} 张、淘汰 ${result.rejectedCount} 张；其余图片不改状态"
        onRefresh()
    }
    fun recover() {
        if (working) return
        working = true
        scope.launch {
            try {
                val batch = coordinator.pending()
                pending = batch
                if (batch == null) { locked = false; message = "没有待核对批次，可重新确认当前页" }
                else coordinator.recover()?.let { accept(it, batch) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { locked = true; message = "结果尚未确认：${error.message ?: "请稍后重试"}。不会重新提交。" }
            finally { working = false }
        }
    }
    fun submit(allowRejectAll: Boolean) {
        if (working || locked || refreshRequired || !supported) return
        val batch = confirmation ?: return
        if (!allowRejectAll && batch.items.none { it.action == "KEEP" }) {
            confirm = false; rejectAll = true; return
        }
        working = true; confirm = false; rejectAll = false
        scope.launch {
            try { pending = batch; accept(coordinator.submit(batch), batch) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                pending = try { coordinator.pending() } catch (_: Exception) { batch }
                locked = pending != null
                if (error is NsfwApiException && error.code == "REVIEW_CONFLICT" && error.httpStatus == 409) refreshRequired = true
                message = if (locked) "结果尚未确认，请核对原批次，勿重复提交" else "提交未成功：${error.message ?: "请刷新后重新确认"}"
            } finally { working = false }
        }
    }
    val close = { if (draft.selectedIds.isNotEmpty() && !locked) exitConfirm = true else onClose() }
    BackHandler(enabled = !originalOpen) { close() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().background(Color(0xFF121212)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close, enabled = !working) { Text("返回图库") }
            Text("${pageSize}图批量审核", color = Color.White, modifier = Modifier.weight(1f))
            Text("${draft.items.size} 张", color = Color.LightGray)
        }
        Text("勾选保留，未选且加载成功的图片淘汰；不会删除文件", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        if (!supported) Text("电脑端暂不支持批量审核，请更新审核桥", color = Color.Yellow)
        message?.let { Text(it, color = Color.LightGray) }
        if (locked) TextButton(onClick = ::recover, enabled = !working) { Text(if (working) "核对中…" else "核对上次批次结果") }
        if (refreshRequired) TextButton(onClick = ::refreshPage, enabled = !working && !locked) { Text(if (refreshingPage) "刷新中…" else "刷新本页并重新确认") }
        LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid, modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(draft.items, key = { it.imageId }) { item ->
                Column(Modifier.background(if (item.imageId in draft.selectedIds) Color(0xFF24573B) else Color(0xFF242424))) {
                    key(item.imageId, retryImages) {
                        AsyncImage(model = loader.request(safeNsfwImageUrl(state.baseUrl, item.thumbnailUrl)), imageLoader = loader.loader,
                            contentDescription = "待审核图片", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable(enabled = !working && !locked) { draft = draft.toggle(item.imageId) },
                            onSuccess = { draft = draft.loaded(item.imageId, true) },
                            onError = { draft = draft.loaded(item.imageId, false) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.imageId in draft.selectedIds, enabled = !working && !locked && item.imageId in draft.eligibleIds,
                            onCheckedChange = { draft = draft.toggle(item.imageId) })
                        TextButton(onClick = { onOpenOriginal(item) }, enabled = !working) { Text("放大") }
                    }
                    if (item.imageId !in draft.eligibleIds) Text("未展示或未加载，不会处理", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row {
            TextButton(onClick = { draft = draft.selectAll() }, enabled = !working && !locked) { Text("全选") }
            TextButton(onClick = { draft = draft.clearSelection() }, enabled = !working && !locked) { Text("清空") }
            TextButton(onClick = { retryImages++ }, enabled = !working && !locked) { Text("重试图片") }
        }
        Text("保留 ${draft.keptCount} · 淘汰 ${draft.rejectedCount} · 未就绪 ${draft.unloadedCount}", color = Color.LightGray)
        if (draft.items.isEmpty()) TextButton(onClick = onLoadMore, enabled = state.hasMore && !state.syncing && !working && !locked) {
            Text(if (state.syncing) "正在加载…" else if (state.hasMore) "加载下一批" else "当前范围没有更多待审核图片")
        }
        Button(onClick = {
            try {
                confirmation = draft.freeze(UUID.randomUUID().toString(), allowRejectAll = true)
                confirmationSkipped = draft.unloadedCount
                confirm = true
            } catch (error: Exception) { message = error.message }
        }, enabled = supported && !working && !locked && !refreshRequired && draft.eligibleIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (working) "提交中…" else "提交本页")
        }
    }
    val confirmedKeep = confirmation?.items?.count { it.action == "KEEP" } ?: 0
    val confirmedReject = confirmation?.items?.count { it.action == "REJECT" } ?: 0
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; confirmation = null }, title = { Text("确认本页审核") },
        text = { Text("保留 $confirmedKeep 张、淘汰 $confirmedReject 张。$confirmationSkipped 张未展示或未加载，不会处理。") },
        confirmButton = { TextButton(onClick = { submit(false) }) { Text("确认提交") } },
        dismissButton = { TextButton(onClick = { confirm = false; confirmation = null }) { Text("返回选择") } })
    if (rejectAll) AlertDialog(onDismissRequest = { rejectAll = false; confirmation = null }, title = { Text("确认全部淘汰？") },
        text = { Text("本页没有选中保留。将淘汰 $confirmedReject 张已展示图片，不会删除文件。") },
        confirmButton = { TextButton(onClick = { submit(true) }) { Text("全部淘汰") } },
        dismissButton = { TextButton(onClick = { rejectAll = false; confirmation = null }) { Text("返回选择") } })
    if (exitConfirm) AlertDialog(onDismissRequest = { exitConfirm = false }, title = { Text("离开未提交的选择？") },
        text = { Text("本页勾选尚未提交，离开将丢弃这些选择。") },
        confirmButton = { TextButton(onClick = onClose) { Text("离开") } },
        dismissButton = { TextButton(onClick = { exitConfirm = false }) { Text("继续选择") } })
}
