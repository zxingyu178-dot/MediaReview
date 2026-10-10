package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import coil.imageLoader
import coil.request.ImageRequest
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.server.V2ServerStatus
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.perf.V2Perf
import com.mediareview.app.feature.v2.settings.displayLabel
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 下一页预取距离（单位：条）。
 *
 * Stage 8A.1 §9：一屏约 8~10 个媒体卡，原阈值 4 太小，实际使用中经常
 * "已经滚到列表底才出现下一页 Loading"。16 ≈ 1.5~2 屏，为局域网请求与
 * JSON 解析留出余量，让下一页在用户到达之前就准备好。
 */
const val PREFETCH_DISTANCE_ITEMS = 16

/** 下一屏封面预取条数（§23：只预取 6~8 张，绝不一上来预取后面 50 张）。 */
private const val PREFETCH_COVER_COUNT = 8

/** 封面预取并发（§23：1~2，不与当前可见封面争抢全部连接）。 */
private const val PREFETCH_COVER_CONCURRENCY = 2

/**
 * 是否应触发下一页加载（纯函数，便于测试）。
 *
 * 阈值见 [PREFETCH_DISTANCE_ITEMS]；`itemCount > 0` 避免空列表时 `-16` 误触发。
 */
fun shouldLoadNextPage(
    lastVisible: Int,
    itemCount: Int,
    hasMore: Boolean,
    loading: Boolean,
): Boolean = hasMore && !loading && itemCount > 0 &&
    lastVisible >= itemCount - PREFETCH_DISTANCE_ITEMS

/**
 * V2 首页：搜索栏 + 数据源状态 + 媒体/书架双模式 + 文件夹分类 + 排序/筛选 + 双列媒体网格。
 * 底部导航由 V2Root 统一管理。
 */
@Composable
fun HomeScreen(
    vm: V2HomeViewModel,
    onOpenMedia: (V2Media) -> Unit,
    onOpenSearchMedia: (V2Media, List<V2Media>) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenDataSource: () -> Unit,
    onOpenFolderBrowser: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val folders by vm.folders.collectAsState()
    val albums by vm.albums.collectAsState()
    val list by vm.currentList.collectAsState()
    val sortSpec by vm.sortSpec.collectAsState()
    val displayMode by vm.displayMode.collectAsState()
    val categoryHint by vm.categoryHint.collectAsState()
    WindowCoverPrefetch(albums.map { it.coverUrl ?: vm.mediaById(it.coverImageId.orEmpty())?.let(vm::coverUri).orEmpty() }, albumCovers = true)
    val searchActive = vm.searchActive
    val homeGridState = rememberLazyGridState()
    DisposableEffect(vm) {
        vm.resumeHomeWindow()
        onDispose { vm.suspendHomeWindow() }
    }
    LaunchedEffect(list, homeGridState) {
        vm.homeAnchor?.let { homeGridState.scrollToItem(it.indexIn(list.map { media -> media.id }), it.offset) }
        snapshotFlow { homeGridState.firstVisibleItemIndex to homeGridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> list.getOrNull(index)?.let { vm.updateHomeAnchor(HomeGridAnchor(it.id, offset)) } }
    }
    val searchGridState = rememberLazyGridState()
    var positionedSearchQuery by rememberSaveable { mutableStateOf(vm.searchQuery) }
    LaunchedEffect(vm.searchQuery) {
        if (positionedSearchQuery != vm.searchQuery) {
            searchGridState.scrollToItem(0)
            positionedSearchQuery = vm.searchQuery
        }
    }
    var showSortSheet by remember { mutableStateOf(false) }

    val layoutButton: @Composable (String) -> Unit = { tag ->
        TextButton(onClick = vm::toggleDisplayMode, modifier = Modifier.testTag(tag)) {
            Text(if (displayMode == HomeDisplayMode.TWO_COLUMN) "单列" else "双列", color = MediaTextPrimary)
        }
    }
    if (searchActive) {
        Column(modifier.fillMaxSize().background(MediaBackground).statusBarsPadding()) {
            SearchBar(true, vm.searchQuery, vm::updateSearchQuery, {}, { vm.setSearchMode(false) }, vm::commitSearch,
                Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Lg, vertical = V2Spacing.Sm))
            SearchPanel(vm, onOpenSearchMedia, modifier = Modifier.weight(1f), gridState = searchGridState)
        }
    } else {
        HomeHeaderOverlay(
            modifier = modifier.background(MediaBackground).statusBarsPadding(),
            enabled = vm.currentTab == V2HomeTab.MEDIA,
            expandedHeader = {
                DataSourceBar(vm, onOpenDataSource,
                    Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Lg, vertical = V2Spacing.Sm))
                SearchBar(false, vm.searchQuery, vm::updateSearchQuery,
                    { vm.setSearchMode(true) }, { vm.setSearchMode(false) }, vm::commitSearch,
                    Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Lg))
                ModeTabRow(vm.currentTab, { vm.setTab(it) },
                    Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Lg, vertical = V2Spacing.Xs))
                if (vm.currentTab == V2HomeTab.MEDIA) {
                    FolderChipRow(folders, null, vm.selectedRecent,
                        { vm.selectAll() }, { vm.selectRecent() },
                        { onOpenFolderBrowser(it) }, { onOpenFolderBrowser(null) })
                    Row(Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Lg),
                        verticalAlignment = Alignment.CenterVertically) {
                        SortFilterRow(sortSpec, { showSortSheet = true }, Modifier.weight(1f))
                        layoutButton("home_layout_toggle")
                    }
                    categoryHint?.let {
                        Text(it, color = MediaTextSecondary, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = V2Spacing.Lg).testTag("home_category_hint").clickable(onClick = vm::retryFolders))
                    }
                }
            },
            compactHeader = {
                Row(Modifier.fillMaxSize().padding(horizontal = V2Spacing.Md),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.setSearchMode(true) }) {
                        Icon(Icons.Default.Search, "搜索", tint = MediaTextPrimary)
                    }
                    TextButton(onClick = { onOpenFolderBrowser(null) }, modifier = Modifier.weight(1f)) {
                        Text(if (vm.selectedRecent) "最近 · 分类" else "全部 · 分类", color = MediaTextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    layoutButton("home_layout_toggle_compact")
                }
            },
        ) { inset ->
            when (vm.currentTab) {
                V2HomeTab.MEDIA -> MediaGrid(list, vm, onOpenMedia, gridState = homeGridState,
                    displayMode = displayMode,
                    contentPadding = PaddingValues(start = V2Spacing.Lg, end = V2Spacing.Lg,
                        top = inset.calculateTopPadding(), bottom = V2Spacing.Xl))
                V2HomeTab.SHELF -> ShelfGrid(albums,
                    coverFor = { id -> vm.mediaById(id)?.let(vm::coverUri) },
                    onOpenAlbum = onOpenAlbum,
                    modifier = Modifier.padding(top = inset.calculateTopPadding()))
            }
        }
    }
    if (showSortSheet) {
        SortFilterSheet(sortSpec, vm::setSortSpec, { showSortSheet = false })
    }
}

/**
 * 数据源状态条（轻量顶部提示，Stage 8A §30）：
 * - Demo 模式显示"演示数据"；Server 模式显示 在线 / 重新连接中 / 离线 / 认证失效 / 未配置；
 * - 点击打开数据源设置 Sheet（Demo ↔ 我的服务器）。
 * 不再有"启动 → ConnectScreen → 健康检查 → 才能进首页"的阻塞路径。
 */
@Composable
private fun DataSourceBar(
    vm: V2HomeViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode by vm.dataMode.collectAsState()
    val status by vm.serverStatus.collectAsState()
    val label = if (mode == V2DataMode.DEMO) "演示数据（离线）" else status.displayLabel()
    val tint = when {
        mode == V2DataMode.DEMO -> MediaTextSecondary
        status == V2ServerStatus.Online -> V2Colors.Accent
        status == V2ServerStatus.Offline || status == V2ServerStatus.AuthRejected -> MediaDanger
        else -> MediaTextSecondary
    }
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tint),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            text = "· 切换数据源",
            style = MaterialTheme.typography.labelMedium,
            color = MediaTextSecondary,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** 顶部唯一搜索框：未激活显示占位文案，点击同一框变为 TextField 并聚焦（不新增第二个输入框）。 */
@Composable
private fun SearchBar(
    active: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onActivate: () -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(V2Radius.SearchBar))
            .background(if (active) MediaSurfaceRaised else MediaSurfaceRaised),
    ) {
        if (active) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V2Spacing.Lg, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MediaTextSecondary,
                )
                val focusRequester = androidx.compose.ui.focus.FocusRequester()
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = V2Spacing.Md)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MediaTextPrimary),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MediaTextPrimary),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
                )
                androidx.compose.runtime.LaunchedEffect(active) {
                    if (active) focusRequester.requestFocus()
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭搜索",
                        tint = MediaTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onActivate)
                    .padding(horizontal = V2Spacing.Lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索",
                    tint = MediaTextSecondary,
                )
                Text(
                    text = "搜索媒体…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = V2Spacing.Md),
                )
            }
        }
    }
}

/** 媒体 / 书架双模式切换。 */
@Composable
private fun ModeTabRow(
    current: V2HomeTab,
    onSelect: (V2HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        TabChip(
            text = "媒体",
            selected = current == V2HomeTab.MEDIA,
            onClick = { onSelect(V2HomeTab.MEDIA) },
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.size(V2Spacing.Md))
        TabChip(
            text = "书架",
            selected = current == V2HomeTab.SHELF,
            onClick = { onSelect(V2HomeTab.SHELF) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(V2Radius.Chip))
            .background(if (selected) V2Colors.Accent else MediaSurfaceRaised)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MediaBackground else MediaTextPrimary,
        )
    }
}

/** 双列媒体网格（分页：滚动到底部自动加载下一页；Server 端执行 search/sort/filter）。 */
@Composable
fun MediaGrid(
    list: List<V2Media>,
    vm: V2HomeViewModel,
    onOpenMedia: (V2Media) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    displayMode: HomeDisplayMode = HomeDisplayMode.TWO_COLUMN,
    contentPadding: PaddingValues = PaddingValues(start = V2Spacing.Lg, end = V2Spacing.Lg,
        top = V2Spacing.Sm, bottom = V2Spacing.Xl),
) {
    val loading by vm.listLoading.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val total by vm.totalCount.collectAsState()
    val error by vm.listError.collectAsState()

    // 首屏媒体真正渲染出来的时刻（相对 home 会话起点，单调时钟）
    LaunchedEffect(list.isEmpty()) {
        if (list.isNotEmpty()) {
            V2Perf.home()?.markOnce("first_media_render", "first_media_render")
        }
    }

    WindowCoverPrefetch(list.map(vm::coverUri), gridState, displayMode)

    // 滚动接近底部 → 加载下一页（每页 50 条；绝不在进入页面时拉全量）
    LaunchedEffect(gridState, hasMore, loading) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, itemCount) ->
                if (shouldLoadNextPage(lastVisible, itemCount, hasMore, loading)) {
                    vm.loadNextPage()
                }
            }
    }

    if (list.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) {
                    SkeletonCards()
                } else if (error != null) {
                    Text(text = error ?: "加载失败", color = MediaTextSecondary, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.retryList() }) {
                        Text("重试", color = MediaTextPrimary)
                    }
                } else {
                    Text("暂无媒体", color = MediaTextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        return
    }

    AnchoredMediaGrid(
        list = list,
        keyOf = { it.id },
        displayMode = displayMode,
        gridState = gridState,
        modifier = modifier,
        contentPadding = contentPadding,
        footer = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = V2Spacing.Md),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> Text("加载中…", color = MediaTextSecondary, style = MaterialTheme.typography.bodySmall)
                    error != null -> TextButton(onClick = { vm.retryList() }) {
                        Text("加载失败，点击重试", color = MediaTextPrimary)
                    }
                    hasMore -> Text("继续下滑加载更多", color = MediaTextSecondary, style = MaterialTheme.typography.bodySmall)
                    else -> Text(
                        text = "已加载全部 $total 项",
                        color = MediaTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    ) { media ->
            MediaCard(
                media = media,
                displayMode = displayMode,
                modifier = Modifier.testTag("media_${media.id}"),
                coverUri = vm.coverUri(media),
                onClick = { onOpenMedia(media) },
            )
    }
}
