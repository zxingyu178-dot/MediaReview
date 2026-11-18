package com.mediareview.app.feature.v2.nsfw

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

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
    onPrefetchNext: () -> Boolean = { false },
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
    var requestedDirection by remember { mutableIntStateOf(0) }
    val previous = { requestedDirection = -1; onPrevious() }
    val next = { requestedDirection = 1; onNext() }
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    val currentIdentity by rememberUpdatedState(item?.imageId)
    var previousFrame by remember { mutableStateOf<ViewerMotionFrame?>(null) }
    // 身份变化的第一次组合就有旧帧及零进度，避免新图先全亮再回退到动画起点。
    val transition = remember(item?.imageId) {
        val direction = imageMotionDirection(previousFrame?.id, item?.imageId,
            previousFrame?.index ?: -1, state.currentIndex)
        ViewerMotionTransition(
            outgoing = previousFrame.takeIf { direction != 0 },
            direction = requestedDirection.takeIf { it != 0 } ?: direction,
        )
    }
    val motion = transition.progress
    val transitionFinished = motion.value >= 1f
    val motionDirection = transition.direction
    val motionDistance = with(LocalDensity.current) { 40.dp.toPx() }
    SideEffect {
        val frame = item?.let { ViewerMotionFrame(it.imageId, state.currentIndex,
            safeNsfwImageUrl(state.baseUrl, it.previewUrl)) }
        if (previousFrame != frame) {
            if (previousFrame?.id != frame?.id) requestedDirection = 0
            previousFrame = frame
        }
    }
    LaunchedEffect(transition) {
        if (transition.outgoing != null) {
            // 冷加载时短暂保留旧预览；错误/离线也必须有界退场，露出当前图的重试入口。
            withTimeoutOrNull(1200L) { snapshotFlow { ready }.first { it } }
            motion.animateTo(1f, tween(190, easing = FastOutSlowInEasing))
        }
    }
    val latestCanPrevious by rememberUpdatedState(canPrevious)
    val latestCanNext by rememberUpdatedState(canNext)
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    var prefetchAttempted by remember(state.nextCursor) { mutableStateOf(false) }
    LaunchedEffect(item?.imageId, state.nextCursor, state.busy, state.syncing) {
        if (item != null && !state.busy && !state.syncing && !prefetchAttempted && onPrefetchNext()) {
            prefetchAttempted = true
        }
    }
    val nextPreview = item?.let { state.items.getOrNull(state.currentIndex + 1) }
    val pagePreview = state.prefetchedPageStartId?.let { id ->
        (2..3).firstNotNullOfOrNull { distance ->
            state.items.getOrNull(state.currentIndex + distance)?.takeIf { it.imageId == id }
        }
    }
    PrefetchPreview(nextPreview?.let { safeNsfwImageUrl(state.baseUrl, it.previewUrl) }, imageLoader)
    PrefetchPreview(pagePreview?.let { safeNsfwImageUrl(state.baseUrl, it.previewUrl) }, imageLoader)
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
        dismissButton = { if (transitionFinished && state.detail != null) TextButton(onClick = { messageDetailsOpen = false; onDetails() }) { Text("图片信息") } },
    )

    Box(Modifier.fillMaxSize().clipToBounds().background(Color(0xFF080B10)).testTag("nsfw_image_stage")) {
        // 出场层只有静态预览：没有手势、审核入口或 ready/scale 回调。
        transition.outgoing?.takeIf { motion.value < 1f }?.let { frame ->
            AsyncImage(model = imageLoader.request(frame.preview), imageLoader = imageLoader.loader,
                contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - motion.value
                    translationX = -motionDirection * motionDistance * motion.value
                })
        }
        if (item == null) {
            if (state.busy || state.syncing) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
            } else {
                Text("暂无待审图片", color = MediaTextSecondary, modifier = Modifier.align(Alignment.Center))
            }
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
            key(item.imageId) { Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = motion.value
                translationX = motionDirection * motionDistance * (1f - motion.value)
            }.then(gestures)) {
                ZoomableImage(preview, original, true, true, item.width ?: 0, item.height ?: 0,
                    onTap = { if (currentIdentity == item.imageId) chrome = chrome.toggle() },
                    onScaleChange = { if (currentIdentity == item.imageId) { scale = it; chrome = chrome.scaleChanged(it) } },
                    modifier = Modifier.fillMaxSize(), source = source, resetZoomToken = resetZoom,
                    onImageReady = { if (currentIdentity == item.imageId) ready = it }, fitScaleTolerance = 0.01f)
            } }
        }
        AnimatedVisibility(chrome.visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xF2080B10), Color(0xD9080B10), Color.Transparent))).statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ViewerIcon("返回图库", Icons.AutoMirrored.Filled.ArrowBack, onGallery, canNavigate)
                    Text(state.reviewPositionLabel, Modifier.weight(1f).padding(horizontal = 8.dp),
                        color = MediaTextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    if (state.newImageCount > 0) {
                        TextButton(onClick = { requestedDirection = 0; onLatest() }, enabled = !state.busy && !state.syncing,
                            contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("新图 ${state.newImageCount}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    ViewerIcon("刷新图库", Icons.Default.Refresh, { requestedDirection = 0; onRefresh() }, !state.syncing && !state.busy, loading = state.syncing)
                    Box {
                        ViewerIcon("更多操作", Icons.Default.MoreVert, { moreOpen = true })
                        DropdownMenu(moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(text = { Text("上一张") }, enabled = canPrevious, onClick = { moreOpen = false; previous() })
                            DropdownMenuItem(text = { Text("下一张") }, enabled = canNext, onClick = { moreOpen = false; next() })
                            DropdownMenuItem(text = { Text(if (showThumbnails) "收起缩略图" else "展开缩略图") },
                                onClick = { moreOpen = false; showThumbnails = !showThumbnails })
                            DropdownMenuItem(text = { Text("图片信息") }, enabled = transitionFinished && state.detail != null,
                                onClick = { moreOpen = false; onDetails() })
                            DropdownMenuItem(text = { Text("重置审核状态") }, enabled = ready && transitionFinished && !state.busy && state.detail != null,
                                onClick = { moreOpen = false; onDecision("RESET", null) })
                            DropdownMenuItem(text = { Text("解除生图配对") }, enabled = canNavigate,
                                onClick = { moreOpen = false; onDisconnect() })
                        }
                    }
                }
                if (messages.isNotEmpty() && (!messageDismissed || important)) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(importantMessage ?: messages.first(), Modifier.weight(1f).padding(start = 12.dp), color = if (important) MaterialTheme.colorScheme.error else MediaTextSecondary,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ViewerIcon("查看提示详情", Icons.Default.Info, { messageDetailsOpen = true })
                    if (!important) ViewerIcon("收起提示", Icons.Default.Close, { messageDismissed = true })
                }
            }
        }
        AnimatedVisibility(chrome.visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xED080B10), Color(0xFF080B10)))).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (showThumbnails) LazyRow(state = thumbnails, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    items(state.items, key = { it.imageId }) { thumbnail ->
                        NsfwReviewThumbnail(safeNsfwImageUrl(state.baseUrl, thumbnail.thumbnailUrl), imageLoader,
                            thumbnail.imageId == state.currentId) { if (canNavigate) { requestedDirection = 0; onSelect(thumbnail.imageId) } }
                    }
                }

                val canReview = ready && transitionFinished && !state.busy && state.detail != null
                val showActionIcons = LocalDensity.current.fontScale <= 1.3f
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            onClick = { requestedDirection = 1; onDecision("KEEP", null) },
                            enabled = canReview,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        ) {
                            if (showActionIcons) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("保留", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { requestedDirection = 1; onDecision("REJECT", null) },
                            enabled = canReview,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        ) {
                            if (showActionIcons) {
                                Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("淘汰", maxLines = 1)
                        }
                        ViewerIcon(
                            if (item?.isFavorite == true) "取消收藏" else "收藏",
                            if (item?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            onToggleFavorite,
                            enabled = canReview,
                        )
                        ViewerIcon(
                            "撤销上次审核", Icons.AutoMirrored.Filled.Undo,
                            { requestedDirection = -1; onUndo() },
                            enabled = !state.busy && state.undoDecision != null,
                        )
                    }
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
private fun PrefetchPreview(url: String?, imageLoader: NsfwImageLoader) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefetch = remember(imageLoader) { NsfwImagePrefetcher(scope, imageLoader::prefetch) }
    LaunchedEffect(url, lifecycleOwner, prefetch) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                prefetch.update(url?.takeIf(String::isNotBlank))
                awaitCancellation()
            } finally {
                prefetch.stop()
            }
        }
    }
}

@Composable
private fun ViewerIcon(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).background(Color(0x66232C3A), RoundedCornerShape(16.dp))) {
        NsfwToolbarGlyph(label, icon, enabled, loading)
    }
}

private data class ViewerMotionFrame(val id: String, val index: Int, val preview: String)

private class ViewerMotionTransition(val outgoing: ViewerMotionFrame?, val direction: Int) {
    val progress = Animatable(if (outgoing == null) 1f else 0f)
}
