package com.mediareview.app.feature.v2.review


import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediareview.app.BuildConfig
import com.mediareview.app.feature.v2.player.V2PlaybackUiState
import com.mediareview.app.feature.v2.player.native.state.awaitPlayerHostReady
import com.mediareview.app.feature.v2.review.data.ReviewQueueItemUi
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
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToLong
import com.mediareview.app.feature.v2.player.native.state.SeekDragEngine
import com.mediareview.app.feature.v2.player.native.state.SpeedGestureState
import com.mediareview.app.feature.v2.player.native.state.SpeedGestureChange
import com.mediareview.app.feature.v2.player.native.state.DoubleTapSeekState
import com.mediareview.app.feature.v2.player.native.ui.PlayerBodyGestureCallbacks
import com.mediareview.app.feature.v2.player.native.ui.rememberPlayerBodyGestures
import com.mediareview.app.feature.v2.player.native.ui.PlayerSeekBar
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
 * 批阅模式（抖音式）：竖屏 VerticalPager，视频优先（VIDEO ONLY）。
 *
 * Stage 8B：**Server 模式不再有占位页** —— 队列来自真实 Review Session（分页窗口 +
 * 绝对索引），播放地址按需解析（当前 P0、下一条 P1），seen / position 写回服务端。
 *
 * - 队列项只含 Metadata（[ReviewQueueItemUi]），播放源由 ViewModel 解析成
 *   [V2PlaybackUiState]（与完整播放器同一套 Direct → 一次 HLS → Error 语义）；
 * - 换页 effect 依据"当前页 item 的 mediaId == 已解析源的 mediaId"才 setUp，
 *   源还没到时保持 Poster（不黑屏、不串媒体）；
 * - Poster 整层淡出：[posterAlpha] 控制 Black/AsyncImage/Scrim/Icon 整层；
 * - Playback Headers：每次换源前与 URL 一起进 GSYVideoOptionBuilder（Critical 3）；
 * - 稳定批阅计时：page == settledPage && !scrolling && revealedPage == page 才计时（High 2）；
 * - 完整播放器返回：恢复原会话与原绝对位置，不重建会话（§37）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V2ReviewScreen(
    vm: V2ReviewViewModel,
    onOpenFullPlayer: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val playback by vm.playback.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.enterReview() }

    // 一次性提示（含"撤销待删除"：只有服务端确认成功才会有这条提示）
    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = if (message.undoMediaId != null) "撤销" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                message.undoMediaId?.let { vm.undoPendingDelete(it) }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        when (val current = state) {
            V2ReviewUiState.Idle,
            V2ReviewUiState.LoadingSession,
            V2ReviewUiState.LoadingQueue,
            -> ReviewLoadingPage(onBack = onBack)

            is V2ReviewUiState.Error -> ReviewErrorPage(
                message = current.message,
                onRetry = { vm.retryEnter() },
                onBack = onBack,
            )

            V2ReviewUiState.Empty -> ReviewCompletePage(
                emptyQueue = true,
                total = 0,
                onRestart = { vm.restartAll() },
                onBack = onBack,
            )

            is V2ReviewUiState.Complete -> ReviewCompletePage(
                emptyQueue = false,
                total = current.totalCount,
                onRestart = { vm.restart() },
                onBack = onBack,
            )

            is V2ReviewUiState.Ready -> ReviewPagerContent(
                vm = vm,
                state = current,
                playback = playback,
                context = context,
                onOpenFullPlayer = onOpenFullPlayer,
                onBack = onBack,
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                // 提示放在56dp顶部操作栏及8dp间距下方，避开底部进度和右侧动作。
                .padding(top = 64.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewPagerContent(
    vm: V2ReviewViewModel,
    state: V2ReviewUiState.Ready,
    playback: V2PlaybackUiState,
    context: android.content.Context,
    onOpenFullPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var lifecycleResumeIntent by remember { mutableStateOf(true) }
    val items = state.items
    val pendingDeleteIds by vm.pendingDeleteIds.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()

    var infoMedia by remember { mutableStateOf<ReviewQueueItemUi?>(null) }
    var moreSheet by remember { mutableStateOf(false) }
    // Sheet 流程（更多 / 视频信息）打开时暂停；关闭按语义恢复。
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetResumePlay by remember { mutableStateOf(false) }
    val playbackReady = playback as? V2PlaybackUiState.Ready
    val playbackFailure = playback as? V2PlaybackUiState.Failed
    val latestPlayback by rememberUpdatedState(playback)
    val latestItems by rememberUpdatedState(items)

    val pagerState = rememberPagerState(initialPage = state.localCurrentIndex) { items.size }

    // Critical 2：Controller 首始源 = 当前页（恢复第 N 条即播放第 N 条）。
    val initialItem = items.getOrNull(state.localCurrentIndex)
    val controller = rememberGSYPlayerController(
        url = null,
        cacheWithPlay = false,
        title = initialItem?.title.orEmpty(),
        autoPlay = false,
        autoPauseResume = false,
    )
    // 当前已加载进播放器的 mediaId（source 一致性的事实源；""=尚未加载）。
    var currentLoadedMediaId by remember { mutableStateOf("") }
    var loadedUrl by remember { mutableStateOf("") }
    var loadedStage by remember { mutableStateOf("") }
    var ownedHost by remember { mutableStateOf<com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer?>(null) }
    var sourceStarted by remember { mutableStateOf(false) }
    // 已完成"播放可用"揭示、允许 Poster 淡出的页面（-1 = 无）。
    var revealedPage by remember { mutableIntStateOf(-1) }
    // Stage 8A §17.2：当前源是否真的出现过画面（Playing 且 position > 0）。
    var hasPlaybackAdvanced by remember { mutableStateOf(false) }
    var userPaused by remember { mutableStateOf(false) }
    var showSourceLoading by remember { mutableStateOf(false) }
    // 重新批阅的显式重载令牌：变化强制换页 effect 重跑（单条队列 scrollToPage 无变化也生效）。
    var playRequestToken by remember { mutableStateOf(0) }
    // 480ms 稳定停留判定（纯逻辑，事件驱动）。
    val stableGate = remember { ReviewStableGate() }
    val snapshot = controller.snapshot.value
    val isPlaying = snapshot.isPlaying

    val speed = remember { SpeedGestureState() }
    val scrub = remember { SeekDragEngine() }
    val bodySeek = remember { SeekDragEngine() }
    val doubleTap = remember { DoubleTapSeekState() }
    val haptic = LocalHapticFeedback.current
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var bodyStartMs by remember { mutableStateOf(0L) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var verticalActive by remember { mutableStateOf(false) }
    var verticalSettling by remember { mutableStateOf(false) }
    var verticalGeneration by remember { mutableIntStateOf(0) }
    var verticalOrigin by remember { mutableIntStateOf(0) }
    var surfaceHeight by remember { mutableIntStateOf(1) }
    var verticalChannel by remember { mutableStateOf<Channel<Float>?>(null) }
    var verticalJob by remember { mutableStateOf<Job?>(null) }

    fun sourceIsCurrent(): Boolean {
        val ready = latestPlayback as? V2PlaybackUiState.Ready ?: return false
        val item = latestItems.getOrNull(pagerState.settledPage) ?: return false
        return ready.source.mediaId == item.mediaId && currentLoadedMediaId == item.mediaId &&
            loadedUrl == ready.endpoint.url && loadedStage == ready.stage.toString()
    }
    fun currentOwnedSourceHost(): com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer? {
        if (!sourceIsCurrent()) return null
        var current: com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer? = null
        controller.withHost { host ->
            val manager = host.getGSYVideoManager()
            val kernel = manager.player?.mediaPlayer as? tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
            if (host === ownedHost && host.isAttachedToWindow && manager.listener() === host &&
                kernel?.dataSource == loadedUrl) current = host
        }
        return current
    }
    fun pauseOwnedHost() {
        val host = ownedHost ?: return
        if (host.isAttachedToWindow && host.getGSYVideoManager().listener() === host) host.onVideoPause()
    }
    fun kernelPlaying(): Boolean = currentOwnedSourceHost()?.getGSYVideoManager()?.isPlaying ?: false
    fun kernelPosition(): Long = currentOwnedSourceHost()?.getGSYVideoManager()?.currentPosition ?: 0L
    fun kernelDuration(): Long = currentOwnedSourceHost()?.getGSYVideoManager()?.duration ?: 0L
    fun resumeCurrent() {
        if (foreground && sourceIsCurrent() && !sheetOpen && !verticalActive && !verticalSettling &&
            !pagerState.isScrollInProgress && !scrub.active && !bodySeek.active && !userPaused) {
            if (!sourceStarted) {
                controller.withHost { host ->
                    if (host === ownedHost && host.isAttachedToWindow) {
                        sourceStarted = true
                        controller.play()
                    }
                }
            } else currentOwnedSourceHost()?.let { host ->
                val resumeState = host.currentState
                host.onVideoResume(false)
                // Buffering completion can restore the UI state after a pause.
                // Resume only this already-started, owned kernel without reloading.
                if ((resumeState == GSYVideoView.CURRENT_STATE_PLAYING ||
                        resumeState == GSYVideoView.CURRENT_STATE_PLAYING_BUFFERING_START) &&
                    currentOwnedSourceHost() === host && !host.getGSYVideoManager().isPlaying) {
                    host.getGSYVideoManager().start()
                }
            }
        }
    }
    fun togglePlayPause() {
        if (!foreground || !sourceIsCurrent() || sheetOpen || verticalActive || verticalSettling) return
        // Preparing may report !isPlaying even though automatic start is requested.
        if (!userPaused) {
            userPaused = true
            pauseOwnedHost()
        } else {
            userPaused = false
            resumeCurrent()
        }
    }
    fun applySpeed(change: SpeedGestureChange) {
        if (foreground && currentOwnedSourceHost() != null) controller.setSpeed(change.speed)
        if (change.haptic) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        change.feedback?.let { feedback = it }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) { delay(800L); feedback = null }
    }
    fun commitSeek(engine: SeekDragEngine) {
        val result = engine.commit()
        if (result == null) return
        seekPreview = null
        if (!foreground || !sourceIsCurrent() || sheetOpen || verticalActive) return
        if (currentOwnedSourceHost() == null) return
        controller.seekTo(result.targetMs)
        if (result.resumePlaying) resumeCurrent()
    }
    fun cancelSeek(engine: SeekDragEngine) {
        val resume = engine.cancel()
        seekPreview = null
        if (resume) resumeCurrent()
    }
    fun stopVertical(cancel: Boolean, totalDy: Float = 0f) {
        if (!verticalActive || verticalSettling) return
        verticalSettling = true
        val generation = verticalGeneration
        val channel = verticalChannel
        val job = verticalJob
        verticalChannel = null
        verticalJob = null
        channel?.close()
        val origin = verticalOrigin
        scope.launch {
            try {
                if (cancel) job?.cancel()
                job?.join()
                if (generation != verticalGeneration) return@launch
                val delta = when { totalDy < -.12f -> 1; totalDy > .12f -> -1; else -> 0 }
                val lastIndex = latestItems.lastIndex
                if (lastIndex < 0) return@launch
                val target = if (cancel) origin.coerceIn(0, lastIndex) else (origin + delta).coerceIn(0, lastIndex)
                pagerState.animateScrollToPage(target)
            } finally {
                if (generation == verticalGeneration) {
                    verticalActive = false
                    verticalSettling = false
                }
            }
        }
    }
    val bodyModifier = rememberPlayerBodyGestures(
        enabled = foreground && !sheetOpen && !verticalSettling && items.isNotEmpty(),
        speed = speed,
        callbacks = PlayerBodyGestureCallbacks(
            isPlaying = ::kernelPlaying,
            onTap = ::togglePlayPause,
            onDoubleTap = { side ->
                if (foreground && currentOwnedSourceHost() != null && !verticalActive) {
                    val tap = doubleTap.tap(side, SystemClock.uptimeMillis(), kernelPosition(), kernelDuration())
                    controller.seekTo(tap.targetMs)
                    feedback = "${if (tap.accumulatedDeltaMs > 0) "+" else ""}${tap.accumulatedDeltaMs / 1000}s"
                }
            },
            onSpeedChange = ::applySpeed,
            onSeekStart = {
                if (foreground && sourceIsCurrent() && !verticalActive && !verticalSettling) {
                    bodyStartMs = kernelPosition()
                    if (bodySeek.begin(bodyStartMs, kernelDuration(), kernelPlaying())) pauseOwnedHost()
                    seekPreview = bodySeek.previewMs
                }
            },
            onSeekMove = { fraction ->
                bodySeek.preview(bodyStartMs + (fraction * kernelDuration()).roundToLong())
                seekPreview = bodySeek.previewMs
            },
            onSeekEnd = { commitSeek(bodySeek) },
            onSeekCancel = { cancelSeek(bodySeek) },
            onVerticalStart = {
                // Shared GestureDirectionLock exclusively owns this sequence; no second Pager pointer recognizer.
                if (foreground && !verticalActive && !verticalSettling) {
                    verticalGeneration++
                    verticalOrigin = pagerState.settledPage
                    verticalActive = true
                    pauseOwnedHost()
                    val channel = Channel<Float>(Channel.UNLIMITED)
                    verticalChannel = channel
                    verticalJob = scope.launch {
                        pagerState.scroll(MutatePriority.UserInput) {
                            for (delta in channel) scrollBy(-delta * surfaceHeight)
                        }
                    }
                }
            },
            onVerticalMove = { delta, _, _ -> verticalChannel?.trySend(delta) },
            onVerticalEnd = { stopVertical(false, it) },
            onVerticalCancel = { stopVertical(true) },
            onActive = {},
        ),
    )
    // Lifecycle callbacks stop sound synchronously; keeping the composition is not an exit.
    // userPaused remains the explicit user intent, never inferred from our temporary pause.
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (foreground) lifecycleResumeIntent = !userPaused
                    foreground = false
                    controller.withHost { host ->
                        if (host.getGSYVideoManager().listener() === host) host.onVideoPause()
                    }
                    scrub.cancel()
                    bodySeek.cancel()
                    seekPreview = null
                    doubleTap.reset()
                    val restoredSpeed = speed.cancel().speed
                    if (currentOwnedSourceHost() != null) controller.setSpeed(restoredSpeed)
                    feedback = null
                    stopVertical(cancel = true)
                    stableGate.reset()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!lifecycleResumeIntent) userPaused = true
                    foreground = true
                    resumeCurrent()
                    // Resume synchronously through the same source, owner and interaction gates.
                    // The setup effect remains responsible for source arrival and initial setup.
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller) {
        onDispose {
            verticalGeneration++
            verticalChannel?.close()
            verticalJob?.cancel()
            scrub.cancel(); bodySeek.cancel(); speed.cancel()
            val host = ownedHost
            val manager = host?.getGSYVideoManager()
            if (host != null && manager?.listener() === host) {
                host.onVideoPause()
                host.release()
                if (manager!!.listener() === host) manager.setListener(null)
                if (manager.lastListener() === host) manager.setLastListener(null)
            }
            ownedHost = null
        }
    }
    LaunchedEffect(pagerState.settledPage, playbackReady?.source?.mediaId, playbackReady?.endpoint?.url,
        playbackReady?.stage, pagerState.isScrollInProgress) {
        if (!sourceIsCurrent() || pagerState.isScrollInProgress) {
            // No old-source resume after cancel/up when P0 is Loading or the page changed.
            scrub.cancel(); bodySeek.cancel(); seekPreview = null
            doubleTap.reset(); applySpeed(speed.cancel())
            pauseOwnedHost()
            revealedPage = -1
        }
    }

    // 进入时固定视觉策略（Review 统一 FIT）。
    LaunchedEffect(Unit) {
        GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_DEFAULT)
        logSourceState("initial", initialItem, currentLoadedMediaId)
    }

    // 换页立即隐藏 Poster（新页内容尚未揭示）；setUp 由下方 effect 在源到达后执行。
    LaunchedEffect(pagerState.settledPage) {
        revealedPage = -1
    }

    // ---------- 换页 / 源到达：setUp(headers) → 等 play 可用 → 揭示 ----------
    LaunchedEffect(pagerState.settledPage, playRequestToken, playbackReady?.source?.mediaId, playbackReady?.stage, playbackReady?.endpoint?.url, verticalActive, verticalSettling, userPaused, sheetOpen, foreground, pagerState.isScrollInProgress) {
        if (!foreground || verticalActive || verticalSettling || pagerState.isScrollInProgress) {
            return@LaunchedEffect
        }
        val page = pagerState.settledPage
        val item = items.getOrNull(page) ?: return@LaunchedEffect
        val ready = playbackReady ?: return@LaunchedEffect
        // 源与当前页不是同一条：等待（禁止串媒体）
        if (ready.source.mediaId != item.mediaId) return@LaunchedEffect
        val changedMedia = currentLoadedMediaId != item.mediaId
        val mustSetup = changedMedia || loadedUrl != ready.endpoint.url || loadedStage != ready.stage.toString()
        if (mustSetup) {
            pauseOwnedHost()
            // Critical 3：Headers 必须与 URL 一起进 GSYVideoOptionBuilder（只 setHeaders 会被 setUp 覆盖）
            val option = GSYVideoOptionBuilder()
                .setUrl(ready.endpoint.url)
                .setCacheWithPlay(false)
                .setVideoTitle(item.title)
                .setMapHeadData(ready.endpoint.headers.ifEmpty { null })
            if (!awaitPlayerHostReady(controller)) return@LaunchedEffect
            val latestReadyAfterWait = latestPlayback as? V2PlaybackUiState.Ready ?: return@LaunchedEffect
            if (!foreground || verticalActive || verticalSettling || pagerState.isScrollInProgress ||
                pagerState.settledPage != page || latestItems.getOrNull(page)?.mediaId != item.mediaId ||
                latestReadyAfterWait.source.mediaId != item.mediaId ||
                latestReadyAfterWait.endpoint.url != ready.endpoint.url ||
                latestReadyAfterWait.stage != ready.stage) return@LaunchedEffect
            controller.withHost { ownedHost = it }
            controller.setUp(option, false)
            currentLoadedMediaId = item.mediaId
            loadedUrl = ready.endpoint.url
            loadedStage = ready.stage.toString()
            sourceStarted = false
            // 换媒体即重置"曾经出现过画面"判定
            hasPlaybackAdvanced = false
            // Same-media fallback/replacement keeps explicit pause intent across backgrounding.
            if (changedMedia) userPaused = false
            logSourceState("switch page=$page", item, currentLoadedMediaId)
        }
        // 明确收敛的 host 等待：可取消、2s 超时；超时保留 Poster（不堆 postDelayed）。
        val hostReady = awaitPlayerHostReady(controller)
        if (!hostReady) return@LaunchedEffect
        controller.withHost { ownedHost = it }
        delay(120L)
        // 取消保护 + 二次确认：仍是同一页、且播放器里仍是同一条媒体才真正 play。
        if (pagerState.settledPage != page || currentLoadedMediaId != item.mediaId) {
            return@LaunchedEffect
        }
        if (!sourceIsCurrent()) return@LaunchedEffect
        resumeCurrent()
        if (currentOwnedSourceHost() != null) controller.setSpeed(speed.effectiveSpeed)
    }

    // Apply retained formal/effective speed only when the prepared source owns the live kernel.
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            if (event is com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerEvent.Prepared) {
                val host = currentOwnedSourceHost() ?: return@collect
                if (!foreground || userPaused || sheetOpen || verticalActive || verticalSettling ||
                    scrub.active || bodySeek.active || pagerState.isScrollInProgress) host.onVideoPause()
                if (!foreground) return@collect
                controller.setSpeed(speed.effectiveSpeed)
                resumeCurrent()
            }
        }
    }

    // Observation is separate from resume/setup: a seek pause must never auto-resume on a snapshot change.
    LaunchedEffect(pagerState.settledPage, loadedUrl, loadedStage, sourceStarted, snapshot.isPlaying,
        userPaused, sheetOpen, foreground, verticalActive, verticalSettling, pagerState.isScrollInProgress) {
        val page = pagerState.settledPage
        val item = latestItems.getOrNull(page) ?: return@LaunchedEffect
        val ready = latestPlayback as? V2PlaybackUiState.Ready ?: return@LaunchedEffect
        if (!foreground || !sourceIsCurrent() || !sourceStarted || userPaused || sheetOpen || verticalActive ||
            verticalSettling || pagerState.isScrollInProgress) return@LaunchedEffect
        // 画面确实在输出后才淡出 Poster（避免 Playing 但首帧未渲染的黑帧窗口）。
        val playReady = withTimeoutOrNull(PLAYBACK_READY_TIMEOUT_MS) {
            var firstPositive: Long? = null
            var observedPlayer: tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer? = null
            while (true) {
                if (!foreground || !sourceIsCurrent() || loadedUrl != ready.endpoint.url || loadedStage != ready.stage.toString()) {
                    return@withTimeoutOrNull false
                }
                var currentPlayer: tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer? = null
                var position = 0L
                var playing = false
                controller.withHost { host ->
                    if (currentOwnedSourceHost() !== host) return@withHost
                    currentPlayer = (host.getGSYVideoManager().player?.mediaPlayer as? tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer)
                        ?.takeIf { it.dataSource == ready.endpoint.url }
                    if (currentPlayer != null) {
                        position = host.getGSYVideoManager().currentPosition
                        playing = host.getGSYVideoManager().isPlaying
                    }
                }
                if (currentPlayer !== observedPlayer) {
                    observedPlayer = currentPlayer
                    firstPositive = null
                }
                if (currentPlayer != null && hasPlaybackAdvanced) return@withTimeoutOrNull true
                if (currentPlayer != null && playing && position > 0L) {
                    val baseline = firstPositive
                    if (baseline == null || position < baseline) firstPositive = position
                    else if (position >= baseline + 500L) {
                        hasPlaybackAdvanced = true
                        return@withTimeoutOrNull true
                    }
                } else firstPositive = null
                delay(50L)
            }
        }
        if (BuildConfig.DEBUG) {
            val s = controller.snapshot.value
            android.util.Log.d(
                "MRReview",
                "PlaybackReady[page=$page] ok=$playReady state=${s.state} posMs=${s.currentPosition} " +
                    "mediaId=${item.mediaId} advanced=$hasPlaybackAdvanced",
            )
        }
        if (foreground && playReady == true && sourceIsCurrent()) {
            revealedPage = page
            vm.onPlaybackStable(item.mediaId)
        }
    }

    LaunchedEffect(revealedPage, pagerState.settledPage, playbackReady?.endpoint?.url) {
        showSourceLoading = false
        if (revealedPage != pagerState.settledPage && playbackFailure == null) {
            delay(300L)
            if (revealedPage != pagerState.settledPage) showSourceLoading = true
        }
    }

    // ---------- 自动批阅：内容可见（revealed）后才开始稳定 ~480ms 计时（High 2 语义不变） ----------
    LaunchedEffect(items, foreground) {
        snapshotFlow {
            Triple(pagerState.settledPage, pagerState.isScrollInProgress, revealedPage)
        }
            .distinctUntilChanged()
            .collectLatest { (page, scrolling, revealed) ->
                val now = SystemClock.uptimeMillis()
                // 滚动中 / 页面无效 / 内容未揭示（Poster 或 Loading 中）→ 立即取消计时
                if (!foreground || scrolling || page < 0 || revealed != page) {
                    stableGate.onEvent(page, true, now)
                    return@collectLatest
                }
                stableGate.onEvent(page, false, now)
                delay(REVIEW_STABLE_MS_DEFAULT)
                // 醒来后再次确认：同页、未滚动、内容仍可见
                if (foreground && !pagerState.isScrollInProgress && pagerState.settledPage == page && revealedPage == page) {
                    val confirmed = stableGate.onEvent(page, false, SystemClock.uptimeMillis())
                    if (confirmed != null) {
                        stableGate.reset()
                        // seen 只在服务端确认成功后才会更新本地（§26）
                        vm.markSeen(page)
                    }
                }
            }
    }

    // Session 同步：当前页变化（本地索引 → 绝对索引由 ViewModel 换算）。
    LaunchedEffect(pagerState.settledPage) {
        vm.onPageSettled(pagerState.settledPage)
    }

    // 恢复定位 / 前置分页后的偏移补偿：ViewModel 的 localCurrentIndex 变化时对齐 pager。
    LaunchedEffect(state.sessionId, state.localCurrentIndex, items.size) {
        if (state.localCurrentIndex in 0..items.lastIndex && pagerState.currentPage != state.localCurrentIndex) {
            pagerState.scrollToPage(state.localCurrentIndex)
        }
    }

    // Sheet 关闭的两个语义（High 1）
    fun openSheetFlow() {
        if (!sheetOpen) {
            sheetOpen = true
            // Preparing still owns an autoplay intent; GSY records pause-before-prepared.
            sheetResumePlay = !userPaused && sourceIsCurrent()
            pauseOwnedHost()
            if (BuildConfig.DEBUG) android.util.Log.d("MRReview", "Sheet.open wasPlaying=$sheetResumePlay → pause")
        }
    }

    /** 正常关闭（外部点 / Back / 视频信息关闭）：按进入 Sheet 前的播放状态恢复。 */
    fun closeSheetAndResume() {
        sheetOpen = false
        if (sheetResumePlay) resumeCurrent()
        sheetResumePlay = false
    }

    /** 关闭且不恢复播放（进入完整播放器前）：杜绝 closeSheet 瞬间 resume → pause 抖动。 */
    fun closeSheetWithoutResume() {
        sheetOpen = false
        sheetResumePlay = false
    }

    Box(modifier = Modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        // 常驻视频 Surface：换页时 setUp 换源（单 Controller，不做双 Player）。
        GSYPlayerSurface(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
        )

        // Pager：每页一个 Poster 层（posterAlpha 控制整个层，淡出后 Surface 完全露出）。
        VerticalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize().testTag("review_pager"),
        ) { page ->
            val item = items[page]
            val isCurrent = page == pagerState.settledPage
            val posterAlpha by animateFloatAsState(
                targetValue = if (isCurrent && page == revealedPage) 0f else 1f,
                animationSpec = tween(POSTER_FADE_MS),
                label = "reviewPosterFade",
            )
            boxOfPosterStack(item, context, posterAlpha)
        }

        Box(Modifier.fillMaxSize().onSizeChanged { surfaceHeight = it.height.coerceAtLeast(1) }
            .testTag("review_gesture_surface").then(bodyModifier))

        // 暂停态：中央轻量 ▶（单击 = 播放/暂停，抖音式，不整体隐藏 UI）。
        if (userPaused && sourceIsCurrent() && !sheetOpen && !verticalActive) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clickable { togglePlayPause() },
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

        // 播放源失败：明确提示 + 重试（不假成功）。
        playbackFailure?.let { failure ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MediaDanger,
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        text = failure.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MediaTextPrimary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { vm.retryPlayback() }) {
                        Text("重试", color = V2Colors.Accent)
                    }
                }
            }
        }

        val currentItem = items.getOrNull(pagerState.settledPage)

        // 顶部：返回 + 绝对进度（绝对索引 + 1 / total）。
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
                text = currentItem?.let { "${it.absoluteIndex + 1} / ${state.totalCount}" }.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 底部：标题 + 编号 · 文件夹 · 时长。
        currentItem?.let { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 90.dp, top = 24.dp, bottom = 48.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MediaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle(),
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
                    .padding(horizontal = 14.dp, vertical = 52.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ReviewActionButton(
                    icon = if (item.mediaId in favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    tint = if (item.mediaId in favoriteIds) V2Colors.Favorite else MediaTextPrimary,
                    label = if (item.mediaId in favoriteIds) "已收藏" else "收藏",
                    onClick = { vm.toggleFavorite(item.mediaId) },
                )
                ReviewActionButton(
                    icon = if (item.mediaId in pendingDeleteIds) Icons.Default.Undo else Icons.Default.DeleteOutline,
                    tint = if (item.mediaId in pendingDeleteIds) MediaDanger else MediaTextPrimary,
                    label = if (item.mediaId in pendingDeleteIds) "撤销" else "待删除",
                    onClick = {
                        if (item.mediaId in pendingDeleteIds) {
                            vm.undoPendingDelete(item.mediaId)
                        } else {
                            // 成功提示（含"撤销"）由 ViewModel 在服务端确认后发出
                            vm.addPendingDelete(item.mediaId)
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

        // 更多 → 统一 ModalBottomSheet（外部点击 / Back 可关闭）。
        if (moreSheet) {
            ModalBottomSheet(onDismissRequest = { moreSheet = false; closeSheetAndResume() }) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Text("播放倍速", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SpeedGestureState.FORMAL_SPEEDS.forEach { formal ->
                            TextButton(onClick = {
                                applySpeed(speed.formalSpeedChanged(formal))
                                moreSheet = false
                                closeSheetAndResume()
                            }) { Text("${formal}×") }
                        }
                    }
                    MoreSheetItem(
                        icon = Icons.Default.PlayArrow,
                        label = "打开完整播放器",
                        onClick = {
                            moreSheet = false
                            closeSheetWithoutResume()
                            val item = items.getOrNull(pagerState.settledPage)
                            vm.onPageSettled(pagerState.settledPage)
                            pauseOwnedHost()
                            // §37：离开前写回当前绝对位置；返回时恢复原会话（不重建）
                            vm.onLeaveForFullPlayer()
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "MRReview",
                                    "FullPlayer.pause+positionSave+navigate mediaId=${item?.mediaId}",
                                )
                            }
                            item?.let { onOpenFullPlayer(it.mediaId) }
                        },
                    )
                    MoreSheetItem(
                        icon = Icons.Default.MoreVert,
                        label = "视频信息",
                        onClick = {
                            moreSheet = false
                            // 仍在 Sheet 流程内：保持暂停，不提前恢复。
                            infoMedia = items.getOrNull(pagerState.settledPage)
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

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            if (showSourceLoading && playbackFailure == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(20.dp)
                    .padding(bottom = 4.dp).testTag("review_source_loading"), color = Color.White, strokeWidth = 2.dp)
            }
            (feedback ?: seekPreview?.let { "${it / 1000}s / ${kernelDuration() / 1000}s" })?.let {
                Text(it, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp), color = Color.White)
            }
            PlayerSeekBar(
                positionMs = seekPreview ?: snapshot.currentPosition,
                durationMs = snapshot.duration,
                bufferPercent = snapshot.bufferPercent,
                trackHeight = 2.5.dp,
                enabled = foreground && sourceIsCurrent() && !sheetOpen && !verticalActive,
                tag = "review_seek_bar",
                onDragStart = {
                    if (scrub.begin(kernelPosition(), kernelDuration(), kernelPlaying())) pauseOwnedHost()
                    seekPreview = scrub.previewMs
                },
                onPreview = { scrub.preview(it); seekPreview = scrub.previewMs },
                onCommit = { commitSeek(scrub) },
                onCancel = { cancelSeek(scrub) },
            )
        }

    }

    // 重新批阅（sessionId 变化 = 新建会话）：回到第 1 条并重置播放揭示状态。
    var lastSessionId by remember { mutableStateOf(state.sessionId) }
    LaunchedEffect(state.sessionId) {
        if (state.sessionId == lastSessionId) return@LaunchedEffect
        lastSessionId = state.sessionId
        stableGate.reset()
        revealedPage = -1
        currentLoadedMediaId = ""
        sourceStarted = false
        hasPlaybackAdvanced = false
        playRequestToken++
        scope.launch { pagerState.scrollToPage(0) }
    }
}

/** 队列项副标题：编号（可能为空）· 文件夹 · 时长。 */
private fun ReviewQueueItemUi.subtitle(): String = listOfNotNull(
    code.takeIf { it.isNotBlank() },
    folderName.takeIf { it.isNotBlank() },
    "${durationSeconds}s",
).joinToString(" · ")

/**
 * Poster Layer 组合：整个层由一个 [posterAlpha] 控制。
 * posterAlpha=1 → 不透明白底 + 封面 + 遮罩 + 播放标记；posterAlpha=0 → 整层透明，
 * 底层 [GSYPlayerSurface] 完全露出（真看到视频而非静态封面）。
 */
@Composable
private fun boxOfPosterStack(
    item: ReviewQueueItemUi,
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
                .data(item.coverUrl)
                .crossfade(false)
                .size(720, 1280)
                .build(),
            contentDescription = item.title,
            // 与视频画面同一视觉策略（Review Display Mode = FIT）。
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        )

    }
}

/**
 * Source 一致性 Debug 证据：记录 UI 目标 mediaId、播放器当前加载 [loadedMediaId]；
 * 不打印 URL / token / 敏感头值（Stage 8B：URL 只可能是临时播放端点的 path 摘要）。
 */
private fun logSourceState(tag: String, item: ReviewQueueItemUi?, loadedMediaId: String) {
    if (!BuildConfig.DEBUG) return
    android.util.Log.d(
        "MRReview",
        "SourceState[$tag] UI/target mediaId=${item?.mediaId} loadedInPlayer=$loadedMediaId",
    )
}

/** 抖音式右侧动作按钮：圆形半透明底 + 图标 + 小字。 */
@Composable
private fun ReviewActionButton(
    icon: ImageVector,
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
    icon: ImageVector,
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

/** 视频信息 Sheet（数据全部来自 [ReviewQueueItemUi]，不接触 Demo 资源）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GsyNativeReviewInfoSheet(
    media: ReviewQueueItemUi,
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
            InfoSheetRow(
                "分辨率",
                if (media.naturalWidth > 0) "${media.naturalWidth} × ${media.naturalHeight}" else "未知",
            )
            if (media.code.isNotBlank()) InfoSheetRow("编号", media.code)
            InfoSheetRow("文件夹", media.folderName)
            InfoSheetRow("媒体 ID", media.mediaId)
            InfoSheetRow("队列位置", "${media.absoluteIndex + 1}")
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

/** 进入批阅时的加载页（恢复会话 / 加载队列）。 */
@Composable
private fun ReviewLoadingPage(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        Text(
            text = "正在恢复批阅…",
            style = MaterialTheme.typography.bodyMedium,
            color = MediaTextSecondary,
        )
    }
}

/**
 * 进入失败页（§17）：latest 网络失败 / 分页失败时显示"无法恢复批阅 + 重新尝试"，
 * **绝不**自动新建会话（会把用户进度重置）。
 */
@Composable
private fun ReviewErrorPage(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MediaDanger,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = "无法恢复批阅",
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MediaTextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = V2Colors.Accent,
                onClick = onRetry,
                modifier = Modifier.padding(top = 18.dp),
            ) {
                Text(
                    text = "重新尝试",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 完成页 / 空队列页（"重新批阅"在 Server 模式 = 新建会话，§39）。 */
@Composable
private fun ReviewCompletePage(
    emptyQueue: Boolean,
    total: Int,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MediaImmersiveBackground),
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
