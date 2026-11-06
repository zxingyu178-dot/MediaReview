package com.mediareview.app.feature.v2.nsfw

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor
import com.mediareview.app.feature.v2.nsfw.di.NsfwImageLoader
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged

/** 共用既有feed与图片认证；网格只为可见单元创建缩略图请求。 */
@Composable
internal fun NsfwGallery(
    state: NsfwUiState,
    imageLoader: NsfwImageLoader,
    grid: LazyGridState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDisconnect: () -> Unit,
    onAnchor: (NsfwGalleryAnchor, Boolean) -> Unit,
) {
    val initialAnchor = remember(state.baseUrl) { state.galleryAnchor }
    var restorePending by remember(state.baseUrl) { mutableStateOf(initialAnchor != null) }
    var restoringNow by remember { mutableStateOf(false) }
    var userMoved by remember(state.baseUrl) { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.items.size, initialAnchor) {
        if (restorePending && initialAnchor != null) {
            val index = state.items.indexOfFirst { it.imageId == initialAnchor.imageId }
            if (index >= 0) {
                restoringNow = true
                try { grid.scrollToItem(index, initialAnchor.offset) ; restorePending = false }
                finally { restoringNow = false }
            }
        }
    }
    LaunchedEffect(grid, state.items) {
        snapshotFlow { Triple(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, grid.isScrollInProgress) }
            .distinctUntilChanged().collect { (index, offset, scrolling) ->
                if (restoringNow) return@collect
                if (scrolling) { restorePending = false; userMoved = true }
                if (!restorePending && !scrolling) state.items.getOrNull(index)?.let { onAnchor(NsfwGalleryAnchor(it.imageId, offset), userMoved) }
            }
    }
    LaunchedEffect(grid, state.items.size, state.hasMore, state.syncing) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { index ->
                if (state.hasMore && !state.syncing && index >= 0 && index >= state.items.size - 9) onLoadMore()
            }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("nsfw_gallery")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("生图图库", color = MediaTextPrimary, style = MaterialTheme.typography.titleLarge)
                Text("${state.items.size} 张${if (state.hasMore) " · 仍有更多" else ""}", color = MediaTextSecondary)
            }
            TextButton(onClick = onRefresh, enabled = !state.syncing && !state.busy) { Text(if (state.syncing) "同步中…" else "同步新图") }
            Box {
                TextButton(onClick = { moreOpen = true }) { Text("更多") }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("解除生图配对") }, enabled = !state.busy && state.panel?.submitting != true,
                        onClick = { moreOpen = false; onDisconnect() })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = state.currentItem != null && !state.busy,
                onClick = { state.currentId?.let(onSelect) }) { Text(if (state.hasBrowsePosition) "继续上次" else "开始审核") }
            if (state.restoringBrowsePosition) Text("正在恢复上次图片…", color = MediaTextSecondary)
            if (state.newImageCount > 0) Text("队尾新增 ${state.newImageCount} 张", color = MediaTextSecondary)
        }
        state.browseRecoveryMessage?.let { Text(it, color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        state.message?.let { Text(it, color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        state.conflictMessage?.let { Text(it, color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        state.evaluationRecoveryHint?.let { Text(it, color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        if (state.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.syncing) "正在同步图库…" else "图库暂无图片，生成后会自动追加", color = MediaTextSecondary)
            }
        } else LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid,
            modifier = Modifier.weight(1f).testTag("nsfw_gallery_grid"),
            contentPadding = PaddingValues(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(state.items, key = { _, item -> item.imageId }) { index, item ->
                GalleryImage(item, index + 1, state.baseUrl, imageLoader, item.imageId == state.currentId, !state.busy && state.panel?.submitting != true) { onSelect(item.imageId) }
            }
        }
        if (state.hasMore) TextButton(onClick = onLoadMore, enabled = !state.syncing) { Text(if (state.syncing) "正在加载…" else "加载更多") }
    }
}

@Composable
private fun GalleryImage(item: NsfwFeedItem, ordinal: Int, baseUrl: String, loader: NsfwImageLoader, selected: Boolean, enabled: Boolean, onOpen: () -> Unit) {
    var failed by remember(item.imageId, item.thumbnailUrl) { mutableStateOf(false) }
    var attempt by remember(item.imageId, item.thumbnailUrl) { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF202126)).clickable(enabled = enabled, onClick = onOpen)) {
        key(attempt) {
            AsyncImage(model = loader.request(safeNsfwImageUrl(baseUrl, item.thumbnailUrl)), imageLoader = loader.loader,
                contentDescription = "第 $ordinal 张生图缩略图${if (selected) "，当前" else ""}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onLoading = { failed = false }, onError = { failed = true }, onSuccess = { failed = false })
        }
        if (failed) TextButton(onClick = { attempt++; failed = false }, modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000))) { Text("加载失败 · 重试") }
        Text(when (item.status) { "KEEP", "KEPT" -> "已保留"; "REJECT", "REJECTED" -> "已淘汰"; "NEW", "PENDING" -> "待审核"; else -> "状态待确认" },
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000)).padding(4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (selected) Text("当前", Modifier.align(Alignment.TopEnd).background(Color(0xCC000000)).padding(4.dp), color = Color.White)
    }
}
