package com.mediareview.app.feature.v2.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.perf.V2Perf
import com.mediareview.app.feature.v2.viewer.state.ImageViewerContext
import com.mediareview.app.feature.v2.viewer.state.ImageViewerState
import com.mediareview.app.feature.v2.viewer.state.ViewerPendingDeleteState
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import coil.imageLoader
import coil.request.SuccessResult
import com.mediareview.app.core.network.ImageRequestLifetime

/**
 * V2 图片查看器（画册体验）：
 * - ImageViewerContext：只包含 IMAGE，initialIndex 过滤后按 initialMediaId 重算
 * - HorizontalPager 左右翻图；scale==1 翻页、scale>1 拖动图片
 * - 单击显隐 UI；双击 1.0x↔2.5x（以点击点为中心）；双指缩放 1~5x；平移带边界
 * - ±2低清预组合；当前成功后串行预取±1原图源。
 * - 收藏与待删除均由 Repository 确认；读取未知时禁用待删除并提供重试
 * - 信息面板 ModalBottomSheet；沉浸式黑底，离开恢复系统栏
 */
@Composable
fun V2ImageViewer(
    vm: V2HomeViewModel,
    initialMediaId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }

    // 图片上下文：全队列过滤 IMAGE，按 initialMediaId 重算索引
    val viewerContext = remember(initialMediaId) {
        val queue = vm.contextQueue
        val allIds = queue?.mediaIds ?: vm.currentList.value.map { it.id }
        ImageViewerContext.build(
            allIds = allIds,
            isImage = { id -> vm.mediaById(id)?.type == V2MediaType.IMAGE },
            initialMediaId = initialMediaId,
            sourceFolderId = queue?.folderId,
            sortField = queue?.sortField ?: V2SortField.RECENT,
            sortOrder = queue?.sortOrder ?: V2SortOrder.DESC,
        )
    }
    val albumWindow by vm.albumViewerState.collectAsState()
    // This session is captured once: appending ids must not reopen either paging or pending-delete.
    val albumSession = remember(vm, initialMediaId) { vm.albumViewerSession }
    val ids = if (albumSession != null) albumWindow?.mediaIds.orEmpty() else viewerContext.mediaIds
    DisposableEffect(vm, albumSession) {
        onDispose { albumSession?.let(vm::closeAlbumViewer) }
    }

    val pagerState = rememberPagerState(initialPage = viewerContext.initialIndex) { ids.size }
    val viewerState = remember { ImageViewerState(viewerContext.initialIndex) }
    val pendingState by vm.viewerPendingDeleteState.collectAsState()
    val pendingBusy by vm.viewerPendingDeleteBusy.collectAsState()
    var pendingSession by remember(vm, initialMediaId) { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(vm, initialMediaId) {
        val session = vm.openViewerPendingDelete()
        pendingSession = session
        onDispose { vm.closeViewerPendingDelete(session) }
    }
    LaunchedEffect(vm) { vm.viewerPendingDeleteMessages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm, albumSession) { vm.albumViewerMessages.collect { snackbar.showSnackbar(it) } }

    // 独立搜索/收藏窗口里的图片可能不在首页列表，使用服务器确认的状态驱动当前按钮。
    val list by vm.currentList.collectAsState()
    val confirmedFavoriteStates by vm.confirmedFavoriteStates.collectAsState()
    val currentId = ids.getOrNull(pagerState.currentPage)
    val cachedMedia = list.firstOrNull { it.id == currentId } ?: currentId?.let { vm.mediaById(it) }
    val currentMedia = cachedMedia?.let { media ->
        confirmedFavoriteStates[media.id]?.let { media.copy(isFavorite = it) } ?: media
    }
    val sourceNamespace = vm.imageCacheNamespace
    val currentOriginal = currentMedia?.let(vm::imageUri)
    var originalReadyKey by remember { mutableStateOf<String?>(null) }
    val readyKey = currentOriginal?.let { originalImageCacheKey(sourceNamespace, it) }
    val originalLifetime = remember(readyKey) { ImageRequestLifetime() }
    DisposableEffect(originalLifetime) {
        onDispose { originalLifetime.cancel() }
    }
    // 页或源变化立即取消旧预取；当前解码成功后才串行预取±1，最多一个小解码。
    val adjacentUrls = adjacentOriginalIndices(pagerState.currentPage, ids.size)
        .mapNotNull { ids.getOrNull(it)?.let(vm::mediaById)?.let(vm::imageUri) }
        .filter { it.isNotBlank() && it != currentOriginal }.distinct()
    // 同页追加相册/邻源更新也会重启预取；其body传输必须随该窗口一起取消。
    val prefetchLifetime = remember(readyKey, originalReadyKey, adjacentUrls) { ImageRequestLifetime() }
    DisposableEffect(prefetchLifetime) {
        onDispose { prefetchLifetime.cancel() }
    }
    LaunchedEffect(readyKey, originalReadyKey, adjacentUrls) {
        if (readyKey == null || originalReadyKey != readyKey) return@LaunchedEffect
        for (url in adjacentUrls) {
            val result = context.imageLoader.execute(originalImageRequest(context, url, sourceNamespace, 1, 1, prefetch = true)
                .newBuilder().tag(ImageRequestLifetime::class.java, prefetchLifetime).build())
            if (result is SuccessResult) V2Perf.viewer()?.markOnce("neighbor_prefetch_ready", "neighbor_prefetch_ready")
        }
    }

    var showInfo by remember { mutableStateOf(false) }
    var currentScale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(pagerState.currentPage, ids.size) {
        viewerState.updateCurrentIndex(pagerState.currentPage)
        if (albumSession != null) {
            vm.updateAlbumViewerIndex(pagerState.currentPage)
            vm.albumViewerNearEnd(albumSession, pagerState.currentPage)
        }
    }

    // Stage 8A.1.1: 每次打开 Viewer 都新建测量会话，preview/full 相对 viewer_open 计时
    LaunchedEffect(Unit) { V2Perf.openViewer() }

    // 沉浸式：进入隐藏系统栏 + 常亮；离开恢复（不污染首页）
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.let { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        CompositionLocalProvider(LocalOriginalImageSession provides OriginalImageSession(sourceNamespace, originalLifetime) { url ->
            if (url == currentOriginal) originalReadyKey = originalImageCacheKey(sourceNamespace, url)
        }) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = currentScale <= 1.01f,
            modifier = Modifier.fillMaxSize().testTag("viewer_pager"),
            beyondViewportPageCount = 2,
            key = { ids[it] },
        ) { page ->
            val media = ids.getOrNull(page)?.let { vm.mediaById(it) }
            if (media != null) {
                ZoomableImage(
                    // 渐进加载: 先显示媒体墙已缓存的封面,原图仅当前页后台加载
                    previewUri = vm.coverUri(media),
                    fullUri = vm.imageUri(media),
                    loadFullResolution = shouldLoadFullResolution(page, pagerState.currentPage),
                    isCurrent = page == pagerState.currentPage,
                    naturalWidth = media.naturalWidth,
                    naturalHeight = media.naturalHeight,
                    onTap = { viewerState.toggleUi() },
                    onScaleChange = { if (page == pagerState.currentPage) currentScale = it },
                )
            }
        }
        }

        if (viewerState.uiVisible) {
            ImageViewerTopBar(
                name = currentMedia?.name ?: "图片",
                code = currentMedia?.code.orEmpty(),
                currentIndex = pagerState.currentPage,
                total = ids.size,
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (viewerState.uiVisible && currentMedia != null) {
            ImageViewerBottomBar(
                isFavorite = currentMedia.isFavorite,
                isPendingDelete = (pendingState as? ViewerPendingDeleteState.Ready)?.ids?.contains(currentMedia.id) == true,
                onToggleFavorite = { vm.setFavorite(currentMedia.id, !currentMedia.isFavorite) },
                onInfo = { showInfo = true },
                onTogglePendingDelete = { pendingSession?.let { vm.toggleViewerPendingDelete(it, currentMedia.id) } },
                modifier = Modifier.align(Alignment.BottomCenter),
                pendingDeleteEnabled = pendingState is ViewerPendingDeleteState.Ready && !pendingBusy,
                pendingDeleteLabel = when (pendingState) {
                    ViewerPendingDeleteState.Loading -> "正在加载…"
                    is ViewerPendingDeleteState.Error -> "状态未知"
                    is ViewerPendingDeleteState.Ready -> null
                },
            )
        }
        if (viewerState.uiVisible && pendingState is ViewerPendingDeleteState.Error) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("待删除状态加载失败", color = androidx.compose.ui.graphics.Color.White)
                TextButton(onClick = { pendingSession?.let { vm.retryViewerPendingDelete(it) } }, modifier = Modifier.testTag("viewer_delete_retry")) {
                    Text("重新加载")
                }
            }
        }
        if (viewerState.uiVisible && albumWindow?.moreError != null) {
            TextButton(onClick = { albumSession?.let(vm::retryAlbumViewer) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 195.dp).testTag("viewer_album_retry")) {
                Text("加载更多失败 · 重试")
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 145.dp))
    }

    if (showInfo && currentMedia != null) {
        ImageInfoSheet(
            name = currentMedia.name,
            code = currentMedia.code,
            folderName = currentMedia.folderName,
            width = currentMedia.naturalWidth,
            height = currentMedia.naturalHeight,
            sizeBytes = currentMedia.sizeBytes,
            dateMillis = currentMedia.dateMillis,
            isFavorite = currentMedia.isFavorite,
            onDismiss = { showInfo = false },
        )
    }
}

/** 从任意 Context 向上查找 Activity。 */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
