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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2TypeFilter
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 相册内部页（纯照片，阶段 8B §12 真实分页）。
 * - 顶部：返回 + 相册名 + "XX 张照片" + ⋮（选择封面）
 * - 双列图片网格（IMAGE ONLY，不受首页过滤状态污染）：第一页 50 张，
 *   滚动接近底部自动加载下一页 —— **超过 200 张的相册不再被截断**
 * - 点击照片 → Viewer（队列 = **已加载窗口**，不拉全量）
 * - ⋮ → 选择封面：当前封面 ✓ 标记，点照片设为封面并保存 DataStore
 */
@Composable
fun AlbumScreen(
    vm: V2HomeViewModel,
    albumId: String,
    onBack: () -> Unit,
    onOpenImage: (V2Media, List<V2Media>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
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
    var images by remember(albumId) { mutableStateOf<List<V2Media>>(emptyList()) }
    var loaded by remember(albumId) { mutableStateOf(false) }
    var total by remember(albumId) { mutableStateOf(0) }
    var loadingMore by remember(albumId) { mutableStateOf(false) }
    // 已请求到的页码（分页去重：同一页不会重复请求，也不会重复追加）
    var loadedPage by remember(albumId) { mutableStateOf(0) }
    var selectingCover by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    suspend fun loadPage(page: Int) {
        val result = vm.albumPage(albumId, page, spec)
        // 去重：以 mediaId 为准（分页边界重复或失败重试都不会产生重复项）
        val existing = images.map { it.id }.toSet()
        images = images + result.items.filterNot { it.id in existing }
        total = result.total
        loadedPage = page
    }

    LaunchedEffect(albumId) {
        images = emptyList()
        total = 0
        loadedPage = 0
        loadingMore = false
        loaded = false
        loadPage(1)
        loaded = true
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

        if (!loaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", color = MediaTextSecondary)
            }
            return@Column
        }

        if (images.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无照片", color = MediaTextSecondary)
            }
            return@Column
        }

        // 双列图片网格（选择封面模式下点击设为封面；普通模式点击进 Viewer）
        val hasMore = images.size < total
        val gridState = rememberLazyGridState()
        LaunchedEffect(gridState, images, total, loadingMore) {
            snapshotFlow {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            }
                .distinctUntilChanged()
                .collect { lastVisible ->
                    if (!selectingCover &&
                        shouldLoadNextPage(lastVisible, images.size, hasMore, loadingMore)
                    ) {
                        loadingMore = true
                        try {
                            loadPage(loadedPage + 1)
                        } finally {
                            loadingMore = false
                        }
                    }
                }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Sm, bottom = V2Spacing.Xl),
            horizontalArrangement = Arrangement.spacedBy(V2Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            items(images, key = { it.id }) { media ->
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
                            // Viewer 队列 = 已加载窗口（§12：后续再做跨页 Viewer Prefetch）
                            onOpenImage(media, images)
                        }
                    },
                )
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
            model = coverUri,
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
    }
}