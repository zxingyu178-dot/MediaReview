package com.mediareview.app.feature.v2.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.perf.V2Perf
import com.mediareview.app.feature.v2.ui.components.EmptyState
import com.mediareview.app.feature.v2.ui.components.ErrorState
import com.mediareview.app.feature.v2.ui.components.LoadingSkeleton
import com.mediareview.app.feature.v2.ui.components.TopBar
import com.mediareview.app.feature.v2.viewer.state.ViewerPendingDeleteState
import com.mediareview.app.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PREFETCH_DISTANCE_ITEMS = 16

fun shouldLoadNextPage(lastVisible: Int, itemCount: Int, hasMore: Boolean, loading: Boolean): Boolean =
    hasMore && !loading && itemCount > 0 && lastVisible >= itemCount - PREFETCH_DISTANCE_ITEMS

@OptIn(ExperimentalMaterial3Api::class)
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
    val albumError by vm.albumError.collectAsState()
    val list by vm.currentList.collectAsState()
    val sortSpec by vm.sortSpec.collectAsState()
    val displayMode by vm.displayMode.collectAsState()
    val categoryHint by vm.categoryHint.collectAsState()
    val capabilities by vm.serverCapabilities.collectAsState()
    val pendingState by vm.viewerPendingDeleteState.collectAsState()
    val pendingIds = (pendingState as? ViewerPendingDeleteState.Ready)?.ids.orEmpty()
    val folderFlowEnabled = !vm.isServerMode || "folder_tree_v1" in capabilities
    val homeGridState = rememberLazyGridState()
    val searchGridState = rememberLazyGridState()
    var positionedSearchQuery by rememberSaveable { mutableStateOf(vm.searchQuery) }
    var showSortSheet by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()

    WindowCoverPrefetch(albums.map {
        it.coverUrl ?: vm.mediaById(it.coverImageId.orEmpty())?.let(vm::coverUri).orEmpty()
    }, albumCovers = true)
    DisposableEffect(vm) {
        val pendingSession = vm.openViewerPendingDelete()
        vm.resumeHomeWindow()
        onDispose {
            vm.closeViewerPendingDelete(pendingSession)
            vm.suspendHomeWindow()
        }
    }
    LaunchedEffect(list, homeGridState) {
        vm.homeAnchor?.let { homeGridState.scrollToItem(it.indexIn(list.map(V2Media::id)), it.offset) }
        snapshotFlow { homeGridState.firstVisibleItemIndex to homeGridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> list.getOrNull(index)?.let { vm.updateHomeAnchor(HomeGridAnchor(it.id, offset)) } }
    }
    LaunchedEffect(vm.searchQuery) {
        if (positionedSearchQuery != vm.searchQuery) {
            searchGridState.scrollToItem(0)
            positionedSearchQuery = vm.searchQuery
        }
    }

    if (vm.searchActive) {
        BackHandler { vm.setSearchMode(false) }
        Column(modifier.fillMaxSize().background(MediaBackground).statusBarsPadding()) {
            SearchBar(
                query = vm.searchQuery,
                onQueryChange = vm::updateSearchQuery,
                onBack = { vm.setSearchMode(false) },
                onSearch = vm::commitSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MediaSpacing.Small, vertical = MediaSpacing.XSmall),
            )
            SearchPanel(vm, onOpenSearchMedia, Modifier.weight(1f), searchGridState, pendingIds)
        }
    } else {
        HomeHeaderOverlay(
            modifier = modifier.background(MediaBackground).statusBarsPadding(),
            enabled = vm.currentTab == V2HomeTab.MEDIA,
            expandedHeader = {
                HomeTopBar(
                    displayMode = displayMode,
                    onSearch = { vm.setSearchMode(true) },
                    onAction = { action ->
                        when (action) {
                            HomeOverflowAction.SORT_FILTER -> showSortSheet = true
                            HomeOverflowAction.TOGGLE_LAYOUT -> vm.toggleDisplayMode()
                            HomeOverflowAction.DATA_SOURCE -> onOpenDataSource()
                            HomeOverflowAction.BROWSE_FOLDERS -> onOpenFolderBrowser(null)
                        }
                    },
                )
                ModeTabRow(vm.currentTab, vm::setTab,
                    Modifier.fillMaxWidth().padding(horizontal = MediaSpacing.Medium))
                if (vm.currentTab == V2HomeTab.MEDIA) {
                    FolderChipRow(
                        folders, null, vm.selectedRecent, vm::selectAll, vm::selectRecent,
                        onSelectFolder = { onOpenFolderBrowser(it) }, onMore = { onOpenFolderBrowser(null) },
                        folderFlowEnabled = folderFlowEnabled,
                    )
                    if (vm.isServerMode) RuntimeDiagnosis(vm)
                    categoryHint?.let {
                        Text(it, color = MediaTextSecondary, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.heightIn(min = MediaDimensions.MinimumTouchTarget)
                                .testTag("home_category_hint").clickable(onClick = vm::retryFolders)
                                .padding(horizontal = MediaSpacing.Medium))
                    }
                }
            },
            compactHeader = {
                HomeTopBar(
                    displayMode = displayMode,
                    compact = true,
                    onSearch = { vm.setSearchMode(true) },
                    onAction = { action ->
                        when (action) {
                            HomeOverflowAction.SORT_FILTER -> showSortSheet = true
                            HomeOverflowAction.TOGGLE_LAYOUT -> vm.toggleDisplayMode()
                            HomeOverflowAction.DATA_SOURCE -> onOpenDataSource()
                            HomeOverflowAction.BROWSE_FOLDERS -> onOpenFolderBrowser(null)
                        }
                    },
                )
            },
        ) { inset ->
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    if (vm.currentTab == V2HomeTab.MEDIA) vm.retryList() else vm.refreshAlbums()
                    refreshScope.launch {
                        delay(700L)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("home_pull_refresh"),
            ) {
                when (vm.currentTab) {
                    V2HomeTab.MEDIA -> MediaGrid(
                        list, vm, onOpenMedia, gridState = homeGridState, displayMode = displayMode,
                        pendingDeleteIds = pendingIds,
                        contentPadding = PaddingValues(
                            start = MediaSpacing.XSmall, end = MediaSpacing.XSmall,
                            top = inset.calculateTopPadding(), bottom = MediaSpacing.Large,
                        ),
                    )
                    V2HomeTab.SHELF -> ShelfGrid(
                        albums, coverFor = { id -> vm.mediaById(id)?.let(vm::coverUri) },
                        onOpenAlbum = onOpenAlbum, modifier = Modifier.padding(top = inset.calculateTopPadding()),
                        error = albumError, onRetry = vm::refreshAlbums,
                    )
                }
            }
        }
    }
    if (showSortSheet) SortFilterSheet(sortSpec, vm::setSortSpec, showTypeFilter = false) { showSortSheet = false }
}

@Composable
private fun HomeTopBar(
    displayMode: HomeDisplayMode,
    onSearch: () -> Unit,
    onAction: (HomeOverflowAction) -> Unit,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    TopBar(title = if (compact) "媒体库" else "媒体库", actions = {
        IconButton(
            onClick = onSearch,
            modifier = Modifier.size(MediaDimensions.MinimumTouchTarget)
                .semantics { text = AnnotatedString("搜索媒体…") },
        ) { Icon(Icons.Default.Search, "搜索") }
        Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(MediaDimensions.MinimumTouchTarget)) {
                Icon(Icons.Default.MoreVert, "更多")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                homeOverflowActions(displayMode).forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = { expanded = false; onAction(item.action) },
                        modifier = if (item.action == HomeOverflowAction.TOGGLE_LAYOUT) Modifier.testTag("home_layout_toggle") else Modifier,
                    )
                }
            }
        }
    })
}

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    Surface(modifier, color = MediaSurfaceRaised, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().heightIn(min = MediaDimensions.MinimumTouchTarget), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, "关闭搜索") }
            Icon(Icons.Default.Search, null, tint = MediaTextSecondary)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(horizontal = MediaSpacing.Small).focusRequester(focusRequester),
                singleLine = true,
                cursorBrush = SolidColor(MediaAccent),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MediaTextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner ->
                    if (query.isBlank()) Text("搜索媒体…", color = MediaTextSecondary)
                    inner()
                },
            )
            if (query.isNotBlank()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "清空") }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun ModeTabRow(current: V2HomeTab, onSelect: (V2HomeTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(MediaSurfaceRaised, MaterialTheme.shapes.large)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(V2HomeTab.MEDIA to "视频", V2HomeTab.SHELF to "相册").forEach { (tab, label) ->
            val selectedTab = current == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MediaDimensions.MinimumTouchTarget)
                    .background(if (selectedTab) MediaAccent else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.medium)
                    .clickable { onSelect(tab) }
                    .semantics { selected = selectedTab }
                    .testTag("home_segment_${tab.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selectedTab) MediaOnImmersive else MediaTextSecondary,
                )
            }
        }
    }
}

@Composable
fun MediaGrid(
    list: List<V2Media>,
    vm: V2HomeViewModel,
    onOpenMedia: (V2Media) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    displayMode: HomeDisplayMode = HomeDisplayMode.TWO_COLUMN,
    contentPadding: PaddingValues = PaddingValues(MediaSpacing.XSmall),
    pendingDeleteIds: Set<String> = emptySet(),
) {
    val loading by vm.listLoading.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val total by vm.totalCount.collectAsState()
    val error by vm.listError.collectAsState()
    LaunchedEffect(list.isEmpty()) { if (list.isNotEmpty()) V2Perf.home()?.markOnce("first_media_render", "first_media_render") }
    WindowCoverPrefetch(list.map(vm::coverUri), gridState, displayMode)
    LaunchedEffect(gridState, hasMore, loading) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.distinctUntilChanged().collect { (last, count) ->
            if (shouldLoadNextPage(last, count, hasMore, loading)) vm.loadNextPage()
        }
    }
    if (list.isEmpty()) {
        when {
            loading -> Box(modifier.fillMaxSize().padding(contentPadding)) { LoadingSkeleton(Modifier.fillMaxSize()) }
            error != null -> ErrorState(error ?: "无法读取媒体", vm::retryList, modifier)
            else -> EmptyState("暂无媒体", "请检查筛选条件、已选媒体库及电脑端同步状态", modifier,
                "重新加载", vm::retryList)
        }
        return
    }
    AnchoredMediaGrid(
        list, V2Media::id, displayMode, gridState, modifier, contentPadding,
        footer = {
            Box(Modifier.fillMaxWidth().padding(MediaSpacing.Medium), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    error != null -> TextButton(onClick = vm::retryList) { Text("加载失败，点击重试") }
                    hasMore -> Text("继续下滑加载更多", color = MediaTextSecondary, style = MaterialTheme.typography.bodySmall)
                    else -> Text("已加载全部 $total 项", color = MediaTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) { media ->
        MediaCard(media, vm.coverUri(media), { onOpenMedia(media) }, Modifier.testTag("media_${media.id}"), displayMode,
            pendingDelete = media.id in pendingDeleteIds)
    }
}
