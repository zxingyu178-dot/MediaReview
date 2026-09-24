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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediareview.app.BuildConfig
import com.mediareview.app.feature.v2.player.native.state.awaitPlayerHostReady
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import com.shuyu.gsyvideoplayer.builder.GSYVideoOptionBuilder
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayState
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerSurface
import com.shuyu.gsyvideoplayer.compose.native_.rememberGSYPlayerController
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Poster 从"播放状态可用"到淡出的时长（消灭切换黑屏的视觉过渡）。 */
private const val POSTER_FADE_MS = 200

/** 播放可用等待超时（模拟器 SwiftShader 软解启动慢，放宽到 10s；超过则保留 Poster / Loading）。 */
private const val PLAYBACK_READY_TIMEOUT_MS = 10_000L

/**
 * "画面确实在输出"判定（Playback-ready reveal，Stage 8A §17.2 修正）：
 * - Playing：要求播放位置已前进（currentPosition > 0）——位置前进说明帧在解码输出，
 *   避免 state==Playing 但首帧尚未渲染时的黑帧窗口；
 * - Paused / Completed：**只有当前源曾经真的播过（Playing + position > 0，即
 *   [hasPlaybackAdvanced]）** 才认为画面已输出。
 *   旧实现无条件返回 true，会出现"还没首帧 → 被暂停 → Poster 提前淡出 → 黑屏"；
 * - 换媒体时 [hasPlaybackAdvanced] 必须重置为 false。
 *
 * 说明：这是当前 GSY 能力下的"播放状态可用 + 输出推进"判定，不是原生 first-frame 回调；
 * 若后续找到 GSY 渲染首帧回调则优先替换。
 */
private fun isPlaybackVisible(
    state: GSYPlayState,
    currentPositionMs: Long,
    hasPlaybackAdvanced: Boolean,
): Boolean = when (state) {
    GSYPlayState.Playing -> currentPositionMs > 0L
    GSYPlayState.Paused, GSYPlayState.Completed -> hasPlaybackAdvanced
    else -> false
}

/**
 * 批阅模式（抖音式）：竖屏 VerticalPager，视频优先（VIDEO ONLY）。Stage 7.1 正确性修正。
 *
 * - 播放生命周期：Controller 首始源 = `queue[currentIndex]`（恢复第 N 条即播放第 N 条）；
 *   维护 [currentLoadedMediaId]，settledPage 变化且 mediaId 不同时才 setHeaders + setUp（不依赖
 *   lastPlayedId 空分支）；
 * - Poster 整层淡出：[posterAlpha] 控制 Black/AsyncImage/Scrim/Icon 整层，淡出后底层
 *   GSYPlayerSurface 完全露出（用户真的看到视频，而非静态封面）；
 * - Playback Headers：每次换源前 `controller.setHeaders(media.headers)` 与 URL 一起切换（Critical 3）；
 * - 稳定批阅计时：仅当 page == settledPage && !scrolling && revealedPage == page（内容可见）开始计时，
 *   任何滚动/换页/未揭示立即取消（High 2）；
 * - 重新批阅显式重启：重置 reveal / gate / loadedId，强制第 1 条 setHeaders+setUp+play（High 3，
 *   单条队列同样生效）；
 * - Sheet 关闭两个语义：视频信息→closeSheetAndResume（按原状态恢复）；打开完整播放器→
 *   closeSheetWithoutResume（不瞬间恢复播放，避免 resume→pause 抖动）。
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

    // Stage 8A §16 / §33：Server 模式下真实批阅会话（Stage 8B）尚未接入。
    // 这里明确显示占位说明，且不建立任何队列 / 不做批量播放地址解析。
    val serverUnsupported by vm.serverModeUnsupported.collectAsState()
    if (serverUnsupported) {
        ReviewServerModePlaceholder(
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

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
    // Sheet 流程（更多 / 视频信息）打开时暂停；关闭按语义恢复。
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

    // Critical 2：Controller 首始源 = 当前页，而不是 queue.first()。
    // 恢复第 N 条时 currentIndex == N → 页面与播放器都在第 N 条。
    val initialMedia = queue[currentIndex.coerceIn(0, queue.lastIndex)]
    val controller = rememberGSYPlayerController(
        url = initialMedia.playbackUrl,
        cacheWithPlay = false,
        title = initialMedia.title,
        autoPlay = false,
        autoPauseResume = true,
    )
    // 当前已加载进播放器的 mediaId（source 一致性的事实源；""=尚未加载）。
    // 初始置空：首条进入也走一次完整 setUp（GSY autoPlay=false 时 play() 需要先 setUp
    // 合成源，单纯构造 URL 不保证可启动）。
    var currentLoadedMediaId by remember { mutableStateOf("") }
    // 已完成"播放可用"揭示、允许 Poster 淡出的页面（-1 = 无）。
    var revealedPage by remember { mutableIntStateOf(-1) }
    // Stage 8A §17.2：当前源是否真的出现过画面（Playing 且 position > 0）。
    // Paused / Completed 只有在它为 true 时才允许 Poster 淡出，避免"未首帧被暂停 → 黑屏"。
    var hasPlaybackAdvanced by remember { mutableStateOf(false) }
    // 重新批阅的显式重载令牌：变化强制换页 effect 重跑（单条队列 scrollToPage 无变化也生效）。
    var playRequestToken by remember { mutableStateOf(0) }
    // 480ms 稳定停留判定（纯逻辑，事件驱动）。
    val stableGate = remember { ReviewStableGate() }
    // snapshot 是 compose State：组合路径读取自动重组。
    val snapshot = controller.snapshot.value
    val isPlaying = snapshot.isPlaying

    // 单击 = 播放/暂停（抖音式；完成页 / Sheet 前景不响应）。
    fun togglePlayPause() {
        if (!allDone && !sheetOpen) controller.togglePlayPause()
    }

    // 进入时固定视觉策略（Review 统一 FIT）。
    // Headers 不需要在此预置：首条源与 controller 初始化一致（Demo 为空），
    // 换源时 setHeaders 会与 setUp 一起应用（见下方换源 effect）。
    // 注意：不要在播放启动路径之外调用 setHeaders，GSY 的 reapplyPendingSetters
    // 时序与本组合的 play 启动存在竞态（实测会打断首次自动播放）。
    LaunchedEffect(Unit) {
        GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_DEFAULT)
        logSourceState("initial", initialMedia, currentLoadedMediaId)
    }

    // ---------- 换页：source 一致性 + 换源(headers) + 等 play 可用后揭示 ----------
    LaunchedEffect(pagerState.settledPage, playRequestToken) {
        val page = pagerState.settledPage
        val media = queue.getOrNull(page) ?: return@LaunchedEffect
        revealedPage = -1
        if (media.mediaId != currentLoadedMediaId) {
            // Critical 3（Stage 8A 实测修正）：Headers 必须与 URL 一起进 GSYVideoOptionBuilder。
            // 只调用 controller.setHeaders(...) 会被随后的 setUp 覆盖（实测直连请求完全没有该头）。
            val option = GSYVideoOptionBuilder()
                .setUrl(media.playbackUrl)
                .setCacheWithPlay(false)
                .setVideoTitle(media.title)
                .setMapHeadData(media.headers.ifEmpty { null })
            controller.setUp(option, false)
            currentLoadedMediaId = media.mediaId
            // Stage 8A §17.2：换媒体即重置"曾经出现过画面"判定（否则新源会被旧源的
            // Paused 状态误判为已渲染，Poster 提前淡出 → 黑屏）。
            hasPlaybackAdvanced = false
            logSourceState("switch page=$page", media, currentLoadedMediaId)
        }
        // 明确收敛的 host 等待：可取消、2s 超时；超时保留 Poster（不堆 postDelayed）。
        val hostReady = awaitPlayerHostReady(controller)
        if (!hostReady) return@LaunchedEffect
        // Stage 8A §17.1：这里改为协程 delay，不再使用 host.postDelayed——
        // 换页 / 退出时所在 LaunchedEffect 协程被取消，delay 自动取消，不会留下 stale Play。
        delay(120L)
        // 取消保护 + 二次确认：仍是同一页、且播放器里仍是同一条媒体才真正 play。
        if (pagerState.settledPage != page || currentLoadedMediaId != media.mediaId) {
            return@LaunchedEffect
        }
        controller.play()
        // 画面确实在输出后才淡出 Poster（避免 Playing 但首帧未渲染的黑帧窗口）：
        // 切换全程用户看到的永远是 旧视频画面 / 新 Poster / 新视频画面 之一，不出现黑屏空窗。
        // 措辞：Playback-ready reveal（GSY 能力下的"播放状态可用 + 输出推进"，非原生 first-frame 回调）。
        val playReady = withTimeoutOrNull(PLAYBACK_READY_TIMEOUT_MS) {
            var s = controller.snapshot.value
            while (!isPlaybackVisible(s.state, s.currentPosition, hasPlaybackAdvanced)) {
                if (s.state == GSYPlayState.Playing && s.currentPosition > 0L) {
                    hasPlaybackAdvanced = true
                }
                delay(50L)
                s = controller.snapshot.value
            }
            hasPlaybackAdvanced = true
            true
        }
        if (BuildConfig.DEBUG) {
            val s = controller.snapshot.value
            android.util.Log.d(
                "MRReview",
                "PlaybackReady[page=$page] ok=$playReady state=${s.state} posMs=${s.currentPosition} " +
                    "mediaId=${media.mediaId} advanced=$hasPlaybackAdvanced"
            )
        }
        if (playReady == true) revealedPage = page
    }

    // ---------- 自动批阅：内容可见（revealed）后才开始稳定 ~480ms 计时（High 2） ----------
    LaunchedEffect(queue) {
        snapshotFlow {
            Triple(pagerState.settledPage, pagerState.isScrollInProgress, revealedPage)
        }
            .distinctUntilChanged()
            .collectLatest { (page, scrolling, revealed) ->
                val now = SystemClock.uptimeMillis()
                // 滚动中 / 页面无效 / 内容未揭示（Poster 或 Loading 中）→ 立即取消计时
                if (scrolling || page < 0 || revealed != page) {
                    stableGate.onEvent(page, true, now)
                    return@collectLatest
                }
                stableGate.onEvent(page, false, now)
                delay(REVIEW_STABLE_MS_DEFAULT)
                // 醒来后再次确认：同页、未滚动、内容仍可见
                if (!pagerState.isScrollInProgress && pagerState.settledPage == page && revealedPage == page) {
                    val confirmed = stableGate.onEvent(page, false, SystemClock.uptimeMillis())
                    if (confirmed != null) {
                        stableGate.reset()
                        vm.markReviewed(queue[page].mediaId)
                    }
                }
            }
    }

    // 完成状态：队列全部已批阅 → 立即暂停（完成页不能盖在还在播的视频上）。
    LaunchedEffect(allDone) {
        if (allDone) controller.pause()
    }

    // Session 同步：当前页变化 / 恢复定位。
    LaunchedEffect(pagerState.settledPage) {
        vm.onPageSettled(pagerState.settledPage)
    }
    LaunchedEffect(ready, currentIndex) {
        if (ready && currentIndex in 0..queue.lastIndex && pagerState.currentPage != currentIndex) {
            pagerState.scrollToPage(currentIndex)
        }
    }

    // ---------- Sheet 关闭的两个语义（High 1） ----------
    fun openSheetFlow() {
        if (!sheetOpen) {
            sheetOpen = true
            sheetResumePlay = controller.snapshot.value.isPlaying
            if (sheetResumePlay) controller.pause()
            if (BuildConfig.DEBUG) android.util.Log.d("MRReview", "Sheet.open wasPlaying=$sheetResumePlay → pause")
        }
    }

    /** 正常关闭（外部点 / Back / 视频信息关闭）：按进入 Sheet 前的播放状态恢复。 */
    fun closeSheetAndResume() {
        sheetOpen = false
        if (BuildConfig.DEBUG) android.util.Log.d("MRReview", "Sheet.closeAndResume resumePlay=$sheetResumePlay")
        if (sheetResumePlay) controller.play()
        sheetResumePlay = false
    }

    /** 关闭且不恢复播放（进入完整播放器前）：杜绝 closeSheet 瞬间 resume → pause 抖动。 */
    fun closeSheetWithoutResume() {
        sheetOpen = false
        if (BuildConfig.DEBUG) android.util.Log.d("MRReview", "Sheet.closeWithoutResume")
        sheetResumePlay = false
    }

    // ---------- 布局 ----------
    Box(modifier = modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        // 常驻视频 Surface：换页时 setHeaders+setUp 换源（单 Controller，不做双 Player）。
        GSYPlayerSurface(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
        )

        // Pager：每页一个 Poster 层（Critical 1：posterAlpha 控制整个层，淡出后 Surface 完全露出）。
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
            boxOfPosterStack(media, context, posterAlpha)
        }

        // 暂停态：中央轻量 ▶（单击 = 播放/暂停，抖音式，不整体隐藏 UI）。
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

        // 顶部：返回 + 当前/总数（常驻；不显示"已批阅 N"，贴近抖音）。
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

            // 底部：标题 + 编号 · 文件夹 · 时长。
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

            // 右侧动作栏：收藏 / 待删除(可撤销) / 更多（48dp 触控区）。
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

        // 更多 → 统一 ModalBottomSheet（外部点击 / Back 可关闭）。
        if (moreSheet) {
            ModalBottomSheet(onDismissRequest = { moreSheet = false; closeSheetAndResume() }) {
                val media = queue[pagerState.settledPage]
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    MoreSheetItem(
                        icon = Icons.Default.PlayArrow,
                        label = "打开完整播放器",
                        onClick = {
                            moreSheet = false
                            // High 1：不恢复播放 → pause → 记录 Session → 导航（日志为
                            // "Review pause / session snapshot / Navigate"，无 resume→pause 抖动）。
                            closeSheetWithoutResume()
                            vm.onPageSettled(pagerState.settledPage)
                            controller.pause()
                            vm.onLeaveForFullPlayer()
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "MRReview",
                                    "FullPlayer.pause+sessionSnapshot+navigate mediaId=${media.mediaId}"
                                )
                            }
                            onOpenFullPlayer(media.mediaId)
                        },
                    )
                    MoreSheetItem(
                        icon = Icons.Default.MoreVert,
                        label = "视频信息",
                        onClick = {
                            moreSheet = false
                            // 仍在 Sheet 流程内：保持暂停，不提前恢复。
                            infoMedia = media
                        },
                    )
                }
            }
        }

        // 视频信息 Sheet（关闭后按进入 Sheet 前的播放状态恢复）。
        infoMedia?.let { media ->
            GsyNativeReviewInfoSheet(
                media = media,
                onDismiss = {
                    infoMedia = null
                    closeSheetAndResume()
                },
            )
        }

        // 完成浮层：队列全部已批阅（视频已暂停）。
        if (allDone) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) {},
            ) {
                ReviewCompleteOverlay(
                    total = queue.size,
                    onRestart = {
                        // High 3：显式重新启动播放器（单条队列 scrollToPage(0) 不触发换页也生效）。
                        vm.restartCurrentSession()
                        stableGate.reset()
                        revealedPage = -1
                        currentLoadedMediaId = "" // 强制下一次 effect 对本条重新 setHeaders + setUp
                        hasPlaybackAdvanced = false // 重新批阅 = 重新判定"是否已出现画面"
                        playRequestToken++
                        scope.launch { pagerState.scrollToPage(0) }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // 中央单击区（抖音式 播放/暂停）：置于最顶层以稳定命中，
        // 但 padding 避开顶栏 / 底部信息 / 右侧动作栏，不干扰按钮点击；
        // 完成页时整体移除，避免吞掉"重新批阅"按钮的点击。
        if (!allDone) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 64.dp, bottom = 240.dp, end = 140.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { togglePlayPause() })
                    },
            )
        }
    }
}

/**
 * Poster Layer 组合：整个层由一个 [posterAlpha] 控制。
 * posterAlpha=1 → 不透明白底 + 封面 + 遮罩 + 播放标记；posterAlpha=0 → 整层透明，
 * 底层 [GSYPlayerSurface] 完全露出（Critical 1：真看到视频而非静态封面）。
 */
@Composable
private fun boxOfPosterStack(
    media: ReviewMediaSource,
    context: android.content.Context,
    posterAlpha: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(posterAlpha),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        )
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(media.coverUrl)
                .crossfade(false)
                .size(720, 1280)
                .build(),
            contentDescription = media.title,
            // 与视频画面同一视觉策略（Review Display Mode = FIT）。
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        )
        // 周边页 / 未揭示页：中央播放标记。
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

/**
 * Source 一致性 Debug 证据（规格 六）：记录 UI 目标 mediaId、播放器当前加载
 * [loadedMediaId]、URL path 摘要、headers 键；不打印 query / token / 敏感头值。
 */
private fun logSourceState(tag: String, media: ReviewMediaSource, loadedMediaId: String) {
    if (!BuildConfig.DEBUG) return
    android.util.Log.d(
        "MRReview",
        "SourceState[$tag] UI/target mediaId=${media.mediaId} loadedInPlayer=$loadedMediaId " +
            "urlPath=…/${fingerprint(media.playbackUrl)} headers=${media.headers.keys.sorted()}"
    )
}

/** URL path 摘要（去协议/包名前缀，只取最后一段 path），不包含 query/token。 */
private fun fingerprint(url: String): String {
    val cleaned = url.removePrefix("android.resource://")
    val path = cleaned.substringBefore('?').substringBefore('#')
    return path.substringAfterLast('/')
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

/** 带返回的占位页标题栏（Server 模式批阅未接入提示用）。 */
@Composable
private fun ReviewServerModePlaceholder(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MediaImmersiveBackground),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding(),
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "返回",
                tint = MediaTextPrimary,
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = null,
                tint = MediaTextSecondary,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = "真实批阅接入将在 Stage 8B 完成",
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                text = "当前服务器模式已支持媒体浏览、图片查看与视频播放；" +
                    "批阅会话（队列 / 已看 / 断点）属于 Stage 8B 范围。",
                style = MaterialTheme.typography.bodySmall,
                color = MediaTextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
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