package com.mediareview.app.feature.v2.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    val pendingState by vm.viewerPendingDeleteState.collectAsState()
    val pendingIds = (pendingState as? ViewerPendingDeleteState.Ready)?.ids.orEmpty()
    var folderId by rememberSaveable(initialFolderId) { mutableStateOf(initialFolderId) }
    var includeDescendants by rememberSaveable { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    val libraryRoots = tree.filter { it.parentId == null }
    val children = if (folderId == null) homeFolderRoots(tree) else tree.filter { it.parentId == folderId }
    val implicitRoot = libraryRoots.singleOrNull()?.takeIf { it.childCount > 0 }?.id
    val effectiveFolderId = folderId ?: implicitRoot
    val crumbs = folderBreadcrumbs(tree, folderId).let { path ->
        if (libraryRoots.size == 1 && path.size > 1) path.drop(1) else path
    }
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
    LaunchedEffect(effectiveFolderId, tree, includeDescendants) {
        vm.openFolderBrowser(effectiveFolderId, includeDescendants)
        val anchor = vm.folderBrowserState.value.anchor
        val keys = children.map { "folder_${it.id}" } + vm.folderBrowserState.value.items.map { "media_${it.id}" }
        grid.scrollToItem(anchor?.indexIn(keys) ?: 0, anchor?.offset ?: 0)
    }
    DisposableEffect(vm) {
        val pendingSession = vm.openViewerPendingDelete()
        onDispose {
            vm.closeViewerPendingDelete(pendingSession)
            vm.closeFolderBrowser()
        }
    }
    val visibleMedia = state.items.takeIf { state.folderId == effectiveFolderId }.orEmpty()
    val entries = children.map(FolderEntry::Folder) + visibleMedia.map(FolderEntry::Media)
    WindowCoverPrefetch(visibleMedia.map(vm::coverUri), grid, mode, leadingItemCount = children.size)
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
                        text = { Text(if (includeDescendants) "包含子文件夹" else "仅当前文件夹") },
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
                "此文件夹为空",
                if (includeDescendants) "当前目录与筛选范围内暂无媒体" else "可在更多菜单中启用“包含子文件夹”",
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
        AssistChip(onClick = onRoot, label = { Text("分类") })
        crumbs.forEach { node ->
            Text("›", color = MediaTextSecondary)
            AssistChip(onClick = { onCrumb(node.id) }, label = { Text(node.name) })
        }
    }
}
