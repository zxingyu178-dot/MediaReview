package com.mediareview.app.feature.v2.nsfw

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mediareview.app.feature.v2.nsfw.di.NsfwImageLoader
import com.mediareview.app.feature.v2.viewer.ZoomableImage
import com.mediareview.app.feature.v2.viewer.ZoomImageSource
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlin.math.abs
import kotlinx.coroutines.awaitCancellation

/** 图片始终占据同一个视口；同步、提示和控制层不会改变它的尺寸。 */
@Composable
internal fun NsfwImageReviewPane(
    state: NsfwUiState,
    imageLoader: NsfwImageLoader,
    onGallery: () -> Unit,
    onRefresh: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (String) -> Unit,
    onDecision: (String, Boolean?) -> Unit,
    onToggleFavorite: () -> Unit,
    onUndo: () -> Unit,
    onDetails: () -> Unit,
    onLatest: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val item = state.currentItem
    var chrome by remember(item?.imageId) { mutableStateOf(NsfwViewerChrome()) }
    var scale by remember(item?.imageId) { mutableFloatStateOf(1f) }
    var ready by remember(item?.imageId, item?.previewUrl, item?.originalUrl) { mutableStateOf(false) }
    var resetZoom by remember(item?.imageId) { mutableIntStateOf(0) }
    var moreOpen by remember { mutableStateOf(false) }
    var showThumbnails by rememberSaveable { mutableStateOf(false) }
    val thumbnails = rememberLazyListState()
    val canNavigate = !state.busy && state.panel?.submitting != true
    val canPrevious = canNavigate && (state.currentIndex > 0 || state.bidirectionalGallery && state.previousPageCursor != null)
    val canNext = canNavigate && (state.currentIndex + 1 < state.items.size || state.bidirectionalGallery && state.hasMore)
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestNext by rememberUpdatedState(onNext)
    val latestCanPrevious by rememberUpdatedState(canPrevious)
    val latestCanNext by rememberUpdatedState(canNext)
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefetch = remember(imageLoader) { NsfwImagePrefetcher(scope, imageLoader::prefetch) }
    val nextPreview = state.items.getOrNull(state.currentIndex + 1)?.let { safeNsfwImageUrl(state.baseUrl, it.previewUrl) }
    DisposableEffect(prefetch) { onDispose { prefetch.stop() } }
    LaunchedEffect(item?.imageId, ready, nextPreview, state.busy, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                if (ready && !state.busy) prefetch.update(nextPreview) else prefetch.stop()
                awaitCancellation()
            } finally { prefetch.stop() }
        }
    }
    LaunchedEffect(item?.imageId, showThumbnails) {
        if (showThumbnails && state.currentIndex >= 0) thumbnails.scrollToItem(state.currentIndex)
    }
    BackHandler(enabled = scale > 1.01f) { resetZoom++; chrome = chrome.scaleChanged(1f) }
    val messages = listOfNotNull(state.conflictMessage, state.evaluationRecoveryHint, state.browseRecoveryMessage, state.message).distinct()
    var messageDismissed by remember(messages) { mutableStateOf(false) }
    var messageDetailsOpen by remember { mutableStateOf(false) }
    val importantMessage = state.conflictMessage ?: state.evaluationRecoveryHint ?: messages.firstOrNull { message ->
        listOf("未确认", "未知", "待核对", "冲突").any(message::contains)
    }
    val important = importantMessage != null
    if (messageDetailsOpen && messages.isNotEmpty()) AlertDialog(
        onDismissRequest = { messageDetailsOpen = false }, title = { Text(if (important) "结果需核对" else "连接提示") },
        text = { Text(messages.joinToString("\n\n")) },
        confirmButton = { TextButton(onClick = { messageDetailsOpen = false }) { Text("关闭") } },
        dismissButton = { if (state.detail != null) TextButton(onClick = { messageDetailsOpen = false; onDetails() }) { Text("图片信息") } },
    )

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("nsfw_image_stage")) {
        if (item == null) {
            Text("暂无图片", color = MediaTextSecondary, modifier = Modifier.align(Alignment.Center))
        } else {
            val preview = safeNsfwImageUrl(state.baseUrl, item.previewUrl)
            val original = safeNsfwImageUrl(state.baseUrl, item.originalUrl)
            val source = remember(item.imageId, preview, original, imageLoader) {
                ZoomImageSource(imageLoader.loader, imageLoader.request(preview), imageLoader.request(original, original = true))
            }
            // 达到横移阈值后先声明手势，让子级点击识别取消；双指和放大拖图仍交给缩放器。
            val gestures = Modifier.pointerInput(item.imageId, chrome.zoomed, canNavigate) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var end = down.position
                    var multiTouch = false
                    var swipeClaimed = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed || it.previousPressed } > 1) multiTouch = true
                        event.changes.firstOrNull { it.id == down.id }?.let { change ->
                            end = change.position
                            val delta = end - down.position
                            if (!multiTouch && scale <= 1.01f && canNavigate) {
                                if (!change.isConsumed && abs(delta.x) >= swipeThreshold && abs(delta.x) > abs(delta.y) * 1.5f) swipeClaimed = true
                                if (swipeClaimed) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    val delta = end - down.position
                    if (swipeClaimed && !multiTouch && scale <= 1.01f && canNavigate &&
                        abs(delta.x) >= swipeThreshold && abs(delta.x) > abs(delta.y) * 1.5f) {
                        if (delta.x < 0 && latestCanNext) latestNext()
                        else if (delta.x > 0 && latestCanPrevious) latestPrevious()
                    }
                }
            }.semantics {
                customActions = listOf(
                    CustomAccessibilityAction("上一张") { if (latestCanPrevious) { latestPrevious(); true } else false },
                    CustomAccessibilityAction("下一张") { if (latestCanNext) { latestNext(); true } else false },
                )
            }
            key(item.imageId) { Box(Modifier.fillMaxSize().then(gestures)) {
                ZoomableImage(preview, original, true, true, item.width ?: 0, item.height ?: 0,
                    onTap = { chrome = chrome.toggle() },
                    onScaleChange = { scale = it; chrome = chrome.scaleChanged(it) },
                    modifier = Modifier.fillMaxSize(), source = source, resetZoomToken = resetZoom,
                    onImageReady = { ready = it }, fitScaleTolerance = 0.01f)
            } }
        }
        AnimatedVisibility(chrome.visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(Color(0xDD121212)).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ViewerIcon("返回图库", Icons.AutoMirrored.Filled.ArrowBack, onGallery, canNavigate)
                    Text(state.reviewPositionLabel, Modifier.weight(1f), color = MediaTextPrimary, maxLines = 1)
                    ViewerIcon("刷新图库", Icons.Default.Refresh, onRefresh, !state.syncing && !state.busy)
                    Box {
                        ViewerIcon("更多操作", Icons.Default.MoreVert, { moreOpen = true })
                        DropdownMenu(moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(text = { Text("上一张") }, enabled = canPrevious, onClick = { moreOpen = false; onPrevious() })
                            DropdownMenuItem(text = { Text("下一张") }, enabled = canNext, onClick = { moreOpen = false; onNext() })
                            DropdownMenuItem(text = { Text(if (showThumbnails) "收起缩略图" else "展开缩略图") },
                                onClick = { moreOpen = false; showThumbnails = !showThumbnails })
                            if (state.undoDecision != null) DropdownMenuItem(text = { Text("撤销上次审核") }, enabled = !state.busy,
                                onClick = { moreOpen = false; onUndo() })
                            DropdownMenuItem(text = { Text("重置审核状态") }, enabled = !state.busy && state.detail != null,
                                onClick = { moreOpen = false; onDecision("RESET", null) })
                            DropdownMenuItem(text = { Text("解除生图配对") }, enabled = canNavigate,
                                onClick = { moreOpen = false; onDisconnect() })
                        }
                    }
                }
                if (messages.isNotEmpty() && (!messageDismissed || important)) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(importantMessage ?: messages.first(), Modifier.weight(1f).padding(start = 12.dp), color = if (important) MaterialTheme.colorScheme.error else MediaTextSecondary,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { messageDetailsOpen = true }) { Text("详情") }
                    if (!important) TextButton(onClick = { messageDismissed = true }) { Text("收起") }
                }
            }
        }
        AnimatedVisibility(chrome.visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Color(0xDD121212)).navigationBarsPadding().padding(8.dp)) {
                if (showThumbnails) LazyRow(state = thumbnails, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    items(state.items, key = { it.imageId }) { thumbnail ->
                        NsfwReviewThumbnail(safeNsfwImageUrl(state.baseUrl, thumbnail.thumbnailUrl), imageLoader,
                            thumbnail.imageId == state.currentId) { if (canNavigate) onSelect(thumbnail.imageId) }
                    }
                }
                if (state.newImageCount > 0) TextButton(onClick = onLatest, enabled = !state.busy && !state.syncing) { Text("新图 ${state.newImageCount}") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDecision("KEEP", null) }, enabled = ready && !state.busy && state.detail != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("保留")
                    }
                    OutlinedButton(onClick = { onDecision("REJECT", null) }, enabled = ready && !state.busy && state.detail != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("淘汰")
                    }
                    ViewerIcon(if (item?.isFavorite == true) "取消收藏" else "收藏", if (item?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        onToggleFavorite, !state.busy && state.detail != null)
                    ViewerIcon("图片信息", Icons.Default.Info, onDetails, state.detail != null)
                }
            }
        }
        // 必须核对的结果不随普通信息层消失。
        if (!chrome.visible && important) TextButton(onClick = { messageDetailsOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().background(Color(0xDD121212))) {
            Text("结果待核对", color = MaterialTheme.colorScheme.error)
        }
        if (chrome.zoomed) Text("${(scale * 100).toInt()}%", color = MediaTextSecondary,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
    }
}

@Composable
private fun ViewerIcon(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label, tint = if (enabled) MediaTextPrimary else MediaTextSecondary)
    }
}
