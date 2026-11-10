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
import com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter
import com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort
import com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
    onChangeFilter: (NsfwGalleryFilter) -> Unit = {},
    onLatest: () -> Unit = onRefresh,
    onResume: () -> Unit = { state.currentId?.let(onSelect) },
    onLoadPrevious: () -> Unit = {},
    onOpenBatch: () -> Unit = {},
) {
    val initialAnchor = remember(state.baseUrl, state.galleryViewEpoch) { state.galleryAnchor }
    var restorePending by remember(state.baseUrl, state.galleryViewEpoch) { mutableStateOf(initialAnchor != null) }
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
                Text("已加载 ${state.items.size} 张${if (state.hasMore) " · 仍有更多" else ""}", color = MediaTextSecondary)
            }
            TextButton(onClick = if (state.galleryEnabled) onLatest else onRefresh, enabled = !state.syncing && !state.busy) {
                Text(if (state.syncing) "同步中…" else if (state.galleryEnabled) "查看最新" else "同步新图")
            }
            Box {
                TextButton(onClick = { moreOpen = true }) { Text("更多") }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("解除生图配对") }, enabled = !state.busy && state.panel?.submitting != true,
                        onClick = { moreOpen = false; onDisconnect() })
                }
            }
        }
        if (state.galleryEnabled) GalleryFilters(state.galleryQuery.filter, !state.busy, onChangeFilter)
        if (state.galleryMembershipUnknown) Text("部分更新的筛选归属待确认，请重新查询；新增数仅统计已确认匹配的图片", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (state.gallerySearchEnabled) LibrarySearch(state.galleryQuery.filter, !state.busy && !state.syncing, onChangeFilter)
        if (state.galleryFiltersEnabled) LibraryFilters(state.galleryQuery.filter, !state.busy && !state.syncing, onChangeFilter)
        else Text("排序和日期筛选需更新电脑端生图审核服务", color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
        TextButton(onClick = onOpenBatch, enabled = !state.busy && !state.syncing) { Text("9图批量审核") }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = (state.currentItem != null || state.hasBrowsePosition) && !state.busy && !state.syncing,
                onClick = onResume) { Text(if (state.hasBrowsePosition) "继续上次" else "开始审核") }
            if (state.restoringBrowsePosition) Text("正在恢复上次图片…", color = MediaTextSecondary)
            if (state.newImageCount > 0) {
                if (state.galleryEnabled) TextButton(onClick = onLatest, enabled = !state.busy && !state.syncing) { Text("${state.newImageCount} 张新图 · 查看最新") }
                else Text("队尾新增 ${state.newImageCount} 张", color = MediaTextSecondary)
            }
        }
        state.browseRecoveryMessage?.let { Text(it, color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        state.message?.let { Text(it, color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        state.conflictMessage?.let { Text(it, color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        state.evaluationRecoveryHint?.let { Text(it, color = MediaTextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
        if (state.bidirectionalGallery && state.previousPageCursor != null) TextButton(onClick = onLoadPrevious, enabled = !state.syncing && !state.busy) {
            Text(if (state.syncing) "正在加载…" else "加载前页")
        }
        if (state.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.syncing) "正在同步图库…" else if (state.galleryEnabled) "当前日期范围没有图片，可调整日期或查看最新" else "图库暂无图片，生成后会自动追加", color = MediaTextSecondary)
            }
        } else LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid,
            modifier = Modifier.weight(1f).testTag("nsfw_gallery_grid"),
            contentPadding = PaddingValues(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(state.items, key = { _, item -> item.imageId }) { index, item ->
                GalleryImage(item, index + 1 + if (state.bidirectionalGallery && state.galleryPositionValid) state.galleryStartIndex else 0, state.baseUrl, imageLoader, item.imageId == state.currentId, !state.busy && state.panel?.submitting != true) { onSelect(item.imageId) }
            }
        }
        if (state.hasMore) TextButton(onClick = onLoadMore, enabled = !state.syncing) { Text(if (state.syncing) "正在加载…" else "加载更多") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GalleryFilters(filter: NsfwGalleryFilter, enabled: Boolean, onChange: (NsfwGalleryFilter) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    var datesOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            TextButton(onClick = { sortOpen = true }, enabled = enabled) { Text(filter.sort.label) }
            DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                NsfwGallerySort.entries.forEach { sort -> DropdownMenuItem(text = { Text(sort.label) },
                    onClick = { sortOpen = false; onChange(filter.copy(sort = sort)) }) }
            }
        }
        Box {
            TextButton(onClick = { datesOpen = true }, enabled = enabled) { Text(filter.label) }
            DropdownMenu(datesOpen, onDismissRequest = { datesOpen = false }) {
                NsfwGalleryDates.entries.forEach { dates -> DropdownMenuItem(text = { Text(dates.label) }, onClick = {
                    datesOpen = false
                    if (dates == NsfwGalleryDates.CUSTOM) customOpen = true else onChange(filter.copy(dates = dates))
                }) }
            }
        }
    }
    if (customOpen) {
        fun dateMillis(date: String?): Long? = date?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
        val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = dateMillis(filter.startDate), initialSelectedEndDateMillis = dateMillis(filter.endDate))
        DatePickerDialog(onDismissRequest = { customOpen = false }, confirmButton = {
            TextButton(enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null, onClick = {
                fun date(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toString()
                onChange(filter.copy(dates = NsfwGalleryDates.CUSTOM, startDate = date(picker.selectedStartDateMillis!!), endDate = date(picker.selectedEndDateMillis!!)))
                customOpen = false
            }) { Text("应用日期") }
        }, dismissButton = { TextButton(onClick = { customOpen = false }) { Text("取消") } }) {
            DateRangePicker(state = picker, modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp), title = { Text("选择审核日期", modifier = Modifier.padding(16.dp)) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilters(filter: NsfwGalleryFilter, enabled: Boolean, onChange: (NsfwGalleryFilter) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryFilterMenu("状态", filter.status, listOf(null to "全部状态", "NEW" to "待审核", "KEEP" to "已保留", "REJECT" to "已淘汰", "ARCHIVED" to "已归档"), enabled) { onChange(filter.copy(status = it)) }
        LibraryFilterMenu("分类", filter.category, listOf(null to "全部分类", "anime" to "动漫", "imported" to "导入"), enabled) { onChange(filter.copy(category = it)) }
        LibraryFilterMenu("收藏", filter.favorite?.toString(), listOf(null to "全部收藏状态", "true" to "已收藏", "false" to "未收藏"), enabled) { onChange(filter.copy(favorite = it?.toBooleanStrict())) }
        if (filter.selection.active) TextButton(enabled = enabled, onClick = {
            onChange(filter.copy(promptId = null, versionId = null, status = null, category = null, favorite = null, search = null, tag = null))
        }) { Text("清除库筛选") }
    }
    if (filter.promptId != null) Text(if (filter.versionId != null) "限定同一提示词版本" else "限定同一提示词", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun LibrarySearch(filter: NsfwGalleryFilter, enabled: Boolean, onChange: (NsfwGalleryFilter) -> Unit) {
    var search by remember(filter.search) { mutableStateOf(filter.search.orEmpty()) }
    var tag by remember(filter.tag) { mutableStateOf(filter.tag.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (filter.search != null || filter.tag != null) "搜索/标签筛选已启用" else "搜索与文字标签") }
    if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OutlinedTextField(value = search, onValueChange = { search = it }, enabled = enabled, singleLine = true,
            label = { Text("提示词名称/历史正文/生成快照/导入文件名") }, supportingText = { Text("字面关键词，最多200字") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tag, onValueChange = { tag = it }, enabled = enabled, singleLine = true,
            label = { Text("提示词当前版本文字标签") }, supportingText = { Text("精确匹配，最多100字；不代表生成时版本标签") }, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(enabled = enabled, onClick = { onChange(filter.copy(search = search, tag = tag)) }) { Text("应用搜索") }
            TextButton(enabled = enabled, onClick = { search = ""; tag = ""; onChange(filter.copy(search = null, tag = null)) }) { Text("清除搜索") }
        }
    }
}

@Composable
private fun LibraryFilterMenu(label: String, value: String?, options: List<Pair<String?, String>>, enabled: Boolean, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = { open = true }) { Text(options.firstOrNull { it.first == value }?.second ?: label) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(key) }) }
        }
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
