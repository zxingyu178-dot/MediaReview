package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import com.mediareview.app.feature.v2.ui.components.EmptyState
import com.mediareview.app.feature.v2.ui.components.ErrorState
import com.mediareview.app.feature.v2.ui.components.LoadingSkeleton

/**
 * 搜索结果面板：顶部搜索框统一由 HomeScreen 维护（本组件不再有第二个输入框）。
 * 内容区按状态切换：最近搜索 / 搜索结果 / 空状态。
 */
@Composable
fun SearchPanel(
    vm: V2HomeViewModel,
    onOpenMedia: (V2Media, List<V2Media>) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    pendingDeleteIds: Set<String> = emptySet(),
) {
    val state by vm.searchState.collectAsState()
    val list = state.items
    val query = vm.searchQuery
    val history = vm.recentSearches
    LaunchedEffect(gridState, state.items.size, state.hasMore, state.loadingFirstPage, state.loadingMore, state.error) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { vm.searchNearEnd(it) }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = V2Spacing.Lg)) {
        if (query.isBlank() && history.isNotEmpty()) {
            // 最近搜索 / 历史
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = V2Spacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "最近搜索",
                    style = MaterialTheme.typography.titleSmall,
                    color = MediaTextPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::clearRecentSearches) {
                    Text("清除历史", color = MediaTextSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
            LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = V2Spacing.Xs),
                        horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
                    ) {
                        items(history) { term ->
                            HistoryChip(term, onClick = { vm.updateSearchQuery(term); vm.commitSearch() })
                        }
                    }
        }

        if (query.isBlank()) {
            if (history.isEmpty()) EmptyState("搜索媒体", "输入文件名或目录关键词", Modifier.fillMaxSize())
        } else if (list.isEmpty()) {
            when {
                state.loadingFirstPage -> LoadingSkeleton(Modifier.fillMaxSize())
                state.error != null -> ErrorState(state.error ?: "搜索失败", vm::retrySearch, Modifier.fillMaxSize())
                else -> EmptyState("没有找到匹配内容", "换个关键词试试", Modifier.fillMaxSize())
            }
        } else {
            // 独立的已加载搜索窗口。
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().testTag("search_grid"),
                contentPadding = PaddingValues(top = V2Spacing.Xs, bottom = V2Spacing.Xl),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Xs),
                verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
            ) {
                items(list, key = { it.id }) { media ->
                    MediaCard(
                        media = media,
                        coverUri = vm.coverUri(media),
                        onClick = { onOpenMedia(media, list) },
                        pendingDelete = media.id in pendingDeleteIds,
                    )
                }
                if (state.loadingMore || state.error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            if (state.loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
                            else TextButton(onClick = vm::retrySearch) { Text("加载更多失败 · 重试") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryChip(
    term: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(V2Radius.Chip))
            .background(MediaSurfaceRaised)
            .clickable(onClick = onClick)
            .sizeIn(minHeight = 48.dp)
            .padding(horizontal = V2Spacing.Lg, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = MediaTextSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = term,
            style = MaterialTheme.typography.labelLarge,
            color = MediaTextPrimary,
            modifier = Modifier.padding(start = V2Spacing.Sm),
        )
    }
}
