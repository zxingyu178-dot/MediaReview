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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * V2 首页：搜索栏 + 媒体/书架双模式 + 文件夹分类 + 排序/筛选 + 双列媒体网格。
 * 底部导航由 V2Root 统一管理。
 */
@Composable
fun HomeScreen(
    vm: V2HomeViewModel,
    onOpenMedia: (V2Media) -> Unit,
    onOpenFolder: (V2Folder) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val folders by vm.folders.collectAsState()
    val albums by vm.albums.collectAsState()
    val list by vm.currentList.collectAsState()
    val sortSpec by vm.sortSpec.collectAsState()
    val selectedFolderId by vm.selectedFolderId.collectAsState()
    val folderCounts by vm.folderCounts.collectAsState()
    val searchActive = vm.searchActive
    val selectedRecent = vm.selectedRecent

    var showMoreSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    // 切换 Tab / 进入搜索时立即停止雪碧图预览，避免残留动画
    fun clearPreviewIfNeeded() {
        if (vm.activePreviewMediaId != null) vm.stopSpritePreview()
    }

    Column(modifier = modifier.fillMaxSize().background(MediaBackground)) {
        SearchBar(
            active = searchActive,
            query = vm.searchQuery,
            onQueryChange = vm::updateSearchQuery,
            onActivate = {
                clearPreviewIfNeeded()
                vm.setSearchMode(true)
            },
            onClose = {
                vm.setSearchMode(false)
            },
            onSearch = vm::commitSearch,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Md),
        )

        if (!searchActive) {
            ModeTabRow(
                current = vm.currentTab,
                onSelect = {
                    clearPreviewIfNeeded()
                    vm.setTab(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V2Spacing.Lg, vertical = V2Spacing.Sm),
            )

            if (vm.currentTab == V2HomeTab.MEDIA) {
                FolderChipRow(
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    selectedRecent = selectedRecent,
                    onSelectAll = {
                        clearPreviewIfNeeded()
                        vm.selectAll()
                    },
                    onSelectRecent = {
                        clearPreviewIfNeeded()
                        vm.selectRecent()
                    },
                    onSelectFolder = {
                        clearPreviewIfNeeded()
                        vm.selectFolder(it)
                    },
                    onMore = { showMoreSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                SortFilterRow(
                    spec = sortSpec,
                    onClick = {
                        clearPreviewIfNeeded()
                        showSortSheet = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = V2Spacing.Lg, vertical = V2Spacing.Xs),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (vm.currentTab) {
                    V2HomeTab.MEDIA -> MediaGrid(
                        list = list,
                        vm = vm,
                        onOpenMedia = onOpenMedia,
                    )
                    V2HomeTab.SHELF -> ShelfGrid(
                        albums = albums,
                        coverFor = { albumId -> vm.mediaById(albumId)?.let { vm.coverUri(it) } },
                        onOpenAlbum = onOpenAlbum,
                    )
                }
            }
        } else {
            SearchPanel(
                vm = vm,
                onOpenMedia = onOpenMedia,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showMoreSheet) {
        MoreFoldersSheet(
            folders = folders,
            counts = folderCounts,
            selectedFolderId = selectedFolderId,
            onSelect = {
                vm.selectFolder(it)
                showMoreSheet = false
            },
            onDismiss = { showMoreSheet = false },
        )
    }

    if (showSortSheet) {
        SortFilterSheet(
            spec = sortSpec,
            onChange = vm::setSortSpec,
            onDismiss = { showSortSheet = false },
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

/** 双列媒体网格。 */
@Composable
fun MediaGrid(
    list: List<V2Media>,
    vm: V2HomeViewModel,
    onOpenMedia: (V2Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val previewId = vm.activePreviewMediaId

    // 用户开始滑动页面 → 立即恢复 Poster
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && vm.activePreviewMediaId != null) vm.stopSpritePreview()
            }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Sm, bottom = V2Spacing.Xl,
        ),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(V2Spacing.Md),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(V2Spacing.Md),
    ) {
        items(list, key = { it.id }) { media ->
            MediaCard(
                media = media,
                coverUri = vm.coverUri(media),
                spriteUri = vm.spriteUri(media),
                manifest = vm.spriteManifest(media),
                isSpritePreviewing = previewId == media.id && media.isVideo,
                onSpritePreviewRequest = {
                    vm.startSpritePreview(media.id)
                },
                onSpritePreviewStop = { vm.stopSpritePreview() },
                onClick = {
                    vm.stopSpritePreview()
                    onOpenMedia(media)
                },
            )
        }
    }
}
