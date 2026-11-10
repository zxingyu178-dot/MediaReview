package com.mediareview.app.feature.v2.nsfw

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediareview.app.feature.v2.nsfw.data.*
import com.mediareview.app.feature.v2.nsfw.di.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException

/** 冻结版本与服务端范围；翻页失败保留原页，不读完整图库。 */
@Composable
internal fun NsfwPromptComparisonScreen(
    state: NsfwUiState, initial: NsfwPromptComparison, loader: NsfwImageLoader,
    originalOpen: Boolean, onClose: () -> Unit, onOpenOriginal: (NsfwFeedItem) -> Unit,
    onScore: (NsfwPromptComparison) -> Unit, onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val source = remember(context) { EntryPointAccessors.fromApplication(context, NsfwEntryPoint::class.java).nsfwBatchSource() }
    var scope by remember(initial) { mutableStateOf(initial) }
    var requestedSize by remember(initial) { mutableIntStateOf(initial.pageSize) }
    var page by remember(initial) { mutableStateOf<NsfwGalleryPage?>(null) }
    var cursor by remember(initial) { mutableStateOf<String?>(null) }
    var anchor by remember(initial) { mutableStateOf<String?>(null) }
    var request by remember(initial) { mutableIntStateOf(0) }
    var loading by remember(initial) { mutableStateOf(true) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    var batch by remember(initial) { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    LaunchedEffect(initial, request) {
        loading = true; error = null
        try {
            val requestedScope = scope.withPageSize(requestedSize)
            val response = source.gallery(requestedScope.query, pageCursor = cursor, scopeCursor = page?.scopeCursor, anchorId = anchor, limit = requestedScope.pageSize)
            requestedScope.validatePage(response.items)
            scope = requestedScope
            page = response
            grid.scrollToItem(0)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "对比页加载失败，请重试" }
        finally { loading = false }
    }
    if (batch) {
        NsfwBatchReviewScreen(state.copy(items = page?.items.orEmpty(), hasMore = false), loader,
            onClose = { batch = false; requestedSize = scope.pageSize; cursor = null; anchor = page?.items?.firstOrNull()?.imageId; request++ },
            onRefresh = onChanged, onLoadMore = {}, onOpenOriginal = onOpenOriginal, originalOpen = originalOpen, pageSize = scope.pageSize)
        return
    }
    BackHandler(enabled = !originalOpen && state.panel == null) { onClose() }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose) { Text("返回审核") }
            Text("同版本对比", Modifier.weight(1f), color = Color.White)
            TextButton(onClick = { onScore(scope) }, enabled = !loading && page != null && state.panel == null) { Text("版本评分") }
        }
        Row {
            listOf(6, 10).forEach { size -> FilterChip(selected = scope.pageSize == size, enabled = !loading,
                onClick = { requestedSize = size; cursor = null; anchor = page?.items?.firstOrNull()?.imageId; request++ }, label = { Text("${size}张") }) }
            TextButton(onClick = { batch = true }, enabled = !loading && page?.items?.any { it.status == "NEW" } == true) { Text("审核本页待审核图") }
        }
        Text("范围已固定，新图不插入；评分对应整个提示词版本", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = Color.Yellow); TextButton(onClick = { request++ }, enabled = !loading) { Text("重试本次加载") } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val current = page
        if (current != null) Text(if (current.items.isEmpty()) "该版本当前范围没有图片" else "${(current.startIndex ?: 0) + 1}–${(current.startIndex ?: 0) + current.items.size} / ${current.total}", color = Color.LightGray)
        LazyVerticalGrid(GridCells.Fixed(2), state = grid, modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(current?.items.orEmpty(), key = { it.imageId }) { image ->
                Column {
                    var failed by remember(image.imageId, image.thumbnailUrl) { mutableStateOf(false) }
                    AsyncImage(model = loader.request(safeNsfwImageUrl(state.baseUrl, image.thumbnailUrl)), imageLoader = loader.loader,
                        contentDescription = "同版本图片，点击放大", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black).clickable { onOpenOriginal(image) },
                        onError = { failed = true }, onSuccess = { failed = false })
                    Text(if (failed) "缩略图失败，可点击查看原图" else when(image.status) { "NEW" -> "待审核"; "KEEP" -> "已保留"; "REJECT" -> "已淘汰"; else -> "已归档" }, color = Color.LightGray)
                }
            }
        }
        Row {
            TextButton(enabled = !loading && current?.previousPageCursor != null, onClick = { cursor = current?.previousPageCursor; anchor = null; request++ }) { Text("上一页") }
            TextButton(enabled = !loading && current?.hasMore == true, onClick = { cursor = current?.nextPageCursor; anchor = null; request++ }) { Text("下一页") }
        }
    }
}
