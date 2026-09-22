package com.mediareview.app.feature.v2.player.native

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mediareview.app.BuildConfig
import com.mediareview.app.feature.v2.player.native.state.BackNavigationLogic
import com.mediareview.app.feature.v2.player.native.state.ControlsVisibilityState
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackUiMapper
import com.mediareview.app.feature.v2.player.native.state.SeekGesturePreview
import com.mediareview.app.feature.v2.player.native.state.SeekScrubState
import com.mediareview.app.feature.v2.player.native.state.SpeedState
import com.mediareview.app.feature.v2.player.native.state.TapActionState
import com.mediareview.app.feature.v2.player.native.state.VideoScaleState
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeBottomBar
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeCenterControls
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeIndicators
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeMoreSheet
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeScaleSheet
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeSeekHint
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeSpeedSheet
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeStatusSheet
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeTopBar
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeVideoInfoSheet
import com.mediareview.app.feature.v2.player.native.ui.PlayerLayoutDebug
import com.mediareview.app.feature.v2.player.native.ui.formatSpeedLabel
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.shuyu.gsyvideoplayer.compose.native_.GSYGestureType
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayState
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerEvent
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerSurface
import com.shuyu.gsyvideoplayer.compose.native_.gsyGestureControl
import com.shuyu.gsyvideoplayer.compose.native_.rememberGSYPlayerController
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 底部面板类型。 */
private enum class PlayerSheet { MORE, SPEED, SCALE, ROTATION, SUBTITLE, AUDIO, INFO }

/**
 * GSY Native Compose 正式播放器 V1。
 *
 * 架构：MediaReview Player UI → GSYPlayerController → GSYPlayerSurface → GSY/Exo2/Media3。
 * 播放 / 暂停 / Seek / 状态 / 全屏 / 音量亮度手势等全部由 GSY 提供；
 * MediaReview 只负责控制层 UI 与交互组织。
 *
 * - 播放 / 锁定 / 倍速状态一律以 controller.snapshot 为唯一真实来源（不维护第二份状态）；
 * - 单击显隐控制层（播放 3s 自动隐藏、暂停保持）；
 * - 双击左/右 ±10s（连续累计）；长按临时 2x；
 * - 横向拖拽 Seek（灵敏度自适应，松手 controller.seekTo 提交）；
 * - 全屏由 MediaReview 自管（仅旋转 Activity 方向，Compose 三层 Overlay 天然跟随，不走 GSY
 *   经典 View 迁移）；Back 统一走 handlePlayerBack；
 * - 上一条 / 下一条复用同一 controller.setUp 正式换源（URL / Title / Headers 全更新）。
 */
@Composable
fun GsyNativePlayerScreen(
    playbackContext: PlaybackContext,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current
    val activity = remember { appContext.findActivity() }
    val scope = rememberCoroutineScope()

    // autoPlay=false：库内部在 AndroidView factory 中 attachHost 后立即 post startPlayLogic，
    // 冷启动 / JIT 繁忙时宿主可能尚未完成首次布局。下方 LaunchedEffect 等 host attach 且
    // 完成首次布局（width/height>0）后再自管 play，保证启动时渲染容器尺寸确定。
    val controller = rememberGSYPlayerController(
        url = playbackContext.current?.url,
        cacheWithPlay = false,
        title = playbackContext.current?.title ?: "",
        autoPlay = false,
        autoPauseResume = true,
    )

    // ---------- MediaReview 交互状态 ----------
    var contextState by remember { mutableStateOf(playbackContext) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var seekHint by remember { mutableStateOf<GsyNativeSeekHint?>(null) }
    var brightnessPct by remember { mutableStateOf<Int?>(null) }
    var volumePct by remember { mutableStateOf<Int?>(null) }
    var hintJob by remember { mutableStateOf<Job?>(null) }
    var savedBrightness by remember { mutableStateOf(-1f) }
    var savedOrientation by remember { mutableStateOf(-1) }

    // 锁定真值不存放于此：唯一来源是 controller.snapshot.isLocked
    val controls = remember {
        ControlsVisibilityState(
            scope = scope,
            onVisibilityChange = { visible -> controlsVisible = visible },
        )
    }
    val scrub = remember { SeekScrubState() }
    val seekPreview = remember { SeekGesturePreview() }
    // 拖动（进度条 / 横向手势）开始前是否在播放：拖动期暂停以冻结画面与声音，
    // 松手 seek 后据此恢复播放（成熟播放器标准手感）。
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var resumeAfterSeekGesture by remember { mutableStateOf(false) }
    val speed = remember { SpeedState() }
    val scale = remember { VideoScaleState() }
    val tap = remember { TapActionState() }

    // snapshot 为唯一播放状态来源
    val snapshot = controller.snapshot.value
    val current = contextState.current
    val locked = snapshot.isLocked

    // ---------- 事件订阅 + 自管播放启动 ----------
    LaunchedEffect(controller) {
        // 等 host attach 到窗口且完成首次布局后再 startPlayLogic，规避冷启动首帧黑屏
        controller.withHost { host ->
            var attempts = 0
            fun tryStartPlayback() {
                val layoutReady = host.isAttachedToWindow && host.width > 0 && host.height > 0
                if (layoutReady) {
                    host.postDelayed({ controller.play() }, 120L)
                } else if (attempts++ < 60) {
                    host.postDelayed({ tryStartPlayback() }, 50L)
                } else {
                    controller.play()
                }
            }
            host.post { tryStartPlayback() }
        }

        controller.events.collect { event ->
            when (event) {
                // 全屏由 MediaReview 自管（enterFullscreen/exitFullscreen），不依赖 GSY 经典
                // View 迁移事件，故此处不再用 EnterFull/QuitFull 驱动 isFullscreen。
                // 每次 Prepared（含切源后内核重置倍速）恢复用户期望倍速
                is GSYPlayerEvent.Prepared -> controller.setSpeed(speed.expectedSpeed)
                else -> Unit
            }
        }
    }

    // 播放状态驱动控制层自动隐藏
    LaunchedEffect(snapshot.isPlaying) {
        controls.updatePlayback(snapshot.isPlaying)
    }

    // 锁定态唯一来源 controller.snapshot.isLocked，同步给显隐逻辑
    LaunchedEffect(locked) {
        controls.onLockChanged(locked)
    }

    // 头信息（Demo 为空；未来 Jellyfin 传 X-Emby-Token）
    LaunchedEffect(current?.mediaId) {
        controller.setHeaders(current?.headers?.ifEmpty { null })
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
            window?.let { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
                if (savedBrightness >= 0f && w.attributes.screenBrightness != savedBrightness) {
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
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        if (locked) return
        controller.togglePlayPause()
        controls.onUserInteraction()
    }

    fun seekRelative(deltaMs: Long) {
        if (locked) return
        controller.seekRelative(deltaMs)
        showHint(deltaMs, controller.snapshot.value.currentPosition)
        controls.onUserInteraction()
    }

    fun handleDoubleTap(xFraction: Float) {
        if (locked) return
        val currentPos = controller.snapshot.value.currentPosition
        val action = tap.onDoubleTap(xFraction, SystemClock.uptimeMillis(), currentPos)
        when (action) {
            TapActionState.DoubleTapAction.SEEK_BACK -> {
                // 目标 = 连续序列起始位置 + 累计增量；clamp 到 [0, duration]
                val target = tap.targetPositionMs().coerceAtLeast(0L)
                controller.seekTo(target)
                showHint(tap.accumulatedDeltaMs, target)
            }
            TapActionState.DoubleTapAction.SEEK_FORWARD -> {
                val maxTarget = controller.snapshot.value.duration
                val target = tap.targetPositionMs().coerceAtMost(maxTarget.coerceAtLeast(0L))
                controller.seekTo(target)
                showHint(tap.accumulatedDeltaMs, target)
            }
            TapActionState.DoubleTapAction.TOGGLE_PLAY_PAUSE -> {
                tap.reset()
                controller.togglePlayPause()
            }
        }
        controls.onUserInteraction()
    }

    fun startTempSpeed() {
        if (locked) return
        speed.startTemp(snapshot.speed, snapshot.isPlaying)
        if (speed.tempActive) controller.setSpeed(SpeedState.TEMP_SPEED)
    }

    fun endTempSpeed() {
        if (speed.tempActive) {
            controller.setSpeed(speed.endTemp())
        }
    }

    fun toggleLock() {
        val next = !locked
        if (next) {
            seekPreview.onCancel()
            scrub.cancel()
            seekHint = null
            brightnessPct = null
            volumePct = null
            endTempSpeed()
        }
        controller.setLocked(next)
    }

    /**
     * 正式换源：同一 controller 重新 setUp（URL / Title / Headers 全更新，autoPlay=true），
     * 而不是只改 MediaReview 自己的 Context。mediaId 未变化时不动作（边界安全）。
     */
    fun switchTo(target: PlaybackContext) {
        val req = target.current ?: return
        if (req.mediaId == current?.mediaId) return
        tap.reset()
        seekHint = null
        brightnessPct = null
        volumePct = null
        seekPreview.onCancel()
        scrub.cancel()
        endTempSpeed()
        controller.setLocked(false)
        controller.setHeaders(req.headers.ifEmpty { null })
        controller.setUp(req.url, false, req.title, true)
        contextState = target
        controls.onUserInteraction()
    }

    fun goPrevious() {
        switchTo(contextState.previous())
    }

    fun goNext() {
        switchTo(contextState.next())
    }

    // 中央覆盖层类型：LOADING / COMPLETED / ERROR 时中央由 GsyNativeIndicators 独占，
    // 三按钮（-10 / 播放暂停 / +10）仅在普通播放 / 暂停态显示，避免与重播层重叠。
    val centerOverlay = PlaybackUiMapper.overlayFor(snapshot.state)

    // Completed 时内核 position 归零（GSY getCurrentPositionWhenPlaying 仅 Playing 返回），
    // UI 进度保持在结尾，避免 thumb 跳回起点、时间显示 00:00。
    val resolvedPosition = when (snapshot.state) {
        GSYPlayState.Completed -> snapshot.duration
        else -> snapshot.currentPosition
    }

    val displayPosition = when {
        scrub.isScrubbing -> scrub.previewMs
        seekPreview.isActive -> seekPreview.targetMs
        else -> resolvedPosition
    }

    // 拖动期内核虽暂停，中央按钮仍维持拖动前的播放/暂停图标，避免图标抖动。
    val centerIsPlaying = PlaybackUiMapper.isPlaying(snapshot.state) || resumeAfterScrub || resumeAfterSeekGesture

    // ---------- 手势层（GSY 纵向 + MediaReview 横向/点击） ----------
    val gestureModifier = Modifier
        .gsyGestureControl(
            controller = controller,
            enableSeek = false,
            enableVolume = true,
            enableBrightness = true,
            onGestureUpdate = { update ->
                // 任何手势开始都结束临时 2x（长按后直接拖动的情况）
                if (update.type != GSYGestureType.None) endTempSpeed()
                when (update.type) {
                    GSYGestureType.Brightness -> {
                        brightnessPct = (update.progress * 100).roundToInt().coerceIn(0, 100)
                        volumePct = null
                        restartHintDismiss()
                    }
                    GSYGestureType.Volume -> {
                        volumePct = (update.progress * 100).roundToInt().coerceIn(0, 100)
                        brightnessPct = null
                        restartHintDismiss()
                    }
                    else -> Unit
                }
            },
            onGestureCommit = { },
        )
        .pointerInput(controller) {
            var accX = 0f
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    if (!controller.snapshot.value.isLocked && controller.snapshot.value.duration > 0L) {
                        endTempSpeed()
                        seekPreview.onStart(controller.snapshot.value.currentPosition, offset.x, size.width.toFloat())
                        accX = 0f
                        resumeAfterSeekGesture = controller.snapshot.value.isPlaying
                        if (resumeAfterSeekGesture) controller.pause()
                    }
                },
                onDragEnd = {
                    if (seekPreview.isActive) {
                        val seekTarget = seekPreview.onEnd()
                        if (seekTarget != null) controller.seekTo(seekTarget)
                        showHint(seekPreview.deltaMs, seekPreview.targetMs)
                        if (resumeAfterSeekGesture) controller.play()
                        resumeAfterSeekGesture = false
                        controls.onUserInteraction()
                    }
                },
                onDragCancel = {
                    if (seekPreview.isActive) {
                        seekPreview.onCancel()
                        seekHint = null
                        if (resumeAfterSeekGesture) controller.play()
                        resumeAfterSeekGesture = false
                    }
                },
                onHorizontalDrag = { change, dragAmount ->
                    if (seekPreview.isActive) {
                        change.consume()
                        accX += dragAmount
                        seekPreview.onDrag(seekPreview.startX + accX, controller.snapshot.value.duration)
                        if (seekPreview.deltaMs != 0L) {
                            seekHint = GsyNativeSeekHint(seekPreview.deltaMs, seekPreview.targetMs)
                        }
                    }
                },
            )
        }
        .pointerInput(controller) {
            detectTapGestures(
                onPress = { _ ->
                    tryAwaitRelease()
                    endTempSpeed()
                },
                onTap = {
                    if (!controller.snapshot.value.isLocked) {
                        controls.toggleVisibility()
                    }
                },
                onDoubleTap = { offset ->
                    if (!controller.snapshot.value.isLocked) {
                        handleDoubleTap(offset.x / size.width.coerceAtLeast(1).toFloat())
                    }
                },
                onLongPress = {
                    if (!controller.snapshot.value.isLocked) {
                        startTempSpeed()
                    }
                },
            )
        }

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

        // 手势层
        Box(modifier = Modifier.fillMaxSize().then(gestureModifier))

        // 控制层（播放 3s 无操作自动隐藏 / 暂停保持 / 锁定隐藏）
        if (controlsVisible && !locked) {
            // TOP：返回 / 标题 / 更多
            GsyNativeTopBar(
                title = current?.title ?: "",
                subtitle = "",
                onBack = { handlePlayerBack() },
                onMore = { sheet = PlayerSheet.MORE },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .debugBounds(PlayerLayoutDebug.Layer.TOP),
            )

            // CENTER：上一条 / 快退 / 播放暂停 / 快进 / 下一条（独立居中，不受 BottomBar 尺寸影响）
            // 缓冲 / 完成 / 错误时中央交给 GsyNativeIndicators 独占，避免重叠
            if (centerOverlay == PlaybackUiMapper.CenterOverlay.NONE) {
                GsyNativeCenterControls(
                    isPlaying = centerIsPlaying,
                    hasPrevious = contextState.hasPrevious,
                    hasNext = contextState.hasNext,
                    onPrevious = { goPrevious() },
                    onRewind = { seekRelative(-10_000L) },
                    onPlayPause = { togglePlayPause() },
                    onForward = { seekRelative(10_000L) },
                    onNext = { goNext() },
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
                onScrub = { value ->
                    if (!scrub.isScrubbing) {
                        scrub.begin(snapshot.currentPosition)
                        resumeAfterScrub = snapshot.isPlaying
                        if (resumeAfterScrub) controller.pause()
                        controls.onUserInteraction()
                    }
                    scrub.update(value.toLong())
                },
                onScrubEnd = {
                    val scrubTarget = scrub.commit()
                    if (scrubTarget != null) controller.seekTo(scrubTarget)
                    if (resumeAfterScrub) controller.play()
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
        GsyNativeIndicators(
            overlay = centerOverlay,
            seekHint = seekHint,
            seekDurationMs = snapshot.duration,
            brightnessPct = brightnessPct,
            volumePct = volumePct,
            tempSpeedActive = speed.tempActive,
            hasNext = contextState.hasNext,
            onRestart = { controller.retry() },
            onNext = { goNext() },
            onRetry = { controller.retry() },
            onBack = { handlePlayerBack() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ---------- 底部面板 ----------
    when (sheet) {
        PlayerSheet.MORE -> GsyNativeMoreSheet(
            hasPrevious = contextState.hasPrevious,
            hasNext = contextState.hasNext,
            onPrevious = { goPrevious(); sheet = null },
            onNext = { goNext(); sheet = null },
            onSpeed = { sheet = PlayerSheet.SPEED },
            onScale = { sheet = PlayerSheet.SCALE },
            onRotation = { sheet = PlayerSheet.ROTATION },
            onSubtitle = { sheet = PlayerSheet.SUBTITLE },
            onAudio = { sheet = PlayerSheet.AUDIO },
            onInfo = { sheet = PlayerSheet.INFO },
            onDismiss = { sheet = null },
        )
        PlayerSheet.SPEED -> GsyNativeSpeedSheet(
            current = snapshot.speed,
            onSelect = { s ->
                speed.setExpected(s)
                controller.setSpeed(s)
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
        PlayerSheet.ROTATION -> GsyNativeStatusSheet(
            title = "旋转画面",
            statusLine = "0°/90°/180°/270°：GSY 13.2.1 未提供公开视频旋转 API，本轮 BACKEND DEFERRED",
            onDismiss = { sheet = null },
        )
        PlayerSheet.SUBTITLE -> GsyNativeStatusSheet(
            title = "字幕",
            statusLine = "暂无可用字幕（外挂 SRT 能力架构已预留，BACKEND DEFERRED）",
            onDismiss = { sheet = null },
        )
        PlayerSheet.AUDIO -> GsyNativeStatusSheet(
            title = "音轨",
            statusLine = "默认（本地 Demo 仅一个音轨，多音轨后端预留）",
            onDismiss = { sheet = null },
        )
        PlayerSheet.INFO -> GsyNativeVideoInfoSheet(
            title = current?.title ?: "",
            durationMs = snapshot.duration,
            positionMs = displayPosition,
            speedLabel = formatSpeedLabel(snapshot.speed),
            fileName = current?.url?.substringAfterLast('/') ?: "",
            mediaId = current?.mediaId ?: "",
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
