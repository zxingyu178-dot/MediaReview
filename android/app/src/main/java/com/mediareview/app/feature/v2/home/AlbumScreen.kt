package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2TypeFilter
import com.mediareview.app.feature.v2.viewer.state.AlbumLoadStatus
import com.mediareview.app.feature.v2.ui.components.EmptyState
import com.mediareview.app.feature.v2.ui.components.ErrorState
import com.mediareview.app.feature.v2.ui.components.LoadingSkeleton
import com.mediareview.app.feature.v2.ui.components.SectionHeader
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 相册内部页（阶段 8B §12 真实分页）。
 * - 顶部：返回 + 相册名 + "XX 张照片" + ⋮（选择封面）
 * - 按日期分节的紧凑图片网格（IMAGE ONLY，不受首页过滤状态污染）：第一页 50 张，
 *   滚动接近底部自动加载下一页 —— **超过 200 张的相册不再被截断**
 * - 点击照片 → Viewer（携带已加载窗口及分页上下文，接近尾部续页，不拉全量）
 * - ⋮ → 选择封面：当前封面 ✓ 标记，点照片设为封面并保存 DataStore
 */
@Composable
fun AlbumScreen(
    vm: V2HomeViewModel,
    albumId: String,
    onBack: () -> Unit,
    onOpenImage: (V2Media, List<V2Media>, Int, Int, V2SortSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albumsState by vm.albums.collectAsState()
    val albumInline = albumsState.find { it.id == albumId }
    val albumName = albumInline?.name ?: albumId
    val coverId = albumInline?.coverImageId

    // 相册内容固定 IMAGE ONLY + 最近添加倒序（不受首页过滤/排序状态污染）
    val spec = remember {
        V2SortSpec(
            field = V2SortField.RECENT,
            order = V2SortOrder.DESC,
            typeFilter = V2TypeFilter.IMAGE,
        )
    }
    val albumState by vm.albumState.collectAsState()
    var session by remember(vm, albumId) { mutableStateOf<Long?>(null) }
    val window = albumState?.takeIf { it.albumId == albumId }
    val images = window?.items.orEmpty()
    val total = window?.total ?: 0
    var selectingCover by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(vm, albumId, spec) {
        val opened = vm.openAlbum(albumId, spec)
        session = opened
        onDispose { vm.closeAlbum(opened) }
    }

    Column(modifier = modifier.fillMaxSize().background(MediaBackground)) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = V2Spacing.Sm, vertical = V2Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MediaTextPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selectingCover) "选择相册封面" else albumName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MediaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${if (total > 0) total else images.size} 张照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaTextSecondary,
                )
            }
            if (!selectingCover) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = MediaTextPrimary,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("选择封面", color = MediaTextPrimary) },
                            onClick = {
                                menuOpen = false
                                selectingCover = true
                            },
                        )
                    }
                }
            }
        }

        if (window == null || window.status == AlbumLoadStatus.Loading) {
            LoadingSkeleton(Modifier.fillMaxSize(), showIcon = true)
            return@Column
        }

        if (window.status == AlbumLoadStatus.Error) {
            ErrorState("相册加载失败", { session?.let(vm::retryAlbum) }, Modifier.fillMaxSize())
            return@Column
        }

        if (window.status == AlbumLoadStatus.Empty) {
            EmptyState("暂无照片", "这个相册还没有可浏览的图片", Modifier.fillMaxSize())
            return@Column
        }

        // 日期分节紧凑网格（选择封面模式下点击设为封面；普通模式点击进 Viewer）
        val gridState = rememberLazyGridState()
        val gridEntries = remember(images) { buildAlbumGridEntries(images) }
        val prefetchIndex = remember(gridEntries) { { gridIndex: Int -> mediaIndexAtOrAfter(gridEntries, gridIndex) } }
        WindowCoverPrefetch(images.map(vm::coverUri), gridState, squareCovers = true,
            visibleIndexToMediaIndex = prefetchIndex)
        LaunchedEffect(gridState, session, images) {
            window.anchor?.let { anchor ->
                val gridIndex = gridEntries.indexOfFirst { it is AlbumGridEntry.Media && it.value.id == anchor.mediaId }
                if (gridIndex >= 0) gridState.scrollToItem(gridIndex, anchor.offset)
            }
            snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    val visible = gridEntries.drop(index).filterIsInstance<AlbumGridEntry.Media>().firstOrNull()?.value
                    session?.let { token -> visible?.let { vm.updateAlbumAnchor(token, HomeGridAnchor(it.id, offset)) } }
                }
        }
        LaunchedEffect(gridState, session, images.size, total, selectingCover, gridEntries) {
            snapshotFlow {
                val key = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.key
                images.indexOfLast { it.id == key }
            }
                .distinctUntilChanged()
                .collect { lastVisible ->
                    if (!selectingCover) {
                        session?.let { vm.albumNearEnd(it, lastVisible.coerceAtMost(images.lastIndex)) }
                    }
                }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).testTag("album_grid"),
            contentPadding = PaddingValues(horizontal = 3.dp, vertical = V2Spacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(
                items = gridEntries,
                key = { entry -> when (entry) {
                    is AlbumGridEntry.Header -> "day_${entry.key}"
                    is AlbumGridEntry.Media -> entry.value.id
                } },
                span = { entry -> if (entry is AlbumGridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            ) { entry ->
                when (entry) {
                    is AlbumGridEntry.Header -> SectionHeader(entry.title, Modifier.padding(horizontal = V2Spacing.Md))
                    is AlbumGridEntry.Media -> {
                        val media = entry.value
                        AlbumImageCell(
                            media = media,
                            coverUri = vm.coverUri(media),
                            isCover = media.id == coverId,
                            selectingCover = selectingCover,
                            onClick = {
                                if (selectingCover) {
                                    vm.setAlbumCover(albumId, media.id)
                                    selectingCover = false
                                } else {
                                    onOpenImage(media, images, total, window.loadedPage, spec)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (window.loadingMore) {
            Text("加载更多…", color = MediaTextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(V2Spacing.Sm))
        }
        if (window.moreError != null) {
            TextButton(onClick = { session?.let(vm::retryAlbum) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("加载更多失败 · 重试")
            }
        }
    }
}

@Composable
private fun AlbumImageCell(
    media: V2Media,
    coverUri: String,
    isCover: Boolean,
    selectingCover: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable(onClick = onClick),
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(com.mediareview.app.core.network.thumbnailProfileUrl(coverUri, com.mediareview.app.core.network.ThumbnailProfile.GRID_SMALL)).crossfade(false).size(256, 144).build(),
            contentDescription = media.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 当前封面标记
        if (isCover) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(V2Spacing.Sm)
                    .clip(RoundedCornerShape(V2Radius.Chip))
                    .background(V2Colors.TimeCapsule)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = V2Colors.Accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "封面",
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextPrimary,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
        // 选择封面模式提示
        if (selectingCover) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = if (isCover) 3.dp else 1.dp,
                        color = if (isCover) V2Colors.Accent else MediaTextSecondary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(V2Radius.Card),
                    ),
            )
        }
        if (media.isVideo) {
            Text(
                text = formatDuration(media.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MediaTextPrimary,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(V2Radius.Chip))
                    .background(V2Colors.TimeCapsule)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
