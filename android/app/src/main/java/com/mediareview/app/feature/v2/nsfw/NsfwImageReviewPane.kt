package com.mediareview.app.feature.v2.nsfw

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mediareview.app.feature.v2.nsfw.data.NsfwGenerationProgress
import com.mediareview.app.feature.v2.nsfw.data.nsfwStatusBadgeLabel
import com.mediareview.app.feature.v2.nsfw.di.NsfwImageLoader
import com.mediareview.app.feature.v2.viewer.ZoomableImage
import com.mediareview.app.feature.v2.viewer.ZoomImageSource
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onFilterModel: (String) -> Unit = {},
) {
    val item = state.currentItem
    val haptic = LocalHapticFeedback.current
    var chrome by remember(item?.imageId) { mutableStateOf(NsfwViewerChrome()) }
    var scale by remember(item?.imageId) { mutableFloatStateOf(1f) }
    var ready by remember(item?.imageId, item?.previewUrl, item?.originalUrl) { mutableStateOf(false) }
    val previewStartedAt = remember(item?.imageId, item?.previewUrl) { android.os.SystemClock.elapsedRealtime() }
    LaunchedEffect(item?.imageId, ready) {
        if (com.mediareview.app.BuildConfig.DEBUG && item != null && ready) {
            android.util.Log.d("MRGeneratedReview", "previewReadyMs=${android.os.SystemClock.elapsedRealtime() - previewStartedAt}")
        }
    }
    var recordedReviewReady by remember(item?.imageId, item?.previewUrl) { mutableStateOf(false) }
    var resetZoom by remember(item?.imageId) { mutableIntStateOf(0) }
    var moreOpen by remember { mutableStateOf(false) }
    var showThumbnails by rememberSaveable { mutableStateOf(false) }
    // 复制后在本页短暂显示“已复制”，不依赖系统 Toast。
    var copiedHint by remember(item?.imageId) { mutableStateOf(false) }
    LaunchedEffect(copiedHint) {
        if (copiedHint) {
            delay(2000)
            copiedHint = false
        }
    }
    // 收藏切换时图标轻微弹跳；只在用户改变状态时触发，切图不重放。
    val favoriteScale = remember(item?.imageId) { Animatable(1f) }
    var lastFavorite by remember(item?.imageId) { mutableStateOf(item?.isFavorite == true) }
    LaunchedEffect(item?.isFavorite) {
        val favorite = item?.isFavorite == true
        if (favorite != lastFavorite) {
            lastFavorite = favorite
            if (favorite) {
                favoriteScale.animateTo(1.18f, tween(90, easing = FastOutSlowInEasing))
                favoriteScale.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                )
            }
        }
    }
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
            // 动画结束后只保留当前图片或其加载状态，避免仍看到上一张。
            motion.animateTo(1f, tween(90, easing = FastOutSlowInEasing))
        }
    }
    val latestCanPrevious by rememberUpdatedState(canPrevious)
    val latestCanNext by rememberUpdatedState(canNext)
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    // 横向拖动跟随：手指按住时图片跟着平移，松手回弹或顺势切换；不改变原有阈值与缩放判定。
    // 手势检测在 AwaitPointerEventScope 里（受限挂起作用域），拖动值用普通状态更新，
    // 回弹/滑出动画放到 rememberCoroutineScope，避免在受限作用域调用 Animatable。
    var dragPx by remember(item?.imageId) { mutableFloatStateOf(0f) }
    var dragJob by remember(item?.imageId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val dragScope = rememberCoroutineScope()
    val maxDrag = with(LocalDensity.current) { 96.dp.toPx() }
    var prefetchAttempted by remember(state.nextCursor) { mutableStateOf(false) }
    LaunchedEffect(item?.imageId, state.nextCursor, state.busy, state.syncing) {
        if (item != null && !state.busy && !state.syncing && !prefetchAttempted && onPrefetchNext()) {
            prefetchAttempted = true
        }
    }
    val upcomingPreviews = if (state.currentIndex < 0) emptyList() else state.items.asSequence()
        .drop(state.currentIndex + 1).take(3)
        .map { safeNsfwImageUrl(state.baseUrl, it.previewUrl) }.filter(String::isNotBlank).toList()
    val visiblePreview = item?.let { safeNsfwImageUrl(state.baseUrl, it.previewUrl) }
    PrefetchPreviews(upcomingPreviews, visiblePreview, imageLoader)
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
    val bannerMessage = importantMessage ?: messages.firstOrNull()
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
            val thumbnail = safeNsfwImageUrl(state.baseUrl, item.thumbnailUrl)
            val source = remember(item.imageId, preview, original, thumbnail, imageLoader) {
                val previewRequest = imageLoader.request(preview).newBuilder()
                    .placeholderMemoryCacheKey(imageLoader.request(thumbnail).memoryCacheKey)
                    .build()
                ZoomImageSource(imageLoader.loader, previewRequest, imageLoader.request(original, original = true))
            }
            // 达到横移阈值后先声明手势，让子级点击识别取消；双指和放大拖图仍交给缩放器。
            val gestures = Modifier.pointerInput(item.imageId, chrome.zoomed, canNavigate, maxDrag) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    dragJob?.cancel()
                    dragPx = 0f
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
                                if (swipeClaimed) {
                                    change.consume()
                                    // 有下一张时 0.38 跟随，边界降到 0.12 做阻尼回弹，避免死拽。
                                    val canMove = if (delta.x < 0) latestCanNext else latestCanPrevious
                                    val resistance = if (canMove) 0.38f else 0.12f
                                    dragPx = (delta.x * resistance).coerceIn(-maxDrag, maxDrag)
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    val delta = end - down.position
                    if (swipeClaimed && !multiTouch && scale <= 1.01f && canNavigate &&
                        abs(delta.x) >= swipeThreshold && abs(delta.x) > abs(delta.y) * 1.5f) {
                        val forward = delta.x < 0
                        val target = when {
                            forward && latestCanNext -> -maxDrag * 1.8f
                            !forward && latestCanPrevious -> maxDrag * 1.8f
                            else -> 0f
                        }
                        val navigate = target != 0f
                        dragJob = dragScope.launch {
                            val anim = Animatable(dragPx)
                            if (navigate) {
                                anim.animateTo(target, tween(70, easing = FastOutSlowInEasing)) { dragPx = value }
                                if (forward) { requestedDirection = 1; latestNext() }
                                else { requestedDirection = -1; latestPrevious() }
                            } else {
                                anim.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium)) { dragPx = value }
                            }
                            dragPx = 0f
                        }
                    } else {
                        dragJob = dragScope.launch {
                            Animatable(dragPx).animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium)) { dragPx = value }
                        }
                    }
                }
            }.semantics {
                customActions = listOf(
                    CustomAccessibilityAction("上一张") { if (latestCanPrevious) { latestPrevious(); true } else false },
                    CustomAccessibilityAction("下一张") { if (latestCanNext) { latestNext(); true } else false },
                )
            }
            key(item.imageId) { Box(Modifier.fillMaxSize().graphicsLayer {
                val dragFraction = (abs(dragPx) / maxDrag).coerceIn(0f, 1f)
                alpha = motion.value * (1f - 0.14f * dragFraction)
                translationX = motionDirection * motionDistance * (1f - motion.value) + dragPx
            }.then(gestures)) {
                ZoomableImage(
                    previewUri = preview,
                    fullUri = original,
                    loadFullResolution = original.isNotBlank() && (scale > 1.01f || preview.isBlank()),
                    isCurrent = true,
                    naturalWidth = item.width ?: 0,
                    naturalHeight = item.height ?: 0,
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
                    val currentStatus = state.detail?.takeIf { it.imageId == item?.imageId }
                        ?.status?.takeIf { it.isNotBlank() } ?: item?.status.orEmpty()
                    val statusBadge = nsfwStatusBadgeLabel(currentStatus)
                    if (statusBadge != null) {
                        val statusColor = when (currentStatus.uppercase()) {
                            "KEEP", "KEPT" -> Color(0xFF4ADE80)
                            "REJECT", "REJECTED" -> MaterialTheme.colorScheme.error
                            else -> MediaTextSecondary
                        }
                        Text(
                            statusBadge,
                            Modifier.padding(end = 4.dp)
                                .background(statusColor.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                    AnimatedVisibility(
                        visible = state.newImageCount > 0,
                        enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.85f, animationSpec = tween(160)),
                        exit = fadeOut(tween(100)),
                    ) {
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
                val generationProgress = state.generationProgress
                AnimatedVisibility(
                    visible = state.generationProgressEnabled && generationProgress?.isGenerating == true,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    // 只读进度：仅在确有任务时出现，空闲时不占用顶栏空间。
                    if (generationProgress != null) GenerationProgressStrip(generationProgress)
                }
                // 当前图片的生成时提示词摘要与底模：点击打开“图片信息”，右侧可直接复制原文。
                val currentDetail = state.detail?.takeIf { it.imageId == item?.imageId }
                val promptPreview = currentDetail?.promptPreview
                val modelLabel = currentDetail?.modelLabel
                // 底模可点：与桌面「只看此底模」一致，回到图库并锁定同一款底模。
                val modelValue = currentDetail?.model?.trim()?.takeIf { it.isNotEmpty() }
                val canFilterModel = state.galleryModelFilterEnabled && modelValue != null
                // AI 直提来源：仅在 agent/doubao 时显示紧凑徽标，网页/续跑不占位。
                val aiSubmitted = currentDetail?.isAiSubmitted == true
                val clipboard = LocalClipboardManager.current
                AnimatedVisibility(
                    visible = promptPreview != null || modelLabel != null || aiSubmitted,
                    enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 底模：旧任务/外部导入没有该字段，自动不占位；可点时带筛选图标。
                        modelLabel?.let { model ->
                            Row(
                                Modifier.padding(start = 12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = canFilterModel) {
                                        if (canFilterModel) onFilterModel(modelValue!!)
                                    }
                                    .semantics {
                                        if (canFilterModel) contentDescription = "只看此底模 $model"
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "底模 $model",
                                    Modifier.widthIn(max = if (canFilterModel) 112.dp else 132.dp),
                                    color = MediaTextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (canFilterModel) {
                                    Icon(
                                        Icons.Default.FilterList, null,
                                        Modifier.padding(start = 2.dp).size(13.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        if (aiSubmitted) {
                            Text(
                                "AI",
                                Modifier.padding(start = if (modelLabel != null) 6.dp else 12.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                    .semantics { contentDescription = "AI 提交" },
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        Text(
                            promptPreview.orEmpty(),
                            Modifier.weight(1f)
                                .clickable(enabled = transitionFinished && state.detail != null) { onDetails() }
                                .padding(
                                    start = if (modelLabel != null) 8.dp else 12.dp,
                                    end = 4.dp, top = 4.dp, bottom = 4.dp,
                                ),
                            color = MediaTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (promptPreview != null) {
                            Box(
                                Modifier
                                    .clickable {
                                        clipboard.setText(AnnotatedString(promptPreview))
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        copiedHint = true
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                AnimatedContent(
                                    targetState = copiedHint,
                                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                                    label = "prompt-copy-hint",
                                ) { copied ->
                                    Text(
                                        if (copied) "已复制" else "复制",
                                        color = if (copied) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = bannerMessage != null && (!messageDismissed || important),
                    enter = fadeIn(tween(150)) + expandVertically(tween(160)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bannerMessage.orEmpty(), Modifier.weight(1f).padding(start = 12.dp), color = if (important) MaterialTheme.colorScheme.error else MediaTextSecondary,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ViewerIcon("查看提示详情", Icons.Default.Info, { messageDetailsOpen = true })
                        if (!important) ViewerIcon("收起提示", Icons.Default.Close, { messageDismissed = true })
                    }
                }
            }
        }
        // 沉浸模式（控制层已隐藏）下保留一条紧凑进度，避免为看进度反复点按。
        val compactProgress = state.generationProgress?.takeIf {
            state.generationProgressEnabled && it.isGenerating
        }
        AnimatedVisibility(
            visible = !chrome.visible && compactProgress != null,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            compactProgress?.let { CompactGenerationProgressPill(it) }
        }
        AnimatedVisibility(chrome.visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xED080B10), Color(0xFF080B10)))).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                AnimatedVisibility(
                    visible = showThumbnails,
                    enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                ) {
                    LazyRow(state = thumbnails, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().height(72.dp)) {
                        items(state.items, key = { it.imageId }) { thumbnail ->
                            NsfwReviewThumbnail(safeNsfwImageUrl(state.baseUrl, thumbnail.thumbnailUrl), imageLoader,
                                thumbnail.imageId == state.currentId) { if (canNavigate) { requestedDirection = 0; onSelect(thumbnail.imageId) } }
                        }
                    }
                }

                if (item != null && ready && state.decisionTarget == null && !state.detailLoading && !state.busy) {
                    TextButton(onClick = { onSelect(item.imageId) }) { Text("重试读取审核状态") }
                }
                val waitingForPage = state.bidirectionalGallery && state.hasMore && state.syncing &&
                    state.currentIndex == state.items.lastIndex
                val canReview = ready && transitionFinished && !state.busy && !waitingForPage && state.decisionTarget != null
                LaunchedEffect(item?.imageId, canReview) {
                    if (com.mediareview.app.BuildConfig.DEBUG && item != null && canReview && !recordedReviewReady) {
                        recordedReviewReady = true
                        android.util.Log.d("MRGeneratedReview", "reviewReadyMs=${android.os.SystemClock.elapsedRealtime() - previewStartedAt}")
                    }
                }
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
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                requestedDirection = 1
                                onDecision("KEEP", null)
                            },
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
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                requestedDirection = 1
                                onDecision("REJECT", null)
                            },
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
                        Box(Modifier.graphicsLayer {
                            scaleX = favoriteScale.value
                            scaleY = favoriteScale.value
                        }) {
                            ViewerIcon(
                                if (item?.isFavorite == true) "取消收藏" else "收藏",
                                if (item?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onToggleFavorite()
                                },
                                enabled = canReview,
                            )
                        }
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
internal fun GenerationProgressStrip(progress: NsfwGenerationProgress) {
    val statusColor = when (progress.activeStatus) {
        "running" -> MaterialTheme.colorScheme.primary
        "queued" -> MaterialTheme.colorScheme.tertiary
        "paused" -> MaterialTheme.colorScheme.error
        else -> MediaTextSecondary
    }
    val pulse = if (progress.isGenerating) {
        val transition = rememberInfiniteTransition(label = "generation-progress")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
            label = "generation-progress-alpha",
        )
        value
    } else {
        1f
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(statusColor, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(6.dp))
            AnimatedContent(
                targetState = progress.label,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "generation-progress-label",
                modifier = Modifier.weight(1f),
            ) { label ->
                Text(
                    label,
                    color = if (progress.isGenerating) MediaTextPrimary else MediaTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 运行中底模：新桥才返回；排队 / 旧任务 / 旧桥自动隐藏，不占高度。
        progress.modelLabel?.let { model ->
            Text("底模 $model", color = MediaTextSecondary, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, top = 1.dp))
        }
        val fraction = progress.fraction
        if (fraction != null) {
            val animated by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(260),
                label = "generation-progress-fraction",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(1.5.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated)
                        .height(3.dp)
                        .background(statusColor, RoundedCornerShape(1.5.dp)),
                )
            }
        }
        // 当前张的采样完成度：细条叠加在本段进度下方，旧桥无该字段时不出现。
        val itemFraction = progress.itemFraction
        if (itemFraction != null) {
            val animatedItem by animateFloatAsState(
                targetValue = itemFraction,
                animationSpec = tween(300),
                label = "generation-item-progress",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(2.dp)
                    .background(Color(0x22FFFFFF), RoundedCornerShape(1.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animatedItem)
                        .height(2.dp)
                        .background(statusColor.copy(alpha = 0.65f), RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/** 沉浸浏览时的紧凑进度胶囊：只读、单行，百分比变化淡入切换。 */
@Composable
private fun CompactGenerationProgressPill(progress: NsfwGenerationProgress) {
    val statusColor = when (progress.activeStatus) {
        "running" -> MaterialTheme.colorScheme.primary
        "queued" -> MaterialTheme.colorScheme.tertiary
        "paused" -> MaterialTheme.colorScheme.error
        else -> MediaTextSecondary
    }
    val label = progress.compactLabel
    Column(
        Modifier
            .statusBarsPadding()
            .padding(top = 8.dp)
            .background(Color(0xCC080B10), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(statusColor, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(6.dp))
            AnimatedContent(
                targetState = label,
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
                label = "compact-generation-progress",
            ) { text ->
                Text(text, color = MediaTextPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        progress.modelLabel?.let { model ->
            Text("底模 $model", color = MediaTextSecondary, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, top = 1.dp))
        }
    }
}

@Composable
private fun PrefetchPreviews(urls: List<String>, visibleUrl: String?, imageLoader: NsfwImageLoader) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefetch = remember(imageLoader) { NsfwImagePrefetcher(scope, imageLoader::prefetch) }
    val latestUrls = rememberUpdatedState(urls to visibleUrl)
    LaunchedEffect(lifecycleOwner, prefetch) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                snapshotFlow { latestUrls.value }.collect { (upcoming, visible) -> prefetch.update(upcoming, visible) }
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
