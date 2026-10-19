package com.mediareview.app.feature.v2.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.ui.theme.*

private sealed interface FolderEntry {
    val key: String
    data class Folder(val node: V2FolderNode) : FolderEntry { override val key = "folder_${node.id}" }
    data class Media(val media: V2Media) : FolderEntry { override val key = "media_${media.id}" }
}

/** 目录树和直接媒体在同一分页页面浏览；返回与面包屑都能回到父目录。 */
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
    var folderId by rememberSaveable(initialFolderId) { mutableStateOf(initialFolderId) }
    var includeDescendants by rememberSaveable { mutableStateOf(true) }
    val libraryRoots = tree.filter { it.parentId == null }
    val children = if (folderId == null) homeFolderRoots(tree) else tree.filter { it.parentId == folderId }
    // 单库分类主页省略虚拟 Library 层，但不能漏掉直接放在库根的媒体。
    val implicitRoot = libraryRoots.singleOrNull()?.takeIf { it.childCount > 0 }?.id
    val effectiveFolderId = folderId ?: implicitRoot
    val crumbs = folderBreadcrumbs(tree, folderId).let { path ->
        if (libraryRoots.size == 1 && path.size > 1) path.drop(1) else path
    }
    val grid = rememberLazyGridState()
    fun goBack() {
        val parent = tree.firstOrNull { it.id == folderId }?.parentId
        if (folderId == null) onBack()
        else folderId = parent?.takeUnless { it == implicitRoot }
    }
    BackHandler(onBack = ::goBack)
    LaunchedEffect(effectiveFolderId, tree, includeDescendants) {
        vm.openFolderBrowser(effectiveFolderId, includeDescendants)
        val anchor = vm.folderBrowserState.value.anchor
        val keys = children.map { "folder_${it.id}" } + vm.folderBrowserState.value.items.map { "media_${it.id}" }
        grid.scrollToItem(anchor?.indexIn(keys) ?: 0, anchor?.offset ?: 0)
    }
    DisposableEffect(vm) { onDispose { vm.closeFolderBrowser() } }
    val visibleMedia = state.items.takeIf { state.folderId == effectiveFolderId }.orEmpty()
    val entries = children.map(FolderEntry::Folder) + visibleMedia.map(FolderEntry::Media)
    WindowCoverPrefetch(visibleMedia.map(vm::coverUri), grid, mode, leadingItemCount = children.size)
    LaunchedEffect(grid, entries) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> entries.getOrNull(index)?.let { vm.updateFolderAnchor(HomeGridAnchor(it.key, offset)) } }
    }
    LaunchedEffect(grid, state.loading, state.hasMore, entries.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { index ->
            if (shouldLoadNextPage(index, entries.size, state.hasMore, state.loading)) vm.nextFolderPage()
        }
    }
    Column(modifier.fillMaxSize().background(MediaBackground).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::goBack, modifier = Modifier.testTag("folder_back")) { Text("返回", color = MediaTextPrimary) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("分类", color = MediaTextPrimary, modifier = Modifier.clickable { folderId = null }.padding(8.dp))
                crumbs.forEach { node ->
                    Text(" > ", color = MediaTextSecondary)
                    Text(node.name, color = MediaTextPrimary,
                        modifier = Modifier.clickable { folderId = node.id }.padding(8.dp))
                }
            }
        }
        if (vm.isServerMode) RuntimeDiagnosis(vm)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (includeDescendants) "包含子文件夹" else "仅当前文件夹", color = MediaTextPrimary, modifier = Modifier.weight(1f))
            Switch(checked = includeDescendants, onCheckedChange = { includeDescendants = it }, modifier = Modifier.testTag("folder_scope_toggle"))
        }
        hint?.let {
            Text(it, color = MediaTextSecondary,
                modifier = Modifier.padding(16.dp).clickable(onClick = vm::retryFolders))
        }
        AnchoredMediaGrid(entries, { it.key }, mode, grid,
            modifier = Modifier.weight(1f),
            footer = {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.loading && state.items.isEmpty() -> SkeletonCards()
                        state.loading -> Text("加载更多…", color = MediaTextSecondary)
                        state.error != null -> TextButton(onClick = vm::retryFolderPage) { Text("加载失败，点击重试") }
                        entries.isEmpty() && hint == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("当前目录与筛选范围内暂无媒体", color = MediaTextSecondary)
                            Text("请检查子文件夹开关、筛选条件和电脑端同步状态", color = MediaTextSecondary,
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.retryFolders(); vm.retryFolderPage() }) { Text("重新加载") }
                        }
                        state.hasMore -> TextButton(onClick = vm::nextFolderPage) { Text("加载更多") }
                        state.items.isNotEmpty() -> Text("已加载全部 ${state.total} 项", color = MediaTextSecondary)
                    }
                }
            },
        ) { entry ->
            when (entry) {
                is FolderEntry.Folder -> FolderCard(entry.node, mode,
                    onClick = { folderId = entry.node.id }, modifier = Modifier.testTag(entry.key))
                is FolderEntry.Media -> MediaCard(entry.media, vm.coverUri(entry.media),
                    onClick = { onOpenMedia(entry.media, visibleMedia, effectiveFolderId) },
                    displayMode = mode, modifier = Modifier.testTag(entry.key))
            }
        }
    }
}
