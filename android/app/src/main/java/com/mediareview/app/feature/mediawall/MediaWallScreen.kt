package com.mediareview.app.feature.mediawall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import coil.compose.AsyncImage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder
import com.mediareview.app.feature.player.PlayerDestinations
import com.mediareview.app.feature.viewer.ImageViewerDestinations
import com.mediareview.app.ui.components.LoadingSkeleton
import com.mediareview.app.ui.components.MediaEmptyState
import com.mediareview.app.ui.components.MediaOfflineState
import com.mediareview.app.ui.theme.MediaSpacing
import com.mediareview.app.ui.theme.MediaControlScrim
import com.mediareview.app.ui.theme.MediaOnImmersive
import com.mediareview.app.ui.theme.MediaDimensions

const val COMPACT_MEDIA_FILTERS_TAG = "compact_media_filters"
const val MEDIA_GRID_TAG = "media_grid"

/** 媒体墙导航路由。 */
object MediaWallDestinations {
    const val MEDIA_WALL = "media_wall"
}

/** 注册媒体墙目的地。 */
fun NavGraphBuilder.mediaWallGraph(navController: NavController) {
    composable(MediaWallDestinations.MEDIA_WALL) {
        MediaWallScreen(
            onBack = { navController.popBackStack() },
            onItemClick = { media ->
                // 视频 → Media3 普通播放器;图片 → Image Viewer(都只传 mediaId)
                if (media.isVideo) {
                    navController.navigate(PlayerDestinations.build(media.media_id))
                } else {
                    navController.navigate(ImageViewerDestinations.build(media.media_id))
                }
            },
        )
    }
}

/**
 * 媒体墙:封面网格 + 封面大小调节 + 排序/类型/媒体库筛选 + 搜索 + 自动加载。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaWallScreen(
    viewModel: MediaWallViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onItemClick: (MediaSummary) -> Unit,
    showHeader: Boolean = true,
) {
    val ui by viewModel.ui.collectAsState()
    val spriteViewModel: SpriteViewModel = hiltViewModel()
    val spriteStates by spriteViewModel.states.collectAsState()
    val gridState: LazyGridState = rememberLazyGridState()

    // 接近底部自动加载
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 6) {
                viewModel.onScrollNearEnd()
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = MediaSpacing.Regular),
        ) {
            if (showHeader) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("媒体", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBack) { Text("返回") }
                }
            }

            ResponsiveMediaWallLayout(
                modifier = Modifier.weight(1f),
                compactFilters = { MediaWallFilters(ui, viewModel, compact = true) },
                stackedFilters = { MediaWallFilters(ui, viewModel, compact = false) },
            ) {
                when {
                    ui.loading && ui.items.isEmpty() -> LoadingSkeleton(
                        Modifier.align(Alignment.Center).padding(MediaSpacing.Large),
                    )

                    ui.error != null && ui.items.isEmpty() -> MediaOfflineState(
                        message = ui.error!!,
                        onRetry = viewModel::onRetry,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    ui.items.isEmpty() -> MediaEmptyState(
                        title = "暂无媒体",
                        message = "请先在整理中选择媒体库",
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(ui.gridColumns),
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                        verticalArrangement = Arrangement.spacedBy(MediaSpacing.Small),
                        horizontalArrangement = Arrangement.spacedBy(MediaSpacing.Small),
                    ) {
                        items(ui.items, key = { it.media_id }) { item ->
                            MediaCell(
                                item = item,
                                spriteState = spriteStates[item.media_id] ?: SpriteScrubState(),
                                sprite = spriteViewModel,
                                onClick = { onItemClick(item) },
                            )
                        }
                        item {
                            if (ui.loading) {
                                Box(
                                    Modifier.fillMaxWidth().padding(MediaSpacing.Regular),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResponsiveMediaWallLayout(
    modifier: Modifier = Modifier,
    compactFilters: @Composable () -> Unit,
    stackedFilters: @Composable () -> Unit,
    grid: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (compact) Modifier.testTag(COMPACT_MEDIA_FILTERS_TAG) else Modifier),
            ) {
                if (compact) compactFilters() else stackedFilters()
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(MEDIA_GRID_TAG),
                content = grid,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaWallFilters(
    ui: MediaWallUiState,
    viewModel: MediaWallViewModel,
    compact: Boolean,
) {
    val controls: @Composable () -> Unit = {
        SortMenu(ui.sortBy, ui.sortOrder, viewModel::setSort)
        TypeFilterMenu(ui.type, viewModel::setType)
        LibraryFilterMenu(
            libraries = ui.libraries,
            selectedId = ui.libraryId,
            onSelect = viewModel::onLibrarySelected,
            onAll = viewModel::onLibraryAll,
        )
        OutlinedButton(onClick = { viewModel.setExcludeFavorites(!ui.excludeFavorites) }) {
            Text(if (ui.excludeFavorites) "仅看未点赞" else "筛选未点赞")
        }
    }

    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MediaSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = ui.search,
                onValueChange = viewModel::onSearchChange,
                label = { Text("搜索") },
                singleLine = true,
                modifier = Modifier.width(MediaDimensions.CompactSearchWidth),
            )
            controls()
            Text("封面 ${ui.gridColumns} 列", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = ui.gridColumns.toFloat(),
                onValueChange = { viewModel.setGridColumns(it.toInt()) },
                valueRange = 2f..5f,
                steps = 2,
                modifier = Modifier.width(MediaDimensions.CompactSliderWidth),
            )
        }
    } else {
        OutlinedTextField(
            value = ui.search,
            onValueChange = viewModel::onSearchChange,
            label = { Text("搜索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MediaSpacing.Small))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MediaSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(MediaSpacing.Small),
            maxItemsInEachRow = 2,
        ) { controls() }
        Text("封面 ${ui.gridColumns} 列", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = ui.gridColumns.toFloat(),
            onValueChange = { viewModel.setGridColumns(it.toInt()) },
            valueRange = 2f..5f,
            steps = 2,
        )
    }
}

@Composable
private fun SortMenu(
    by: SortField,
    order: SortOrder,
    onSelect: (SortField) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("排序:${by.label} ${if (order == SortOrder.Asc) "升序" else "降序"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { f ->
                DropdownMenuItem(text = { Text(f.label) }, onClick = { onSelect(f); expanded = false })
            }
        }
    }
}

@Composable
private fun TypeFilterMenu(
    type: MediaTypeFilter,
    onSelect: (MediaTypeFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("类型:${type.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MediaTypeFilter.entries.forEach { t ->
                DropdownMenuItem(text = { Text(t.label) }, onClick = { onSelect(t); expanded = false })
            }
        }
    }
}

@Composable
private fun LibraryFilterMenu(
    libraries: List<com.mediareview.app.core.model.LibraryItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = libraries.firstOrNull { it.jellyfin_id == selectedId }?.name ?: "全部库"
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("媒体库:$selectedName")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部库") }, onClick = { onAll(); expanded = false })
            libraries.forEach { lib ->
                DropdownMenuItem(
                    text = { Text(lib.name) },
                    onClick = { onSelect(lib.jellyfin_id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun MediaCell(
    item: MediaSummary,
    spriteState: SpriteScrubState,
    sprite: SpriteViewModel,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (item.isVideo) {
                    // 视频:单击打开普通播放器;长按进入雪碧图预览,横向滑动映射时间位置
                    Modifier.longPressScrub(
                        enabled = true,
                        onTap = onClick,
                        onLongPressStart = { sprite.begin(item.media_id) },
                        onScrub = { sprite.setFraction(item.media_id, it) },
                        onScrubEnd = { sprite.end(item.media_id) },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
    ) {
        Box(Modifier.weight(1f)) {
            AsyncImage(
                model = item.cover_url,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.isVideo) {
                Text(
                    text = formatDuration(item.duration_ms),
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaOnImmersive,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(MediaSpacing.Small)
                        .background(MediaControlScrim),
                )
                SpritePreviewOverlay(
                    state = spriteState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(MediaSpacing.XSmall),
        )
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(MediaSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(MediaSpacing.Medium))
        Button(onClick = onRetry) { Text("重试") }
    }
}

private fun formatDuration(ms: Long?): String {
    if (ms == null) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
