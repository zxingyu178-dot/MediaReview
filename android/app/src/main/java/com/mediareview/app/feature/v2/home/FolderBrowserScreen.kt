package com.mediareview.app.feature.v2.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.ui.components.EmptyState
import com.mediareview.app.feature.v2.ui.components.ErrorState
import com.mediareview.app.feature.v2.ui.components.LoadingSkeleton
import com.mediareview.app.feature.v2.ui.components.TopBar
import com.mediareview.app.feature.v2.viewer.state.ViewerPendingDeleteState
import com.mediareview.app.ui.theme.*

private sealed interface FolderEntry {
    val key: String
    data class Folder(val node: V2FolderNode) : FolderEntry { override val key = "folder_${node.id}" }
    data class Media(val media: V2Media) : FolderEntry { override val key = "media_${media.id}" }
}

@Composable
fun FolderBrowserScreen(
    vm: V2HomeViewModel,
    initialFolderId: String?,
    onBack: () -> Unit,
    onOpenMedia: (V2Media, List<V2Media>, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tree by vm.folderTree.collectAsState()
    val hint by vm.categoryHint.collectAsState()
    val state by vm.folderBrowserState.collectAsState()
    val mode by vm.displayMode.collectAsState()
    val sortSpec by vm.folderSortSpec.collectAsState()
    val pendingState by vm.viewerPendingDeleteState.collectAsState()
    val pendingIds = (pendingState as? ViewerPendingDeleteState.Ready)?.ids.orEmpty()
    var folderId by rememberSaveable(initialFolderId) { mutableStateOf(initialFolderId) }
    var includeDescendants by rememberSaveable { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    val largeText = LocalDensity.current.fontScale > 1.3f
    val sortButton: @Composable () -> Unit = {
        IconButton(onClick = { showSortSheet = true }, modifier = Modifier.testTag("folder_sort")) {
            Icon(Icons.AutoMirrored.Filled.Sort, "排序")
        }
    }
    val libraryRoots = tree.filter { it.parentId == null }
    val children = folderChildren(tree, folderId, sortSpec)
    val implicitRoot = implicitFolderRoot(tree)
    val effectiveFolderId = folderId ?: implicitRoot
    var showChildren by rememberSaveable(effectiveFolderId) { mutableStateOf(false) }
    val crumbs = folderBreadcrumbs(tree, folderId).let { path ->
        if (libraryRoots.size == 1 && path.size > 1) path.drop(1) else path
    }
    val rootCards = children.takeIf { effectiveFolderId == null }.orEmpty()
    val currentTitle = tree.firstOrNull { it.id == effectiveFolderId }?.name ?: "浏览文件夹"
    val parents = remember(tree) { tree.associate { it.id to it.parentId } }
    val grid = rememberLazyGridState()
    fun goBack() {
        when (val target = folderBackTarget(folderId, parents, implicitRoot)) {
            FolderBackTarget.Exit -> onBack()
            is FolderBackTarget.Parent -> folderId = target.folderId
        }
    }
    BackHandler(onBack = ::goBack)
    LaunchedEffect(effectiveFolderId, tree, includeDescendants, sortSpec) {
        vm.openFolderBrowser(effectiveFolderId, includeDescendants)
        val anchor = vm.folderBrowserState.value.anchor
        val keys = vm.folderBrowserState.value.items.map { "media_${it.id}" } + rootCards.map { "folder_${it.id}" }
        grid.scrollToItem(anchor?.indexIn(keys) ?: 0, anchor?.offset ?: 0)
    }
    DisposableEffect(vm) {
        val pendingSession = vm.openViewerPendingDelete()
        onDispose {
            vm.closeViewerPendingDelete(pendingSession)
            vm.closeFolderBrowser()
        }
    }
    val currentWindow = state.folderId == effectiveFolderId && state.sortSpec == sortSpec && state.includeDescendants == includeDescendants
    val visibleMedia = state.items.takeIf { currentWindow }.orEmpty()
    val countLabel = when {
        !currentWindow || state.loading -> "加载中…"
        state.error != null && visibleMedia.isEmpty() -> "加载失败"
        else -> "${typeFilterLabel(sortSpec.typeFilter)} ${state.total} 项"
    }
    val entries = visibleMedia.map(FolderEntry::Media) + rootCards.map(FolderEntry::Folder)
    WindowCoverPrefetch(visibleMedia.map(vm::coverUri), grid, mode, leadingItemCount = 0)
    LaunchedEffect(grid, entries) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            entries.getOrNull(index)?.let { vm.updateFolderAnchor(HomeGridAnchor(it.key, offset)) }
        }
    }
    LaunchedEffect(grid, state.loading, state.hasMore, entries.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { index ->
            if (shouldLoadNextPage(index, entries.size, state.hasMore, state.loading)) vm.nextFolderPage()
        }
    }

    Column(modifier.fillMaxSize().background(MediaBackground).statusBarsPadding()) {
        TopBar(currentTitle, onBack = ::goBack, actions = {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.testTag("folder_more")) {
                    Icon(Icons.Default.MoreVert, "更多")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("包含子文件夹媒体") },
                        onClick = { includeDescendants = !includeDescendants; menuExpanded = false },
                        trailingIcon = {
                            Switch(
                                checked = includeDescendants,
                                onCheckedChange = { includeDescendants = it; menuExpanded = false },
                                modifier = Modifier.testTag("folder_scope_toggle"),
                            )
                        },
                    )
                }
            }
        })
        BreadcrumbRow(crumbs, onRoot = { folderId = null }, onCrumb = { folderId = it })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MediaSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V2TypeFilter.entries.forEach { type ->
                FolderTypeButton(type, sortSpec.typeFilter == type, Modifier.weight(1f)) {
                    vm.setFolderSortSpec(sortSpec.copy(typeFilter = type))
                }
            }
            if (!largeText) sortButton()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = MediaSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (effectiveFolderId == null) "${children.size} 个媒体库 · 请选择目录"
                else "${if (includeDescendants) "含子文件夹" else "仅当前目录"} · $countLabel",
                color = MediaTextSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = MediaSpacing.XSmall),
            )
            if (effectiveFolderId != null && children.isNotEmpty()) {
                TextButton(onClick = { showChildren = !showChildren }, modifier = Modifier.testTag("folder_children_toggle")) {
                    Text(if (showChildren) "收起子目录" else "子目录 ${children.size}")
                }
            }
            if (largeText) sortButton()
        }
        key(effectiveFolderId) {
            AnimatedVisibility(
                visible = showChildren && effectiveFolderId != null && children.isNotEmpty(),
                enter = expandVertically(tween(180), expandFrom = Alignment.Top) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(140), shrinkTowards = Alignment.Top) + fadeOut(tween(110)),
            ) {
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = MediaSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(MediaSpacing.Small)) {
                    items(children, key = { it.id }) { node ->
                        AssistChip(
                            onClick = { if (showChildren) folderId = node.id },
                            label = { Text(node.name, maxLines = 1) },
                            enabled = showChildren,
                            modifier = Modifier.testTag("folder_${node.id}"),
                        )
                    }
                }
            }
        }
        if (vm.isServerMode) RuntimeDiagnosis(vm)
        hint?.let {
            Text(it, color = MediaTextSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = MediaSpacing.Medium, vertical = MediaSpacing.XSmall))
        }
        if (entries.isEmpty() && state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(MediaSpacing.XSmall)) { LoadingSkeleton(Modifier.fillMaxSize()) }
        } else if (entries.isEmpty() && state.error != null) {
            ErrorState(state.error ?: "无法读取目录", { vm.retryFolders(); vm.retryFolderPage() }, Modifier.weight(1f))
        } else if (entries.isEmpty() && hint == null) {
            EmptyState(
                "当前筛选暂无媒体",
                if (includeDescendants) "当前目录与筛选范围内暂无媒体" else "可切换图片/视频筛选，或在更多菜单中启用“包含子文件夹媒体”",
                Modifier.weight(1f), "重新加载", { vm.retryFolders(); vm.retryFolderPage() },
            )
        } else {
            AnchoredMediaGrid(
                entries, FolderEntry::key, mode, grid, Modifier.weight(1f),
                contentPadding = PaddingValues(MediaSpacing.XSmall),
                footer = {
                    Box(Modifier.fillMaxWidth().padding(MediaSpacing.Medium), contentAlignment = Alignment.Center) {
                        when {
                            state.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            state.error != null -> TextButton(onClick = vm::retryFolderPage) { Text("加载失败，点击重试") }
                            state.hasMore -> TextButton(onClick = vm::nextFolderPage) { Text("加载更多") }
                            state.items.isNotEmpty() -> Text("已加载全部 ${state.total} 项", color = MediaTextSecondary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
            ) { entry ->
                when (entry) {
                    is FolderEntry.Folder -> FolderCard(entry.node, mode, { folderId = entry.node.id }, Modifier.testTag(entry.key))
                    is FolderEntry.Media -> MediaCard(entry.media, vm.coverUri(entry.media),
                        { onOpenMedia(entry.media, visibleMedia, effectiveFolderId) }, Modifier.testTag(entry.key), mode,
                        pendingDelete = entry.media.id in pendingIds)
                }
            }
        }
    }
    if (showSortSheet) SortFilterSheet(sortSpec, vm::setFolderSortSpec, showSourceFilter = false) { showSortSheet = false }
}

@Composable
private fun FolderTypeButton(
    type: V2TypeFilter,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MediaTextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        modifier = modifier.heightIn(min = 48.dp).testTag("folder_type_${type.name}")
            .semantics { selected = active },
    ) {
        Text(
            typeFilterLabel(type),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BreadcrumbRow(
    crumbs: List<V2FolderNode>,
    onRoot: () -> Unit,
    onCrumb: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = MediaSpacing.Medium, vertical = MediaSpacing.XSmall),
        horizontalArrangement = Arrangement.spacedBy(MediaSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(onClick = onRoot, label = { Text("分类") }, modifier = Modifier.heightIn(min = 48.dp))
        crumbs.forEach { node ->
            Text("›", color = MediaTextSecondary)
            AssistChip(onClick = { onCrumb(node.id) }, label = { Text(node.name) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}
