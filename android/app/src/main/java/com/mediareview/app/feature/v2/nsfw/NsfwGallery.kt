package com.mediareview.app.feature.v2.nsfw

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
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
@OptIn(ExperimentalMaterial3Api::class)
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
    var filtersOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var capabilityOpen by remember { mutableStateOf(false) }
    val interaction = nsfwGalleryInteraction(state.busy, state.syncing)
    val filter = state.galleryQuery.filter
    // 未确认提交类消息即使来自通用消息字段，也不能被普通提示隐藏。
    val criticalMessage = state.message?.takeIf { message ->
        listOf("未知", "未确认", "无法确认", "待核对", "核对回执", "冲突").any { it in message }
    }
    val ordinaryMessages = listOfNotNull(state.browseRecoveryMessage, state.message?.takeIf { it != criticalMessage }).distinct()
    var messageExpanded by remember(ordinaryMessages) { mutableStateOf(false) }
    var messageDismissed by remember(ordinaryMessages) { mutableStateOf(false) }
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
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("生图", color = MediaTextPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(if (state.galleryEnabled) "${state.galleryTotal} 张" else "已载 ${state.items.size} 张",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MediaTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Box {
                TextButton(onClick = { if (state.galleryEnabled) sortOpen = true else capabilityOpen = true },
                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (!state.galleryEnabled) "需更新电脑端" else if (filter.sort == NsfwGallerySort.NEWEST) "最新" else "最早",
                        maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    if (state.galleryEnabled) Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                    NsfwGallerySort.entries.forEach { sort ->
                        DropdownMenuItem(text = { Text(sort.label) }, enabled = interaction.canChangeQuery,
                            onClick = { sortOpen = false; onChangeFilter(filter.copy(sort = sort)) })
                    }
                }
            }
            GalleryIconButton("筛选", Icons.Default.FilterList, { filtersOpen = true })
            GalleryIconButton("刷新图库", Icons.Default.Refresh, onRefresh,
                enabled = interaction.canRefresh, loading = state.syncing)
            Box {
                GalleryIconButton("更多操作", Icons.Default.MoreVert, { moreOpen = true })
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    if (state.galleryEnabled) DropdownMenuItem(text = { Text("查看最新") },
                        enabled = interaction.canChangeQuery,
                        onClick = { moreOpen = false; onLatest() })
                    DropdownMenuItem(text = { Text("9图批量审核") }, enabled = !state.busy,
                        onClick = { moreOpen = false; onOpenBatch() })
                    DropdownMenuItem(text = { Text(if (state.hasBrowsePosition) "继续上次" else "开始审核") },
                        enabled = (state.currentItem != null || state.hasBrowsePosition) && !state.busy,
                        onClick = { moreOpen = false; onResume() })
                    DropdownMenuItem(text = { Text("解除生图配对") }, enabled = !state.busy && state.panel?.submitting != true,
                        onClick = { moreOpen = false; onDisconnect() })
                }
            }
        }
        // 电脑端有排队 / 生成 / 暂停任务时，在生图库顶部同步只读进度；空闲时不占位。
        AnimatedVisibility(
            visible = state.generationProgress?.visibleInGallery(state.generationProgressEnabled) == true,
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(140)),
        ) {
            state.generationProgress?.takeIf { it.isGenerating }?.let { GenerationProgressStrip(it) }
        }
        if (filter.selection.active || filter.dates != NsfwGalleryDates.ALL || state.newImageCount > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (filter.selection.active || filter.dates != NsfwGalleryDates.ALL) {
                    TextButton(onClick = { filtersOpen = true }) { Text(when (filter.status) {
                        "NEW" -> "只看未审核"; "NOT_REJECTED" -> "隐藏已淘汰"
                        "KEEP" -> "已保留"; "REJECT" -> "已淘汰"; "ARCHIVED" -> "已归档"
                        else -> "筛选已启用"
                    }) }
                    TextButton(enabled = interaction.canChangeQuery, onClick = {
                        onChangeFilter(NsfwGalleryFilter(sort = filter.sort))
                    }) { Text("清除") }
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = state.newImageCount > 0,
                    enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.88f, animationSpec = tween(160)),
                    exit = fadeOut(tween(100)),
                ) {
                    TextButton(
                        onClick = { if (state.galleryEnabled) onLatest() else capabilityOpen = true },
                        enabled = interaction.canChangeQuery) {
                        AnimatedContent(
                            targetState = state.newImageCount,
                            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
                            label = "nsfw-gallery-new-count",
                        ) { count -> Text("新图 $count") }
                    }
                }
            }
        }
        if (ordinaryMessages.isNotEmpty() && !messageDismissed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ordinaryMessages.joinToString("；"), color = MediaTextSecondary,
                    modifier = Modifier.weight(1f).clickable { messageExpanded = !messageExpanded },
                    maxLines = if (messageExpanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRefresh, enabled = interaction.canRefresh) { Text("重试") }
                TextButton(onClick = { messageDismissed = true }) { Text("收起") }
            }
        }
        // 冲突、未知结果与筛选归属必须持续可见，不参与普通提示的收起。
        criticalMessage?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        state.conflictMessage?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        state.evaluationRecoveryHint?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (state.galleryMembershipUnknown) Text("筛选归属待确认，请重新查询", modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
        if (state.bidirectionalGallery && state.previousPageCursor != null) TextButton(onClick = onLoadPrevious, enabled = interaction.canRefresh) {
            Text("加载前页")
        }
        // 下拉刷新与顶部刷新按钮共用同一次有界增量同步；仅非空图库可下拉。
        PullToRefreshBox(
            isRefreshing = state.syncing && state.items.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("nsfw_gallery_pull_refresh"),
        ) {
            if (state.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.PhotoLibrary, null, Modifier.size(32.dp), tint = MediaTextSecondary)
                        Text(if (state.syncing) "正在同步图库…" else if (filter.selection.active || filter.dates != NsfwGalleryDates.ALL) "无匹配图片" else "暂无图片",
                            color = MediaTextSecondary, style = MaterialTheme.typography.bodyMedium)
                        // 空结果给出直接出路：筛选导致为空时可一键清除，否则重新同步。
                        if (!state.syncing) {
                            val narrowed = filter.selection.active || filter.dates != NsfwGalleryDates.ALL
                            TextButton(
                                onClick = {
                                    if (narrowed) onChangeFilter(NsfwGalleryFilter(sort = filter.sort))
                                    else onRefresh()
                                },
                                enabled = if (narrowed) interaction.canChangeQuery else interaction.canRefresh,
                            ) { Text(if (narrowed) "清除筛选" else "刷新图库") }
                        }
                    }
                }
            } else LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), state = grid,
                modifier = Modifier.fillMaxSize().testTag("nsfw_gallery_grid"),
                contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.items, key = { _, item -> item.imageId }) { index, item ->
                    GalleryImage(item, index + 1 + if (state.bidirectionalGallery && state.galleryPositionValid) state.galleryStartIndex else 0, state.baseUrl, imageLoader, item.imageId == state.currentId, !state.busy && state.panel?.submitting != true) { onSelect(item.imageId) }
                }
            }
        }
        if (state.hasMore) TextButton(onClick = onLoadMore, enabled = !state.syncing) { Text("加载更多") }
    }
    if (filtersOpen) ModalBottomSheet(onDismissRequest = { filtersOpen = false }, containerColor = Color(0xFF181E28)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("筛选生图", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { filtersOpen = false }) { Text("完成") }
            }
            if (state.galleryEnabled) GalleryFilters(filter, interaction.canChangeQuery, onChangeFilter)
            else Text("排序与日期不可用 · 请更新电脑端", modifier = Modifier.padding(16.dp))
            if (state.galleryFiltersEnabled) LibraryFilters(filter, interaction.canChangeQuery, state.galleryReviewFilterEnabled, onChangeFilter)
            else Text("分类、状态与收藏筛选需更新电脑端", modifier = Modifier.padding(16.dp))
            if (state.gallerySearchEnabled) LibrarySearch(filter, interaction.canChangeQuery, onChangeFilter, onApplied = { filtersOpen = false })
            else Text("搜索与标签不可用 · 请更新电脑端", modifier = Modifier.padding(16.dp))
        }
    }
    if (capabilityOpen) AlertDialog(onDismissRequest = { capabilityOpen = false },
        title = { Text("需更新电脑端") },
        text = { Text("排序需更新电脑端。当前按队列顺序显示。") },
        confirmButton = { TextButton(onClick = { capabilityOpen = false }) { Text("知道了") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryIconButton(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            NsfwToolbarGlyph(label, icon, enabled, loading)
        }
    }
}

@Composable
internal fun NsfwToolbarGlyph(label: String, icon: ImageVector, enabled: Boolean, loading: Boolean) {
    Box(Modifier.size(28.dp).semantics {
        contentDescription = label
        if (loading) stateDescription = "同步中"
    }, contentAlignment = Alignment.Center) {
        val color = MaterialTheme.colorScheme.onSurface
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
        else Icon(icon, contentDescription = null, tint = if (enabled) color else color.copy(alpha = 0.38f))
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
private fun LibraryFilters(filter: NsfwGalleryFilter, enabled: Boolean, reviewFilterEnabled: Boolean, onChange: (NsfwGalleryFilter) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val statuses = listOf(null to "全部状态", "NEW" to "只看未审核", "KEEP" to "已保留", "REJECT" to "已淘汰", "ARCHIVED" to "已归档") +
            if (reviewFilterEnabled) listOf("NOT_REJECTED" to "隐藏已淘汰") else emptyList()
        LibraryFilterMenu("状态", filter.status, statuses, enabled) { onChange(filter.copy(status = it)) }
        LibraryFilterMenu("分类", filter.category, listOf(null to "全部分类", "anime" to "动漫", "imported" to "导入"), enabled) { onChange(filter.copy(category = it)) }
        LibraryFilterMenu("收藏", filter.favorite?.toString(), listOf(null to "全部收藏状态", "true" to "已收藏", "false" to "未收藏"), enabled) { onChange(filter.copy(favorite = it?.toBooleanStrict())) }
        if (filter.selection.active) TextButton(enabled = enabled, onClick = {
            onChange(filter.copy(promptId = null, versionId = null, status = null, category = null, favorite = null, search = null, tag = null))
        }) { Text("清除库筛选") }
    }
    if (filter.promptId != null) Text(if (filter.versionId != null) "限定同一提示词版本" else "限定同一提示词", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun LibrarySearch(
    filter: NsfwGalleryFilter,
    enabled: Boolean,
    onChange: (NsfwGalleryFilter) -> Unit,
    onApplied: () -> Unit,
) {
    var search by remember(filter.search) { mutableStateOf(filter.search.orEmpty()) }
    var tag by remember(filter.tag) { mutableStateOf(filter.tag.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // 应用后收起面板：键盘会随之关闭，结果立即可见；再次展开可继续调整。
    val apply = {
        onChange(filter.copy(search = search, tag = tag))
        focusManager.clearFocus()
        onApplied()
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (filter.search != null || filter.tag != null) "搜索/标签筛选已启用" else "搜索与文字标签") }
    if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OutlinedTextField(value = search, onValueChange = { search = it }, enabled = enabled, singleLine = true,
            label = { Text("搜索提示词或文件名") }, supportingText = { Text("关键词，最多200字") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { apply() }),
            trailingIcon = {
                IconButton(enabled = enabled, onClick = apply) {
                    Icon(Icons.Default.Search, contentDescription = "应用搜索")
                }
            },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tag, onValueChange = { tag = it }, enabled = enabled, singleLine = true,
            label = { Text("当前版本标签") }, supportingText = { Text("精确匹配，最多100字 · 非生成版本标签") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { apply() }),
            modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(enabled = enabled, onClick = apply) { Text("应用搜索") }
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
    val statusLabel = when (item.status) {
        "KEEP", "KEPT" -> "已保留"
        "REJECT", "REJECTED" -> "已淘汰"
        "NEW", "PENDING" -> "待审核"
        else -> "状态待确认"
    }
    val statusIcon = when (item.status) {
        "KEEP", "KEPT" -> Icons.Default.Check
        "REJECT", "REJECTED" -> Icons.Default.Close
        "NEW", "PENDING" -> Icons.Default.Schedule
        else -> Icons.Default.HelpOutline
    }
    val url = safeNsfwImageUrl(baseUrl, item.thumbnailUrl)
    val request = remember(loader, url, attempt) { loader.request(url) }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF202630))
        .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, onClick = onOpen)) {
        key(attempt) {
            AsyncImage(model = request, imageLoader = loader.loader,
                contentDescription = "第 $ordinal 张生图缩略图，$statusLabel${if (selected) "，当前" else ""}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onLoading = { failed = false }, onError = { failed = true }, onSuccess = { failed = false })
        }
        if (failed) TextButton(onClick = { attempt++; failed = false }, modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000))) { Text("加载失败 · 重试") }
        Icon(statusIcon, contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000)).padding(4.dp).size(16.dp),
            tint = Color.White)
    }
}
