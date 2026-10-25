package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.mediareview.app.core.network.ThumbnailProfile
import com.mediareview.app.core.network.thumbnailProfileUrl
import com.mediareview.app.feature.v2.ui.V2Colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val coverPrefetchSlots = Semaphore(2)

/** 元数据到达就给首屏和下一屏预热，有界16，最多2并发；离开浏览页自动取消。 */
internal fun coverPrefetchIndices(count: Int, firstVisible: Int = 0): List<Int> =
    (firstVisible.coerceAtLeast(0) until minOf(count, firstVisible.coerceAtLeast(0) + 16)).toList()

internal suspend fun prefetchBoundedCovers(urls: List<String>, request: suspend (String) -> Unit) {
    for (chunk in urls.filter { it.isNotBlank() }.distinct().take(16).chunked(2)) {
        coroutineScope { chunk.map { url -> async(Dispatchers.Default) { coverPrefetchSlots.withPermit { request(url) } } }.awaitAll() }
    }
}

@Composable
internal fun WindowCoverPrefetch(
    urls: List<String>,
    grid: LazyGridState? = null,
    mode: HomeDisplayMode = HomeDisplayMode.TWO_COLUMN,
    albumCovers: Boolean = false,
    squareCovers: Boolean = false,
    leadingItemCount: Int = 0,
    visibleIndexToMediaIndex: ((Int) -> Int)? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(urls, grid, mode, albumCovers, squareCovers, leadingItemCount) {
        suspend fun warm(first: Int) {
            prefetchBoundedCovers(coverPrefetchIndices(urls.size, first).map { urls[it] }) { url ->
                val profile = if (albumCovers) ThumbnailProfile.ALBUM else if (mode == HomeDisplayMode.ONE_COLUMN) ThumbnailProfile.GRID_LARGE else ThumbnailProfile.GRID_SMALL
                context.imageLoader.execute(ImageRequest.Builder(context).data(thumbnailProfileUrl(url, profile)).crossfade(false)
                    .size(profile.width, profile.height)
                    .build())
            }
        }
        warm(0)
        if (grid != null) snapshotFlow {
            visibleIndexToMediaIndex?.invoke(grid.firstVisibleItemIndex)
                ?: (grid.firstVisibleItemIndex - leadingItemCount).coerceAtLeast(0)
        }
            .distinctUntilChanged().collectLatest { warm(it) }
    }
}

/** 没有窗口缓存时显示卡片轮廓，而不是整屏 loading。 */
@Composable
internal fun SkeletonCards(modifier: Modifier = Modifier, aspectRatio: Float = 16f / 9f) {
    Column(modifier.fillMaxWidth().padding(16.dp).testTag("window_skeleton"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) { Box(Modifier.weight(1f).aspectRatio(aspectRatio).background(V2Colors.Skeleton)) }
            }
        }
    }
}
