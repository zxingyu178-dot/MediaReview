package com.mediareview.app.feature.v2.review

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayState
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerController
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerSurface
import com.shuyu.gsyvideoplayer.compose.native_.rememberGSYPlayerController
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Poster 从首帧就绪到淡出的时长（消灭切换黑屏的视觉过渡）。 */
private const val POSTER_FADE_MS = 200

/** 等待首帧渲染超时（超过则保留 Poster / Loading，不让纯黑空窗暴露）。 */
private const val FIRST_FRAME_TIMEOUT_MS = 2500L

/** 表示"已出画面"的播放状态（本地资源首帧后立即进入这些状态之一）。 */
private val FIRST_FRAME_STATES = setOf(
    GSYPlayState.Playing,
    GSYPlayState.Paused,
    GSYPlayState.Completed,
)

/**
 * 批阅模式（抖音式）：竖屏 VerticalPager，视频优先（VIDEO ONLY）。
 *
 * Stage 7 收稳要点：
 * - 生命周期：进入页面调 [V2ReviewViewModel.enterReview]（每次真正进入重新检查 Repository；
 *   从完整播放器返回恢复 Session，不跳回第 1 条）；
 * - 换页防黑屏：Page N 停稳 → Poster 仍盖住 Surface → controller 换源 →
 *   首帧就绪 → Poster 200ms 淡出露出视频；切到 N+1 重复。
 *   （单 GSY Controller 共享，不做双 Player 预加载——视觉等待由 Poster 覆盖解决。）
 * - 自动批阅：snapshotFlow 观察 settledPage + isScrollInProgress，同页无滚动稳定 ~480ms 才
 *   markReviewed；任何新滚动/换页立即取消等待（不清楚"已经离开该页却仍标记"）；
 * - 单击 = 播放/暂停（暂停中央轻量 ▶），不再整体隐藏操作层；
 * - 右侧 🖤 收藏 / 🗑 待删除（可撤销）/ ⋯ 更多（ModalBottomSheet：打开完整播放器 / 视频信息）；
 * - 完成 = 本 Session 队列全部已批阅（[V2ReviewViewModel.isComplete]），进入完成同时暂停播放；
 * - 播放源完全来自 [ReviewMediaSource]（Repository 解析 URL），UI 不接触 Demo 资源。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V2ReviewScreen(
    vm: V2ReviewViewModel,
    onOpenFullPlayer: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val queue by vm.queue.collectAsState()
    val ready by vm.ready.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.enterReview() }

    if (!ready) {
        Box(modifier = modifier.fillMaxSize().background(MediaImmersiveBackground))
        return
    }

    val pendingDeleteIds by vm.pendingDeleteIds.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()
    val allDone by vm.isComplete.collectAsState()

    var infoMedia by remember { mutableStateOf<ReviewMediaSource?>(null) }
    var moreSheet by remember { mutableStateOf(false) }
    // Sheet 流程（更多 / 视频信息）打开时暂停、关闭按进入时状态恢复，避免视频在
    // 面板前景时继续响（规格 12）。
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetResumePlay by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 无未审视频 → 空队列页（"重新批阅"必须重新建立全量队列）
    if (queue.isEmpty()) {
        ReviewCompletePage(
            emptyQueue = true,
            total = 0,
            onRestart = { vm.restartAllVideos() },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceIn(0, queue.lastIndex),
    ) { queue.size }

    // 单共享 controller：源从 ReviewMediaSource 解析（不接触 demo 资源）。
    val controller = rememberGSYPlayerController(
        url = queue.first().playbackUrl,
        cacheWithPlay = false,
        title = queue.first().title,
        autoPlay = false,
        autoPauseResume = true,
    )
    var lastPlayedId by remember { mutableStateOf("") }
    // 已完成首帧揭示、允许 Poster 淡出的页面（-1 = 无）
    var revealedPage by remember { mutableIntStateOf(-1) }
    // 480ms 稳定停留判定（纯逻辑，事件驱动）
    val stableGate = remember { ReviewStableGate() }
    // snapshot 是 compose State：组合路径读取自动重组
    val snapshot = controller.snapshot.value
    val isPlaying = snapshot.isPlaying

    // 单击 = 播放/暂停（抖音式；完成页 / Sheet 前景不响应）
    fun togglePlayPause() {
        if (!allDone && !sheetOpen) controller.togglePlayPause()
    }

    // 进入时固定视觉策略：Review 统一 FIT（Poster 与视频画面同一种显示策略）。
    LaunchedEffect(Unit) {
        GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_DEFAULT)
    }

    // ---------- 换页：换源 + 等 host + 首帧后揭示（Poster 盖住直到首帧） ----------
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        val media = queue.getOrNull(page) ?: return@LaunchedEffect
        revealedPage = -1
        if (lastPlayedId.isNotEmpty() && media.mediaId != lastPlayedId) {
            controller.setUp(media.playbackUrl, false, media.title, false)
        }
        lastPlayedId = media.mediaId
        // 明确收敛的 host 等待：可取消、2s 超时；超时保留 Poster（不堆 postDelayed）。
        val hostReady = awaitPlayerHostReady(controller)
        if (!hostReady) return@LaunchedEffect
        controller.withHost { host ->
            host.postDelayed({ controller.play() }, 120L)
            true
        }
        // 等首帧就绪后再淡出 Poster：切换全程用户看到的永远是
        // 旧视频画面 / 新 Poster / 新视频首帧之一，不出现大面积纯黑空窗。
        val firstFrameOk = withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) {
            while (controller.snapshot.value.state !in FIRST_FRAME_STATES) delay(50L)
            true
        }
        if (firstFrameOk == true) revealedPage = page
    }

    // ---------- 自动批阅：稳定 ~480ms（snapshotFlow + collectLatest 取消语义） ----------
    LaunchedEffect(queue) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { (page, scrolling) ->
                val now = SystemClock.uptimeMillis()
                if (scrolling || page < 0) {
                    // 任何滚动：立即取消等待（不清除"已经离开该页却仍标记"）
                    stableGate.onEvent(page, true, now)
                    return@collectLatest
                }
                stableGate.onEvent(page, false, now)
                delay(REVIEW_STABLE_MS_DEFAULT)
                // 醒来后再次确认仍是同一页且未滚动，且稳定时长确实达标
                if (!pagerState.isScrollInProgress && pagerState.settledPage == page) {
                    val confirmed = stableGate.onEvent(page, false, SystemClock.uptimeMillis())
                    if (confirmed != null) {
                        stableGate.reset()
                        vm.markReviewed(queue[page].mediaId)
                    }
                }
            }
    }

    // 完成状态：队列全部已批阅 → 立即暂停（完成页不能盖在还在播的视频上）
    LaunchedEffect(allDone) {
        if (allDone) controller.pause()
    }

    // Session 同步：当前页变化 / 恢复定位
    LaunchedEffect(pagerState.settledPage) {
        vm.onPageSettled(pagerState.settledPage)
    }
    LaunchedEffect(ready, currentIndex) {
        if (ready && currentIndex in 0..queue.lastIndex && pagerState.currentPage != currentIndex) {
            pagerState.scrollToPage(currentIndex)
        }
    }

    // ---------- Sheet 打开 / 关闭的播放策略 ----------
    fun openSheetFlow() {
        if (!sheetOpen) {
            sheetOpen = true
            sheetResumePlay = controller.snapshot.value.isPlaying
            if (sheetResumePlay) controller.pause()
        }
    }

    fun closeSheetFlow() {
        sheetOpen = false
        if (sheetResumePlay) controller.play()
        sheetResumePlay = false
    }

    // ---------- 布局 ----------
    Box(modifier = modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        // 常驻视频 Surface：换页时 setUp 换源（单 Controller，不做双 Player）。
        GSYPlayerSurface(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
        )

        // Pager：每页 Poster 盖层。周边页恒不透明；当前页在首帧就绪后淡出露出视频。
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = queue[page]
            val isCurrent = page == pagerState.settledPage
            val posterAlpha by animateFloatAsState(
                targetValue = if (isCurrent && page == revealedPage) 0f else 1f,
                animationSpec = tween(POSTER_FADE_MS),
                label = "reviewPosterFade",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { togglePlayPause() })
                    },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(media.coverUrl)
                        .crossfade(false)
                        .size(720, 1280)
                        .build(),
                    contentDescription = media.title,
                    // 与视频画面同一视觉策略（Review Display Mode = FIT）
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f * posterAlpha)),
                )
                // 周边页 / 未揭示页：中央播放标记
                if (posterAlpha > 0.5f) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MediaTextPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
        }

        // 暂停态：中央轻量 ▶（单击 = 播放/暂停，抖音式，不整体隐藏 UI）
        if (!isPlaying && !allDone && !sheetOpen) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "播放",
                        tint = MediaTextPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }

        // 顶部：返回 + 当前/总数（常驻；不再显示"已批阅 N"，贴近抖音）
        if (!allDone) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = MediaTextPrimary,
                    )
                }
                Text(
                    text = "${pagerState.settledPage + 1} / ${queue.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MediaTextPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 底部：标题 + 编号 · 文件夹 · 时长
            val media = queue[pagerState.settledPage]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MediaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${media.code} · ${media.folderName} · ${media.durationSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // 右侧动作栏：收藏 / 待删除(可撤销) / 更多（48dp 触控区）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val media = queue[pagerState.settledPage]
                ReviewActionButton(
                    icon = if (media.mediaId in favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    tint = if (media.mediaId in favoriteIds) V2Colors.Favorite else MediaTextPrimary,
                    label = if (media.mediaId in favoriteIds) "已收藏" else "收藏",
                    onClick = { vm.toggleFavorite(media.mediaId) },
                )
                ReviewActionButton(
                    icon = if (media.mediaId in pendingDeleteIds) Icons.Default.Undo else Icons.Default.DeleteOutline,
                    tint = if (media.mediaId in pendingDeleteIds) MediaDanger else MediaTextPrimary,
                    label = if (media.mediaId in pendingDeleteIds) "撤销" else "待删除",
                    onClick = {
                        if (media.mediaId in pendingDeleteIds) {
                            vm.undoPendingDelete(media.mediaId)
                        } else {
                            vm.addPendingDelete(media.mediaId)
                            scope.launch {
                                val result = snackbar.showSnackbar(
                                    message = "已加入待删除",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    vm.undoPendingDelete(media.mediaId)
                                }
                            }
                        }
                    },
                )
                ReviewActionButton(
                    icon = Icons.Default.MoreVert,
                    tint = MediaTextPrimary,
                    label = "更多",
                    onClick = {
                        openSheetFlow()
                        moreSheet = true
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )

        // 更多 → 统一 ModalBottomSheet（外部点击 / Back 可关闭，动画与 V2 其他 Sheet 一致）
        if (moreSheet) {
            ModalBottomSheet(onDismissRequest = { closeSheetFlow(); moreSheet = false }) {
                val media = queue[pagerState.settledPage]
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    MoreSheetItem(
                        icon = Icons.Default.PlayArrow,
                        label = "打开完整播放器",
                        onClick = {
                            moreSheet = false
                            closeSheetFlow()
                            vm.onPageSettled(pagerState.settledPage)
                            vm.onLeaveForFullPlayer()
                            controller.pause()
                            onOpenFullPlayer(media.mediaId)
                        },
                    )
                    MoreSheetItem(
                        icon = Icons.Default.MoreVert,
                        label = "视频信息",
                        onClick = {
                            moreSheet = false
                            // 仍在 Sheet 流程内：保持暂停，不提前恢复
                            infoMedia = media
                        },
                    )
                }
            }
        }

        // 视频信息 Sheet（与更多同一暂停/恢复流程）
        infoMedia?.let { media ->
            GsyNativeReviewInfoSheet(
                media = media,
                onDismiss = {
                    infoMedia = null
                    closeSheetFlow()
                },
            )
        }

        // 完成浮层：队列全部已批阅（视频已暂停）
        if (allDone) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) {},
            ) {
                ReviewCompleteOverlay(
                    total = queue.size,
                    onRestart = {
                        vm.restartCurrentSession()
                        scope.launch { pagerState.scrollToPage(0) }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * 等待 GSY host（GSYPlayerSurface 的 AndroidView）attach 且完成首次布局。
 * 可取消（所在协程取消即停止）、明确 2s 超时，超时返回 false；
 * 替代旧实现的 `repeat(150) + return@repeat + postDelayed recursion` 双层等待。
 */
private suspend fun awaitPlayerHostReady(
    controller: GSYPlayerController,
    timeoutMs: Long = 2000L,
): Boolean {
    val done = withTimeoutOrNull(timeoutMs) {
        var ready = false
        while (!ready) {
            ready = controller.withHost { host ->
                host.isAttachedToWindow && host.width > 0 && host.height > 0
            } ?: false
            if (!ready) delay(40L)
        }
        true
    }
    return done ?: false
}

/** 抖音式右侧动作按钮：圆形半透明底 + 图标 + 小字。 */
@Composable
private fun ReviewActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(44.dp)
                .background(V2Colors.CardScrim, CircleShape),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MediaTextPrimary,
        )
    }
}

/** 更多菜单条目：图标 + 文案，横向整行点击。 */
@Composable
private fun MoreSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MediaTextPrimary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MediaTextPrimary,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** 视频信息 Sheet（Review 版本：数据全部来自 [ReviewMediaSource]，不接触 Demo 资源）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GsyNativeReviewInfoSheet(
    media: ReviewMediaSource,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            InfoSheetRow("时长", "${media.durationSeconds}s")
            InfoSheetRow("分辨率", if (media.naturalWidth > 0) "${media.naturalWidth} × ${media.naturalHeight}" else "未知")
            InfoSheetRow("编号", media.code)
            InfoSheetRow("文件夹", media.folderName)
            InfoSheetRow("媒体 ID", media.mediaId)
        }
    }
}

@Composable
private fun InfoSheetRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MediaTextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MediaTextPrimary)
    }
}

/** 完成浮层：批阅完成 + 重新批阅（进入时视频已暂停）。 */
@Composable
private fun ReviewCompleteOverlay(
    total: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.78f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = V2Colors.Accent,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "批阅完成",
                style = MaterialTheme.typography.titleLarge,
                color = MediaTextPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "共 $total 条视频已全部浏览",
                style = MaterialTheme.typography.bodyMedium,
                color = MediaTextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = V2Colors.Accent,
                onClick = onRestart,
                modifier = Modifier.padding(top = 18.dp),
            ) {
                Text(
                    text = "重新批阅",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 无未审视频时的完成页（"重新批阅"= 重建全量视频队列）。 */
@Composable
private fun ReviewCompletePage(
    emptyQueue: Boolean,
    total: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MediaImmersiveBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = V2Colors.Favorite,
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = if (emptyQueue) "没有待批阅的视频" else "批阅完成",
                style = MaterialTheme.typography.titleLarge,
                color = MediaTextPrimary,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                text = if (emptyQueue) "全部视频都已批阅完成" else "共 $total 条视频已全部浏览",
                style = MaterialTheme.typography.bodyMedium,
                color = MediaTextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = V2Colors.Accent,
                onClick = onRestart,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(
                    text = "重新批阅",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}