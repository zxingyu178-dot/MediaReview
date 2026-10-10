package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaBackground

val HOME_EXPANDED_HEADER_HEIGHT = 248.dp
val HOME_COMPACT_HEADER_HEIGHT = 56.dp

/** 内容边距从不跟随折叠状态改变；两个顶部层始终存在，只改变位移。 */
@Composable
fun HomeHeaderOverlay(
    expandedHeader: @Composable () -> Unit,
    compactHeader: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val policy = remember { HomeHeaderScrollPolicy() }
    var collapsed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val expandedHeight = HOME_EXPANDED_HEADER_HEIGHT * density.fontScale.coerceAtLeast(1f)
    val scroll = remember(density, enabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (enabled && source == NestedScrollSource.UserInput && policy.scroll(available.y / density.density)) {
                    collapsed = policy.collapsed
                }
                return Offset.Zero
            }
        }
    }
    Box(modifier.fillMaxSize().clipToBounds().nestedScroll(scroll)) {
        content(PaddingValues(top = expandedHeight))
        Column(
            Modifier.fillMaxWidth().height(expandedHeight)
                .graphicsLayer { translationY = if (collapsed && enabled) -expandedHeight.toPx() else 0f }
                .background(MediaBackground).testTag("home_header_expanded"),
        ) { expandedHeader() }
        Box(
            Modifier.fillMaxWidth().height(HOME_COMPACT_HEADER_HEIGHT)
                .graphicsLayer { translationY = if (collapsed && enabled) 0f else -HOME_COMPACT_HEADER_HEIGHT.toPx() }
                .background(MediaBackground).testTag("home_header_compact"),
        ) { compactHeader() }
    }
}

/** 保留同一 LazyVerticalGrid 组件，仅列数变化时按身份和像素偏移恢复窗口。 */
@Composable
fun <T> AnchoredMediaGrid(
    list: List<T>,
    keyOf: (T) -> String,
    displayMode: HomeDisplayMode,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    footer: @Composable () -> Unit = {},
    itemContent: @Composable (T) -> Unit,
) {
    val ids = list.map(keyOf)
    var previousMode by remember { mutableStateOf(displayMode) }
    // remember 在新布局测量之前捕获旧布局；媒体身份比条目下标稳定。
    val pendingAnchor = remember(displayMode) {
        if (previousMode != displayMode) {
            ids.getOrNull(gridState.firstVisibleItemIndex)?.let { HomeGridAnchor(it, gridState.firstVisibleItemScrollOffset) }
        } else null
    }
    LaunchedEffect(displayMode) {
        pendingAnchor?.let { gridState.scrollToItem(it.indexIn(ids), it.offset) }
        previousMode = displayMode
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(displayMode.columns),
        modifier = modifier.fillMaxSize().testTag("home_grid"),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = keyOf) { itemContent(it) }
        item(span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}
