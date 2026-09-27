package com.mediareview.app.feature.v2.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
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
import com.mediareview.app.ui.theme.MediaImmersiveBackground

/**
 * V2 图片查看器（画册体验）：
 * - ImageViewerContext：只包含 IMAGE，initialIndex 过滤后按 initialMediaId 重算
 * - HorizontalPager 左右翻图；scale==1 翻页、scale>1 拖动图片
 * - 单击显隐 UI；双击 1.0x↔2.5x（以点击点为中心）；双指缩放 1~5x；平移带边界
 * - 预加载 N-1 / N+1 / N+2
 * - 收藏（真实状态）；待删除（会话内 pendingDeleteIds，不再用 markReviewed）
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
    val ids = viewerContext.mediaIds

    val pagerState = rememberPagerState(initialPage = viewerContext.initialIndex) { ids.size }
    val viewerState = remember { ImageViewerState(viewerContext.initialIndex) }

    // 收藏等状态变化经 currentList 刷新驱动重绘
    val list by vm.currentList.collectAsState()
    val currentId = ids.getOrNull(pagerState.currentPage)
    val currentMedia = list.firstOrNull { it.id == currentId } ?: currentId?.let { vm.mediaById(it) }

    var showInfo by remember { mutableStateOf(false) }
    var currentScale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(pagerState.currentPage) {
        viewerState.updateCurrentIndex(pagerState.currentPage)
    }

    // Stage 8A.1 §3: Viewer 打开时刻(viewer_preview_visible / viewer_full_image_ready 在其后)
    LaunchedEffect(Unit) { V2Perf.viewerOpen() }

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
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = currentScale <= 1.01f,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 2,
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
                isPendingDelete = viewerState.isPendingDelete(currentMedia.id),
                onToggleFavorite = { vm.setFavorite(currentMedia.id, !currentMedia.isFavorite) },
                onInfo = { showInfo = true },
                onTogglePendingDelete = { viewerState.togglePendingDelete(currentMedia.id) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
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
