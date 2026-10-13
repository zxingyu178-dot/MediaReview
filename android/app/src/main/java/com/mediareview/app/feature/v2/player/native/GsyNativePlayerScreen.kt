package com.mediareview.app.feature.v2.player.native

import android.app.Activity
import com.mediareview.app.feature.v2.player.gsy.detachRetiredKernelErrorListener
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mediareview.app.BuildConfig
import com.mediareview.app.feature.v2.model.V2PlaybackStage
import com.mediareview.app.feature.v2.perf.V2Perf
import com.mediareview.app.feature.v2.player.V2PlaybackUiState
import com.mediareview.app.feature.v2.player.V2PlayerState
import com.mediareview.app.feature.v2.player.native.state.BackNavigationLogic
import com.mediareview.app.feature.v2.player.native.state.ControlsVisibilityState
import com.mediareview.app.feature.v2.player.native.state.PlaybackUiMapper
import com.mediareview.app.feature.v2.player.native.state.SeekDragEngine
import com.mediareview.app.feature.v2.player.native.state.PlayerSeekMapping
import com.mediareview.app.feature.v2.player.native.state.VideoScaleState
import com.mediareview.app.feature.v2.player.native.state.SpeedGestureState
import com.mediareview.app.feature.v2.player.native.state.SpeedGestureMode
import com.mediareview.app.feature.v2.player.native.state.SpeedGestureChange
import com.mediareview.app.feature.v2.player.native.state.DoubleTapSeekState
import com.mediareview.app.feature.v2.player.native.ui.PlayerBodyGestureCallbacks
import com.mediareview.app.feature.v2.player.native.ui.rememberPlayerBodyGestures
import com.mediareview.app.feature.v2.player.native.state.awaitPlayerHostReady
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeBottomBar
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeCenterControls
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeIndicators
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeMoreSheet
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeScaleSheet
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeSeekHint
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeSpeedSheet
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeTopBar
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeVideoInfoSheet
import com.mediareview.app.feature.v2.player.native.ui.PlayerLayoutDebug
import com.mediareview.app.feature.v2.player.native.ui.formatSpeedLabel
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.shuyu.gsyvideoplayer.builder.GSYVideoOptionBuilder
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayState
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerEvent
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerSurface
import com.shuyu.gsyvideoplayer.compose.native_.rememberGSYPlayerController
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 底部面板类型。 */
private enum class PlayerSheet { MORE, SPEED, SCALE, INFO, AUDIO }

/** 播放进度上报间隔（Stage 8A §27：10~15 秒，禁止高频上报）。 */
private const val PROGRESS_REPORT_INTERVAL_MS = 15_000L

/**
 * GSY Native Compose 正式播放器 V1（Stage 8A：播放源改为异步解析）。
 *
 * 架构：V2NativePlayerViewModel（resolvePlayback → UiState）→ GSYPlayerController → GSYPlayerSurface。
 *
 * - **本组合函数不发任何网络请求**：URL / headers 全部来自 [V2PlayerState]，
 *   Loading / Ready / Error 三态由 ViewModel 收敛；
 * - Ready 到达时成对切换 `setHeaders` + `setUp`（A 的 header 绝不泄漏到 B）；
 * - 上一条 / 下一条只请求索引变化（`onRequestIndex`），由 ViewModel 解析该条播放源；
 * - Direct Play → 内核报错时由 ViewModel 决定"一次 HLS 回退 → Error"，本层只上报错误；
 * - resume_position_ms > 0 时在 Prepared 后 seek 到续播位置；
 * - 播放进度按 开始 / 暂停 / 退出 / 每 15 秒上报（Server 模式真正发请求）；
 * - 播放 / 暂停 / Seek / 状态 / 全屏 / 音量亮度手势等仍由 GSY 提供，MediaReview 只负责控制层 UI。
 */
@Composable
fun GsyNativePlayerScreen(
    state: V2PlayerState,
    onRequestIndex: (Int) -> Unit,
    /**
     * 内核报错上报（Stage 8B §7）：必须带上"报错时播放器实际装载的 mediaId"，
     * 让 ViewModel 能识别并丢弃旧媒体的迟到错误（禁止串媒体）。
     */
    onPlaybackFailed: (String) -> Unit,
    onRetry: () -> Unit,
    onReportProgress: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlaybackStable: (String) -> Unit = {},
    onReportLoadedProgress: (String, Long, Boolean) -> Unit = { _, position, paused -> onReportProgress(position, paused) },
    pendingDeleteEnabled: Boolean = false,
    isPendingDelete: Boolean = false,
    pendingDeleteLabel: String? = null,
    onTogglePendingDelete: () -> Unit = {},
    onRetryPendingDelete: (() -> Unit)? = null,
) {
    val appContext = LocalContext.current
    val activity = remember { appContext.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var userWantsPlayback by remember { mutableStateOf(true) }
    var sourceStarted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val audio = remember { appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var gestureActive by remember { mutableStateOf(false) }
    var speedMode by remember { mutableStateOf(SpeedGestureMode.Normal) }
    var speedFeedback by remember { mutableStateOf<String?>(null) }
    var speedHintJob by remember { mutableStateOf<Job?>(null) }
    var verticalStartBrightness by remember { mutableStateOf(.5f) }
    var verticalStartVolume by remember { mutableStateOf(0) }

    val ready = state.ui as? V2PlaybackUiState.Ready
    val failure = state.ui as? V2PlaybackUiState.Failed
    var playbackAdvanced by remember(state.currentMediaId, ready?.stage, ready?.endpoint?.url) { mutableStateOf(false) }
    val posterAlpha by animateFloatAsState(if (playbackAdvanced) 0f else 1f, tween(180), label = "playerPoster")

    // autoPlay=false：库内部在 AndroidView factory 中 attachHost 后立即 post startPlayLogic，
    // 冷启动 / JIT 繁忙时宿主可能尚未完成首次布局。下方 LaunchedEffect 等 host attach 且
    // 完成首次布局（width/height>0）后再自管播放，保证启动时渲染容器尺寸确定。
    val controller = rememberGSYPlayerController(
        url = null,
        cacheWithPlay = false,
        title = ready?.source?.title ?: state.current?.title ?: "",
        autoPlay = false,
        autoPauseResume = false,
    )

    // ---------- MediaReview 交互状态 ----------
    var controlsVisible by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var seekHint by remember { mutableStateOf<GsyNativeSeekHint?>(null) }
    var brightnessPct by remember { mutableStateOf<Int?>(null) }
    var volumePct by remember { mutableStateOf<Int?>(null) }
    var hintJob by remember { mutableStateOf<Job?>(null) }
    var savedBrightness by remember { mutableStateOf(-1f) }
    var savedOrientation by remember { mutableStateOf(-1) }

    // 已应用到播放器的媒体 / 阶段（source 一致性事实源：同一条目同一阶段不重复 setUp）
    var loadedMediaId by remember { mutableStateOf("") }
    var loadedEndpointUrl by remember { mutableStateOf("") }
    var loadedStage by remember { mutableStateOf(ready?.stage ?: V2PlaybackStage.DIRECT) }
    // 待恢复的续播位置（服务端 resume_position_ms；Prepared 后 seek）
    var pendingResumeMs by remember { mutableStateOf(0L) }
    // AndroidView can detach the controller before its DisposableEffect releases it.
    var ownedHost by remember { mutableStateOf<com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer?>(null) }

    // 锁定真值不存放于此：唯一来源是 controller.snapshot.isLocked
    val controls = remember {
        ControlsVisibilityState(
            scope = scope,
            onVisibilityChange = { visible -> controlsVisible = visible },
        )
    }
    val scrub = remember { SeekDragEngine() }
    var scrubPreviewMs by remember { mutableStateOf(0L) }
    val bodySeek = remember { SeekDragEngine() }
    var bodySeekPreviewMs by remember { mutableStateOf(0L) }
    var bodySeekStartMs by remember { mutableStateOf(0L) }
    var bodySeekDurationMs by remember { mutableStateOf(0L) }
    // 拖动（进度条 / 横向手势）开始前是否在播放：拖动期暂停以冻结画面与声音，
    // 松手 seek 后据此恢复播放（成熟播放器标准手感）。
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var resumeAfterSeekGesture by remember { mutableStateOf(false) }
    val speed = remember { SpeedGestureState() }
    val scale = remember { VideoScaleState() }
    val tap = remember { DoubleTapSeekState() }

    // snapshot 为唯一播放状态来源
    val snapshot = controller.snapshot.value
    val locked = snapshot.isLocked
    val sourceIsCurrent = ready != null && ready.source.mediaId == state.currentMediaId &&
        ready.source.mediaId == loadedMediaId && ready.stage == loadedStage && ready.endpoint.url == loadedEndpointUrl

    val audioTracks = remember(controller) {
        com.mediareview.app.feature.v2.player.native.tracks.GsyAudioTrackAdapter {
            var player: tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer? = null
            controller.withHost { host ->
                player = (host.getGSYVideoManager().player?.mediaPlayer as? tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer)
                    ?.takeIf { it.dataSource == loadedEndpointUrl }
            }
            player
        }
    }
    var audioTrackSnapshot by remember { mutableStateOf<com.mediareview.app.feature.v2.player.native.tracks.AudioTrackSnapshot?>(null) }
    val audioSourceKey = "$loadedMediaId|$loadedStage|$loadedEndpointUrl"
    val latestAudioSource by rememberUpdatedState(sourceIsCurrent to audioSourceKey)
    val latestPlaybackState by rememberUpdatedState(state)
    fun sourceMatchesLoaded(): Boolean {
        val current = latestPlaybackState.ui as? V2PlaybackUiState.Ready ?: return false
        return current.source.mediaId == latestPlaybackState.currentMediaId && current.source.mediaId == loadedMediaId &&
            current.stage == loadedStage && current.endpoint.url == loadedEndpointUrl
    }
    fun currentOwnedSourceHost(): com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer? {
        if (!sourceMatchesLoaded()) return null
        val host = ownedHost ?: return null
        var attachedOwner = false
        controller.withHost { attachedOwner = it === host }
        if (!attachedOwner) return null
        val manager = host.getGSYVideoManager()
        val kernel = manager.player?.mediaPlayer as? tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
        return host.takeIf { manager.listener() === host && kernel?.dataSource == loadedEndpointUrl }
    }
    fun pauseOwned() {
        val host = ownedHost ?: return
        if (host.getGSYVideoManager().listener() === host) host.onVideoPause()
    }
    fun resumeOwned(explicitReplay: Boolean = false) {
        if (!foreground || !userWantsPlayback || !sourceMatchesLoaded() || scrub.active || bodySeek.active) return
        val host = ownedHost ?: return
        if (sourceStarted && host.getGSYVideoManager().listener() !== host) return
        if (!sourceStarted || explicitReplay || host.currentState == GSYVideoView.CURRENT_STATE_ERROR) {
            controller.withHost { attached ->
                if (attached === host) { sourceStarted = true; controller.play() }
            }
            return
        }
        if (currentOwnedSourceHost() !== host || host.currentState == GSYVideoView.CURRENT_STATE_AUTO_COMPLETE) return
        if (pendingResumeMs > 0L && host.currentState != GSYVideoView.CURRENT_STATE_PREPAREING) {
            controller.seekTo(pendingResumeMs)
            pendingResumeMs = 0L
        }
        val resumeState = host.currentState
        host.onVideoResume(false)
        // A late buffering-end callback can restore PLAYING after our pause, while
        // the same kernel remains paused. GSY's resume only handles its PAUSE state.
        if ((resumeState == GSYVideoView.CURRENT_STATE_PLAYING ||
                resumeState == GSYVideoView.CURRENT_STATE_PLAYING_BUFFERING_START) &&
            currentOwnedSourceHost() === host && !host.getGSYVideoManager().isPlaying) {
            host.getGSYVideoManager().start()
        }
    }
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (sourceMatchesLoaded() && ownedHost?.currentState == GSYVideoView.CURRENT_STATE_AUTO_COMPLETE) userWantsPlayback = false
                    foreground = false
                    pauseOwned()
                    scrub.cancel(); bodySeek.cancel()
                    resumeAfterScrub = false; resumeAfterSeekGesture = false
                    gestureActive = false
                    tap.reset()
                    val formalSpeed = speed.cancel().speed
                    if (currentOwnedSourceHost() != null) controller.setSpeed(formalSpeed)
                    speedMode = speed.mode
                    speedFeedback = null
                }
                Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    resumeOwned()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(snapshot.state) {
        if (snapshot.state == GSYPlayState.Completed && sourceMatchesLoaded() && ownedHost?.currentState == GSYVideoView.CURRENT_STATE_AUTO_COMPLETE) {
            userWantsPlayback = false
        }
    }
    LaunchedEffect(audioTracks, sourceIsCurrent, audioSourceKey) {
        if (!sourceIsCurrent) {
            audioTrackSnapshot = null
            if (sheet == PlayerSheet.AUDIO) sheet = null
        } else {
            while (true) {
                audioTrackSnapshot = audioTracks.read(audioSourceKey)
                delay(500)
            }
        }
    }

    // ---------- 事件订阅 ----------
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                // 全屏由 MediaReview 自管（enterFullscreen/exitFullscreen），不依赖 GSY 经典
                // View 迁移事件，故此处不再用 EnterFull/QuitFull 驱动 isFullscreen。
                is GSYPlayerEvent.Prepared -> {
                    // Ignore queued events after source replacement or owner handoff before any kernel write.
                    val host = currentOwnedSourceHost() ?: return@collect
                    if (!foreground) {
                        host.onVideoPause()
                        return@collect
                    }
                    if (!userWantsPlayback) host.onVideoPause()
                    controller.setSpeed(speed.effectiveSpeed)
                    // 服务端续播：resume_position_ms > 0 时从该位置开始（Demo 恒为 0）
                    if (foreground && pendingResumeMs > 0L) {
                        controller.seekTo(pendingResumeMs)
                        pendingResumeMs = 0L
                    }
                    if (foreground && userWantsPlayback) resumeOwned()
                }
                else -> Unit
            }
        }
    }

    // ---------- 内核错误 → Direct 失败一次 HLS 回退 → Error（由 ViewModel 决策） ----------
    LaunchedEffect(controller, ready?.source?.mediaId, ready?.stage) {
        snapshotFlow { controller.snapshot.value.state }
            .distinctUntilChanged()
            .collect { playState ->
                if (playState == GSYPlayState.Error) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("MRPlayer", "kernelError mediaId=$loadedMediaId stage=$loadedStage")
                    }
                    // 带上播放器实际装载的媒体 id：ViewModel 会据此丢弃旧媒体的迟到错误
                    onPlaybackFailed(loadedMediaId)
                }
            }
    }

    // ---------- 播放进度上报：开始 / 暂停 / 每 15s / 退出 ----------
    LaunchedEffect(snapshot.isPlaying, loadedMediaId) {
        val mediaId = loadedMediaId
        if (mediaId.isEmpty()) return@LaunchedEffect
        if (!snapshot.isPlaying) {
            onReportLoadedProgress(mediaId, controller.snapshot.value.currentPosition, true)
            return@LaunchedEffect
        }
        while (true) {
            delay(PROGRESS_REPORT_INTERVAL_MS)
            val s = controller.snapshot.value
            if (!s.isPlaying) break
            onReportLoadedProgress(mediaId, s.currentPosition, false)
        }
    }

    // Sheet/拖动/手势期间暂停自动隐藏计时，控制锁始终拥有最高优先级。
    LaunchedEffect(locked) {
        controls.onLockChanged(locked)
    }
    LaunchedEffect(sheet, gestureActive, resumeAfterScrub, resumeAfterSeekGesture) {
        controls.updateInteractionHeld(sheet != null || gestureActive || resumeAfterScrub || resumeAfterSeekGesture)
    }
    LaunchedEffect(snapshot.isPlaying, userWantsPlayback) {
        controls.updatePlayback(userWantsPlayback && snapshot.isPlaying)
    }

    // 全屏方向由 MediaReview 自管（见 enterFullscreen/exitFullscreen）。关闭 GSY 自动旋转全屏，
    // 避免 OrientationUtils 在横屏时自行 startWindowFullscreen（Compose 下会迁出 View 导致黑屏）。
    LaunchedEffect(Unit) {
        controller.withHost { player ->
            player.setRotateViewAuto(false)
            player.setRotateWithSystem(false)
        }
    }

    // ---------- 生命周期 / 沉浸 / 恢复 ----------
    DisposableEffect(Unit) {
        val window = activity?.window
        savedBrightness = window?.attributes?.screenBrightness ?: -1f
        savedOrientation = activity?.requestedOrientation ?: -1
        window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            val host = ownedHost
            val manager = host?.getGSYVideoManager()
            val actualKernel = manager?.player?.mediaPlayer as? tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
            val ownsCurrent = host != null && manager?.listener() === host
            // Capture/report before releasing; a detached controller only has its final cached snapshot.
            val finalPosition = if (ownsCurrent && actualKernel?.dataSource == loadedEndpointUrl) manager!!.currentPosition
                else controller.snapshot.value.currentPosition
            if (loadedMediaId.isNotEmpty()) {
                onReportLoadedProgress(loadedMediaId, finalPosition, true)
            }
            if (ownsCurrent) {
                // Retained host survives controller.detachHost; guard forbids releasing a successor's kernel.
                detachRetiredKernelErrorListener(host, loadedEndpointUrl)
                host!!.onVideoPause()
                host.release()
                if (manager!!.listener() === host) manager.setListener(null)
                if (manager.lastListener() === host) manager.setLastListener(null)
            }
            ownedHost = null
            window?.let { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
                if (w.attributes.screenBrightness != savedBrightness) {
                    w.attributes = w.attributes.apply { screenBrightness = savedBrightness }
                }
            }
            activity?.requestedOrientation = savedOrientation
            // 画面比例为 GSY 全局静态，离开播放器恢复默认，避免污染其他页面
            GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_DEFAULT)
        }
    }

    // 全屏由 MediaReview 自管（Compose 方案）：仅旋转 Activity 方向，不调用 GSY 经典
    // startWindowFullscreen——后者会把 View 迁出 Compose 树迁到 decorView，在 Compose 下不旋转、
    // 产生双 host 且自绘三层 Overlay 不跟随（实测黑屏）。Manifest 已声明 configChanges，
    // 旋转只触发重组、不重建 Activity，controller / 播放进度 / 三层 Overlay 全部保留。
    fun enterFullscreen() {
        val a = activity ?: return
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        isFullscreen = true
        controls.onUserInteraction()
    }

    fun exitFullscreen() {
        val a = activity ?: return
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        isFullscreen = false
        controls.onUserInteraction()
    }

    fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    // ---------- Back：顶部返回与 Android Back 统一走 handlePlayerBack ----------
    fun handlePlayerBack() {
        when (BackNavigationLogic.resolveBackAction(sheet != null, isFullscreen)) {
            BackNavigationLogic.BackAction.DISMISS_SHEET -> sheet = null
            BackNavigationLogic.BackAction.EXIT_FULLSCREEN -> exitFullscreen()
            BackNavigationLogic.BackAction.EXIT_PLAYER -> onBack()
        }
    }

    BackHandler {
        handlePlayerBack()
    }

    // ---------- 交互动作 ----------
    fun showHint(deltaMs: Long, positionMs: Long) {
        seekHint = GsyNativeSeekHint(deltaMs, positionMs)
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(800L)
            seekHint = null
            tap.reset()
        }
    }

    fun restartHintDismiss() {
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(800L)
            brightnessPct = null
            volumePct = null
        }
    }

    fun togglePlayPause() {
        if (!foreground || locked || !sourceMatchesLoaded()) return
        val completed = ownedHost?.currentState == GSYVideoView.CURRENT_STATE_AUTO_COMPLETE
        if (userWantsPlayback && !completed) {
            userWantsPlayback = false
            pauseOwned()
        } else {
            userWantsPlayback = true
            resumeOwned(explicitReplay = completed)
        }
        controls.onUserInteraction()
    }
    // 快照用于绘制；手势开始必须捕获当下内核状态，不能使用轮询中的旧播放态。
    fun kernelIsPlaying(): Boolean {
        return currentOwnedSourceHost()?.getGSYVideoManager()?.isPlaying == true
    }

    fun seekRelative(deltaMs: Long) {
        if (!foreground || locked || currentOwnedSourceHost() == null) return
        controller.seekRelative(deltaMs)
        showHint(deltaMs, controller.snapshot.value.currentPosition)
        controls.onUserInteraction()
    }

    fun handleDoubleTap(xFraction: Float) {
        if (!foreground || locked) return
        val host = currentOwnedSourceHost() ?: return
        val manager = host.getGSYVideoManager()
        // 刚完成拖动时绘制快照可能仍是旧进度；双击以当前所属内核为准。
        val result = tap.tap(xFraction, SystemClock.uptimeMillis(), manager.currentPosition, manager.duration)
        controller.seekTo(result.targetMs)
        showHint(result.accumulatedDeltaMs, result.targetMs)
        controls.onUserInteraction()
    }

    fun applySpeed(change: SpeedGestureChange) {
        if (currentOwnedSourceHost() != null) controller.setSpeed(change.speed)
        speedMode = speed.mode
        if (change.haptic) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        change.feedback?.let { message ->
            speedFeedback = message
            speedHintJob?.cancel()
            speedHintJob = scope.launch { delay(800L); speedFeedback = null }
        }
    }

    fun endTempSpeed() { applySpeed(speed.cancel()) }

    fun toggleLock() {
        val next = !locked
        if (next) {
            val resumeBody = bodySeek.cancel()
            val resumeScrub = scrub.cancel()
            resumeAfterSeekGesture = false
            resumeAfterScrub = false
            if ((resumeBody || resumeScrub) && foreground && sourceMatchesLoaded()) resumeOwned()
            seekHint = null
            brightnessPct = null
            volumePct = null
            endTempSpeed()
        }
        controller.setLocked(next)
    }

    /** 上一条 / 下一条：只请求索引（ViewModel 异步解析该条播放源，禁止整队列解析）。 */
    fun goPrevious() {
        if (state.hasPrevious) onRequestIndex(state.context.currentIndex - 1)
    }

    fun goNext() {
        if (state.hasNext) onRequestIndex(state.context.currentIndex + 1)
    }

    // ---------- 播放源应用（Ready 变化时；URL 与 headers 必须成对切换） ----------
    // 放在交互函数声明之后：本 effect 会清理手势 / 临时倍速状态（Kotlin 局部函数须先声明后使用）。
    LaunchedEffect(ready?.source?.mediaId, ready?.stage, ready?.endpoint?.url, foreground) {
        if (!foreground) return@LaunchedEffect
        val current = ready ?: return@LaunchedEffect
        // 等 host attach + 完成首次布局：可取消（换源/退出即取消），无悬挂 postDelayed
        if (!awaitPlayerHostReady(controller)) return@LaunchedEffect
        if (!foreground) return@LaunchedEffect
        val latest = latestPlaybackState.ui as? V2PlaybackUiState.Ready ?: return@LaunchedEffect
        if (latest.source.mediaId != latestPlaybackState.currentMediaId || latest.source.mediaId != current.source.mediaId ||
            latest.stage != current.stage || latest.endpoint.url != current.endpoint.url) return@LaunchedEffect
        controller.withHost { ownedHost = it }
        val sameSource =
            current.source.mediaId == loadedMediaId && current.stage == loadedStage && current.endpoint.url == loadedEndpointUrl
        if (!sameSource) {
            if (current.source.mediaId != loadedMediaId) userWantsPlayback = true
            tap.reset()
            seekHint = null
            brightnessPct = null
            volumePct = null
            bodySeek.cancel()
            scrub.cancel()
            endTempSpeed()
            controller.setLocked(false)
            // Critical（Stage 8A 实测修正）：headers 必须写进 GSYVideoOptionBuilder。
            // 只调用 controller.setHeaders(...) 会在随后的 setUp 里被新源覆盖 —— 实测表现为
            // 直连请求完全没有 X-Emby-Token（Jellyfin 会 403/401），HLS 回退同样丢失。
            val option = GSYVideoOptionBuilder()
                .setUrl(current.endpoint.url)
                .setCacheWithPlay(false)
                .setVideoTitle(current.source.title)
                .setMapHeadData(current.endpoint.headers.ifEmpty { null })
                // Stage 8A.1 §33: 只加性能指标,不改播放器行为。
                // GSY 进度回调为 4 参: (curProgress, duration, currPosition, bufferedPosition)。
                // 播放位置首次 >0 即"首帧已呈现"; duration 不参与判定以免准备完成即误报。
                .setGSYVideoProgressListener { curProgress, _duration, currPosition, _buffer ->
                    if (curProgress > 0 || currPosition > 0) {
                        V2Perf.player()?.markOnce("first_frame", "first_frame")
                    }
                }
            detachRetiredKernelErrorListener(ownedHost, loadedEndpointUrl)
            pauseOwned()
            sourceStarted = false
            controller.setUp(option, false)
            loadedMediaId = current.source.mediaId
            loadedEndpointUrl = current.endpoint.url
            loadedStage = current.stage
            pendingResumeMs = if (current.stage == V2PlaybackStage.DIRECT) {
                current.source.resumePositionMs
            } else {
                0L
            }
            if (foreground && userWantsPlayback) onReportLoadedProgress(loadedMediaId, 0L, false)
            resumeOwned()
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "MRPlayer",
                    "applySource[stage=${current.stage}] mediaId=${current.source.mediaId} " +
                        "resumeMs=${current.source.resumePositionMs} headers=${current.endpoint.headers.keys.sorted()}",
                )
            }
        } else {
            resumeOwned()
        }
        if (currentOwnedSourceHost() != null) controller.setSpeed(speed.effectiveSpeed)
        controls.onUserInteraction()
    }

    // 旧视频在新条目解析阶段即停声，Poster保留到新内核持续前进。
    LaunchedEffect(sourceIsCurrent, state.currentMediaId) {
        // setUp可能先更新loaded字段；执行时核对最新状态，不能用旧composition的false暂停新内核。
        val expected = latestPlaybackState.ui as? V2PlaybackUiState.Ready
        val loadedMatches = expected != null && expected.source.mediaId == latestPlaybackState.currentMediaId &&
            expected.source.mediaId == loadedMediaId && expected.stage == loadedStage && expected.endpoint.url == loadedEndpointUrl
        if (loadedMediaId.isNotEmpty() && !loadedMatches) {
            // 选择已变化或正在解析：取消旧手势，禁止旧up/cancel重新播放旧源。
            bodySeek.cancel()
            scrub.cancel()
            resumeAfterSeekGesture = false
            resumeAfterScrub = false
            seekHint = null
            tap.reset()
            endTempSpeed()
            pauseOwned()
        }
    }
    LaunchedEffect(loadedMediaId, loadedStage, loadedEndpointUrl, ready?.stage, ready?.endpoint?.url, foreground) {
        val observedMediaId = loadedMediaId
        val observedStage = loadedStage
        val observedUrl = loadedEndpointUrl
        var firstPositive: Long? = null
        var stableSent = false
        var observedPlayer: tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer? = null
        snapshotFlow { controller.snapshot.value }
            .collect {
                val expected = latestPlaybackState.ui as? V2PlaybackUiState.Ready
                val identityMatches = foreground && expected != null && observedMediaId.isNotEmpty() &&
                    expected.source.mediaId == latestPlaybackState.currentMediaId &&
                    expected.source.mediaId == observedMediaId && expected.stage == observedStage &&
                    expected.endpoint.url == observedUrl && loadedMediaId == observedMediaId &&
                    loadedStage == observedStage && loadedEndpointUrl == observedUrl
                var player: tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer? = null
                var playing = false
                var position = 0L
                if (identityMatches) controller.withHost { host ->
                    if (currentOwnedSourceHost() !== host) return@withHost
                    val manager = host.getGSYVideoManager()
                    player = (manager.player?.mediaPlayer as? tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer)
                        ?.takeIf { it.dataSource == observedUrl }
                    if (player != null) {
                        playing = manager.isPlaying
                        position = manager.currentPosition
                    }
                }
                if (player !== observedPlayer) {
                    observedPlayer = player
                    firstPositive = null
                    stableSent = false
                }
                if (identityMatches && player != null && playing && position > 0L) {
                    val baseline = firstPositive
                    if (baseline == null || position < baseline) firstPositive = position
                    else {
                        if (position > baseline + 100L) playbackAdvanced = true
                        if (!stableSent && position >= baseline + 500L) {
                            stableSent = true
                            onPlaybackStable(observedMediaId)
                        }
                    }
                } else firstPositive = null
            }
    }
    // 中央覆盖层：加载中 / 解析失败由 ViewModel 状态决定，其余沿用内核状态
    val centerOverlay = when {
        failure != null -> PlaybackUiMapper.CenterOverlay.ERROR
        state.ui is V2PlaybackUiState.Loading -> PlaybackUiMapper.CenterOverlay.LOADING
        else -> PlaybackUiMapper.overlayFor(snapshot.state)
    }

    // Completed 时内核 position 归零（GSY getCurrentPositionWhenPlaying 仅 Playing 返回），
    // UI 进度保持在结尾，避免 thumb 跳回起点、时间显示 00:00。
    val resolvedPosition = when (snapshot.state) {
        GSYPlayState.Completed -> snapshot.duration
        else -> snapshot.currentPosition
    }

    val displayPosition = when {
        scrub.active -> scrubPreviewMs
        bodySeek.active -> bodySeekPreviewMs
        else -> resolvedPosition
    }

    // 临时手势暂停不改变用户意图；迟到快照也不能隐藏用户明确暂停后的播放入口。
    val centerIsPlaying = userWantsPlayback

    // 一次pointer sequence只由一类手势接管，Full/Review共用该方向及速度实现。
    val gestureModifier = rememberPlayerBodyGestures(
        enabled = foreground && !locked && sheet == null && sourceIsCurrent,
        speed = speed,
        callbacks = PlayerBodyGestureCallbacks(
            isPlaying = ::kernelIsPlaying,
            onTap = { controls.toggleVisibility() },
            onDoubleTap = { handleDoubleTap(it) },
            onSpeedChange = { applySpeed(it) },
            onSeekStart = {
                currentOwnedSourceHost()?.let { host ->
                    val manager = host.getGSYVideoManager()
                    bodySeekStartMs = manager.currentPosition
                    bodySeekDurationMs = manager.duration
                    resumeAfterSeekGesture = bodySeek.begin(bodySeekStartMs, bodySeekDurationMs, manager.isPlaying)
                    bodySeekPreviewMs = bodySeek.previewMs
                    if (resumeAfterSeekGesture) pauseOwned()
                }
            },
            onSeekMove = { delta ->
                val target = PlayerSeekMapping.horizontalTarget(bodySeekStartMs, delta, bodySeekDurationMs)
                bodySeek.preview(target)
                bodySeekPreviewMs = bodySeek.previewMs
                seekHint = GsyNativeSeekHint(bodySeekPreviewMs - bodySeekStartMs, bodySeekPreviewMs, bodyGesture = true)
            },
            onSeekEnd = {
                (if (foreground && currentOwnedSourceHost() != null) bodySeek.commit() else { bodySeek.cancel(); null })?.let { result ->
                    controller.seekTo(result.targetMs)
                    // 默认resume会seek回pause缓存的旧位置，覆盖刚提交的seek。
                    if (result.resumePlaying) resumeOwned()
                }
                resumeAfterSeekGesture = false
                seekHint = null
            },
            onSeekCancel = {
                if (bodySeek.cancel() && foreground && sourceMatchesLoaded()) resumeOwned()
                resumeAfterSeekGesture = false
                seekHint = null
            },
            onVerticalStart = {
                val windowBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                verticalStartBrightness = if (windowBrightness >= 0f) windowBrightness else
                    Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                verticalStartVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            },
            onVerticalMove = { _, total, side ->
                if (side < .5f) {
                    val value = (verticalStartBrightness - total).coerceIn(.01f, 1f)
                    activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value } }
                    brightnessPct = (value * 100).roundToInt()
                    volumePct = null
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val value = (verticalStartVolume - total * max).roundToInt().coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                    volumePct = if (max > 0) value * 100 / max else 0
                    brightnessPct = null
                }
            },
            onVerticalEnd = { restartHintDismiss() },
            onVerticalCancel = { brightnessPct = null; volumePct = null },
            onActive = { gestureActive = it },
        ),
    )

    // Debug-only：三层边界可视化，默认关闭；release 构建恒不生效
    fun Modifier.debugBounds(layer: PlayerLayoutDebug.Layer): Modifier =
        if (BuildConfig.DEBUG && PlayerLayoutDebug.ENABLED) {
            PlayerLayoutDebug.boundsModifier(this, layer)
        } else {
            this
        }

    // ---------- 布局：三个独立 Overlay，禁止 Column 堆叠 ----------
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MediaImmersiveBackground),
    ) {
        // 视频画面（GSY Native Surface）
        GSYPlayerSurface(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
        )

        if (posterAlpha > 0f && state.current?.posterUrl != null) {
            AsyncImage(
                model = state.current?.posterUrl,
                contentDescription = "视频封面",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha },
            )
        }

        // 手势层
        Box(modifier = Modifier.fillMaxSize().then(gestureModifier).testTag("player_gesture_surface"))

        // 控制层（播放 3s 无操作自动隐藏 / 暂停保持 / 锁定隐藏）
        if (controlsVisible && !locked) {
            // TOP：返回 / 标题 / 更多
            GsyNativeTopBar(
                title = state.current?.title ?: "",
                subtitle = "",
                onBack = { handlePlayerBack() },
                onMore = { sheet = PlayerSheet.MORE },
                pendingDeleteEnabled = pendingDeleteEnabled,
                isPendingDelete = isPendingDelete,
                pendingDeleteLabel = pendingDeleteLabel,
                onTogglePendingDelete = { controls.onUserInteraction(); onTogglePendingDelete() },
                onRetryPendingDelete = onRetryPendingDelete,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .debugBounds(PlayerLayoutDebug.Layer.TOP),
            )

            // CENTER：暂停时单一播放入口，正常播放不遮挡画面
            // 缓冲 / 完成 / 错误时中央交给 GsyNativeIndicators 独占，避免重叠
            if (centerOverlay == PlaybackUiMapper.CenterOverlay.NONE) {
                GsyNativeCenterControls(
                    isPlaying = centerIsPlaying,
                    onPlayPause = { togglePlayPause() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .debugBounds(PlayerLayoutDebug.Layer.CENTER),
                )
            }

            // BOTTOM：进度 / 时间 / 倍速 / 比例 / 锁定 / 全屏
            // 锁定时整块控制层已隐藏，locked 固定传 false（锁图标由外层独占）
            GsyNativeBottomBar(
                positionMs = displayPosition,
                durationMs = snapshot.duration,
                bufferPercent = snapshot.bufferPercent,
                speedLabel = formatSpeedLabel(snapshot.speed),
                scaleLabel = scale.mode.label,
                locked = false,
                isFullscreen = isFullscreen,
                hasPrevious = state.hasPrevious,
                hasNext = state.hasNext,
                seekEnabled = foreground && sourceIsCurrent,
                onPrevious = { goPrevious() },
                onNext = { goNext() },
                onScrubStart = {
                    val current = controller.snapshot.value
                    resumeAfterScrub = scrub.begin(current.currentPosition, current.duration, kernelIsPlaying())
                    scrubPreviewMs = scrub.previewMs
                    if (resumeAfterScrub) pauseOwned()
                    controls.onUserInteraction()
                },
                onScrub = { value ->
                    scrub.preview(value)
                    scrubPreviewMs = scrub.previewMs
                },
                onScrubEnd = {
                    (if (foreground && currentOwnedSourceHost() != null) scrub.commit() else { scrub.cancel(); null })?.let { result ->
                        controller.seekTo(result.targetMs)
                        if (result.resumePlaying) resumeOwned()
                    }
                    resumeAfterScrub = false
                    controls.onUserInteraction()
                },
                onScrubCancel = {
                    if (scrub.cancel() && foreground && sourceMatchesLoaded()) resumeOwned()
                    resumeAfterScrub = false
                    controls.onUserInteraction()
                },
                onOpenSpeed = {
                    sheet = PlayerSheet.SPEED
                    controls.onUserInteraction()
                },
                onOpenScale = {
                    sheet = PlayerSheet.SCALE
                    controls.onUserInteraction()
                },
                onToggleLock = { toggleLock() },
                onToggleFullscreen = { toggleFullscreen() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .debugBounds(PlayerLayoutDebug.Layer.BOTTOM),
            )
        }

        // 锁定态：只保留锁图标（点击解锁）
        if (locked) {
            IconButton(
                onClick = { toggleLock() },
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    Icons.Default.Lock,
                    "解锁",
                    tint = MediaTextPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // 指示器（Loading / Seek 提示 / 音量亮度 / 临时 2x / Completed / Error）
        if (!locked) GsyNativeIndicators(
            overlay = centerOverlay,
            seekHint = seekHint,
            seekDurationMs = snapshot.duration,
            brightnessPct = brightnessPct,
            volumePct = volumePct,
            tempSpeedActive = speedMode == SpeedGestureMode.Temp2x,
            speedFeedback = speedFeedback,
            hasNext = state.hasNext,
            errorMessage = failure?.message,
            onRestart = {
                if (foreground && currentOwnedSourceHost() != null) {
                    userWantsPlayback = true
                    resumeOwned(explicitReplay = true)
                }
            },
            onNext = { goNext() },
            onRetry = { onRetry() },
            onBack = { handlePlayerBack() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ---------- 底部面板 ----------
    when (sheet) {
        PlayerSheet.MORE -> GsyNativeMoreSheet(
            isPlaying = userWantsPlayback && ownedHost?.currentState != GSYVideoView.CURRENT_STATE_AUTO_COMPLETE,
            onPlayPause = { togglePlayPause(); sheet = null },
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
            onPrevious = { goPrevious(); sheet = null },
            onNext = { goNext(); sheet = null },
            onSpeed = { sheet = PlayerSheet.SPEED },
            onScale = { sheet = PlayerSheet.SCALE },
            onInfo = { sheet = PlayerSheet.INFO },
            onAudio = if (sourceIsCurrent && audioTrackSnapshot?.menu?.canSelect == true) {
                { sheet = PlayerSheet.AUDIO }
            } else null,
            onDismiss = { sheet = null },
        )
        PlayerSheet.SPEED -> GsyNativeSpeedSheet(
            current = speed.formalSpeed,
            onSelect = { s ->
                applySpeed(speed.formalSpeedChanged(s))
                sheet = null
                controls.onUserInteraction()
            },
            onDismiss = { sheet = null },
        )
        PlayerSheet.SCALE -> GsyNativeScaleSheet(
            current = scale.mode,
            onSelect = { mode ->
                scale.set(mode)
                controller.withHost { player ->
                    GSYVideoType.setShowType(mode.gsyShowType)
                    player.requestLayout()
                }
                sheet = null
                controls.onUserInteraction()
            },
            onDismiss = { sheet = null },
        )
        PlayerSheet.AUDIO -> audioTrackSnapshot?.takeIf { sourceIsCurrent && it.menu.canSelect }?.let { tracks ->
            com.mediareview.app.feature.v2.player.native.ui.GsyAudioTrackSheet(
                menu = tracks.menu,
                onSelect = { choice ->
                    val current = latestAudioSource
                    if (current.first && audioTracks.select(tracks, choice, current.second)) {
                        // 参数异步生效；勾选只来自内核回读，不乐观标记。
                        audioTrackSnapshot = audioTracks.read(current.second)
                        controls.onUserInteraction()
                    }
                },
                onDismiss = { sheet = null },
            )
        }
        PlayerSheet.INFO -> GsyNativeVideoInfoSheet(
            title = state.current?.title ?: "",
            durationMs = snapshot.duration,
            positionMs = displayPosition,
            speedLabel = formatSpeedLabel(snapshot.speed),
            fileName = ready?.endpoint?.url?.substringAfterLast('/') ?: "",
            mediaId = state.current?.mediaId ?: "",
            resolution = if (snapshot.videoWidth > 0) "${snapshot.videoWidth} × ${snapshot.videoHeight}" else "未知",
            onDismiss = { sheet = null },
        )
        null -> Unit
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
