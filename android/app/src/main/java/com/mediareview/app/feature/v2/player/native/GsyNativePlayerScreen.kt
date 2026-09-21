package com.mediareview.app.feature.v2.player.native

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import com.mediareview.app.feature.v2.player.gsy.demoRawVideoUri
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
import com.mediareview.app.feature.v2.player.native.ui.formatSpeedLabel
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.shuyu.gsyvideoplayer.compose.native_.GSYGestureType
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
 * - 播放状态一律来自 controller.snapshot（不维护第二套 isPlaying）；
 * - 单击显隐控制层（播放 3s 自动隐藏、暂停保持）；
 * - 双击左/右 ±10s（连续累计）；长按临时 2x；
 * - 横向拖拽 Seek（灵敏度自适应，松手 controller.seekTo 提交）；
 * - 全屏用 controller.enterFullscreen / exitFullscreen；Back 优先退出全屏；
 * - 上一条 / 下一条复用同一 controller.setUp 切换源。
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

    val controller = rememberGSYPlayerController(
        url = playbackContext.current?.url,
        cacheWithPlay = false,
        title = playbackContext.current?.title ?: "",
        autoPlay = true,
        autoPauseResume = true,
    )

    // ---------- MediaReview 交互状态 ----------
    var contextState by remember { mutableStateOf(playbackContext) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var seekHint by remember { mutableStateOf<GsyNativeSeekHint?>(null) }
    var brightnessPct by remember { mutableStateOf<Int?>(null) }
    var volumePct by remember { mutableStateOf<Int?>(null) }
    var hintJob by remember { mutableStateOf<Job?>(null) }
    var savedBrightness by remember { mutableStateOf(-1f) }
    var savedOrientation by remember { mutableStateOf(-1) }

    val controls = remember {
        ControlsVisibilityState(
            scope = scope,
            onVisibilityChange = { visible -> controlsVisible = visible },
            onLockChange = { locked -> controlsLocked = locked },
        )
    }
    val scrub = remember { SeekScrubState() }
    val seekPreview = remember { SeekGesturePreview() }
    val speed = remember { SpeedState() }
    val scale = remember { VideoScaleState() }
    val tap = remember { TapActionState() }

    // snapshot 为唯一播放状态来源
    val snapshot = controller.snapshot.value
    val current = contextState.current

    // ---------- 事件订阅（全屏边沿等） ----------
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                is GSYPlayerEvent.EnterFull -> isFullscreen = true
                is GSYPlayerEvent.QuitFull -> isFullscreen = false
                else -> Unit
            }
        }
    }

    // 播放状态驱动控制层自动隐藏
    LaunchedEffect(snapshot.isPlaying) {
        controls.updatePlayback(snapshot.isPlaying)
    }

    // 头信息（Demo 为空；未来 Jellyfin 传 X-Emby-Token）
    LaunchedEffect(current?.mediaId) {
        controller.setHeaders(current?.headers?.ifEmpty { null })
    }

    // 全屏自动旋转交给 GSY OrientationUtils（rotateViewAuto），内嵌不随系统转
    LaunchedEffect(Unit) {
        controller.withHost { player ->
            player.setRotateViewAuto(true)
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

    // ---------- Back：全屏优先退出全屏 ----------
    BackHandler {
        when (BackNavigationLogic.resolveBackAction(isFullscreen)) {
            BackNavigationLogic.BackAction.EXIT_FULLSCREEN -> activity?.let { controller.exitFullscreen(it) }
            BackNavigationLogic.BackAction.EXIT_PLAYER -> onBack()
        }
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
        if (controlsLocked) return
        controller.togglePlayPause()
        controls.onUserInteraction()
    }

    fun seekRelative(deltaMs: Long) {
        if (controlsLocked) return
        controller.seekRelative(deltaMs)
        showHint(deltaMs, controller.snapshot.value.currentPosition)
        controls.onUserInteraction()
    }

    fun handleDoubleTap(xFraction: Float) {
        if (controlsLocked) return
        val action = tap.onDoubleTap(xFraction, SystemClock.uptimeMillis())
        when (action) {
            TapActionState.DoubleTapAction.SEEK_BACK -> {
                val d = -tap.deltaSeconds() * 1000L
                controller.seekRelative(d)
                showHint(d, controller.snapshot.value.currentPosition)
            }
            TapActionState.DoubleTapAction.SEEK_FORWARD -> {
                val d = tap.deltaSeconds() * 1000L
                controller.seekRelative(d)
                showHint(d, controller.snapshot.value.currentPosition)
            }
            TapActionState.DoubleTapAction.TOGGLE_PLAY_PAUSE -> {
                tap.reset()
                controller.togglePlayPause()
            }
        }
        controls.onUserInteraction()
    }

    fun startTempSpeed() {
        if (controlsLocked) return
        speed.startTempSpeed(snapshot.isPlaying)
        if (speed.tempActive) controller.setSpeed(SpeedState.TEMP_SPEED)
    }

    fun endTempSpeed() {
        if (speed.tempActive) {
            speed.endTempSpeed()
            controller.setSpeed(speed.userSpeed)
        }
    }

    fun toggleLock() {
        val next = !controls.locked
        controls.setLocked(next)
        controller.setLocked(next)
        if (next) {
            seekPreview.onCancel()
            scrub.cancel()
            seekHint = null
            brightnessPct = null
            volumePct = null
        }
    }

    fun toggleFullscreen() {
        val a = activity ?: return
        if (isFullscreen) {
            controller.exitFullscreen(a)
        } else {
            controller.enterFullscreen(a)
        }
        controls.onUserInteraction()
    }

    fun resetForSwitch() {
        tap.reset()
        seekHint = null
        brightnessPct = null
        volumePct = null
        seekPreview.onCancel()
        scrub.cancel()
        endTempSpeed()
        controls.onUserInteraction()
    }

    fun goPrevious() {
        contextState = contextState.previous()
        resetForSwitch()
    }

    fun goNext() {
        contextState = contextState.next()
        resetForSwitch()
    }

    val displayPosition = when {
        scrub.isScrubbing -> scrub.previewMs
        seekPreview.isActive -> seekPreview.targetMs
        else -> snapshot.currentPosition
    }

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
                    }
                },
                onDragEnd = {
                    if (seekPreview.isActive) {
                        seekPreview.onEnd()?.let { controller.seekTo(it) }
                        showHint(seekPreview.deltaMs, seekPreview.targetMs)
                        controls.onUserInteraction()
                    }
                },
                onDragCancel = {
                    if (seekPreview.isActive) {
                        seekPreview.onCancel()
                        seekHint = null
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

    // ---------- 布局 ----------
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
        if (controlsVisible && !controlsLocked) {
            GsyNativeTopBar(
                title = current?.title ?: "",
                subtitle = "",
                onBack = { onBack() },
                onMore = { sheet = PlayerSheet.MORE },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            GsyNativeCenterControls(
                playing = PlaybackUiMapper.isPlaying(snapshot.state),
                onTogglePlay = { togglePlayPause() },
                onRewind = { seekRelative(-10_000L) },
                onForward = { seekRelative(10_000L) },
                modifier = Modifier.align(Alignment.Center),
            )

            GsyNativeBottomBar(
                positionMs = displayPosition,
                durationMs = snapshot.duration,
                bufferPercent = snapshot.bufferPercent,
                speedLabel = formatSpeedLabel(speed.effectiveSpeed),
                scaleLabel = scale.mode.label,
                locked = controlsLocked,
                isFullscreen = isFullscreen,
                onScrub = { value ->
                    if (!scrub.isScrubbing) {
                        scrub.begin(snapshot.currentPosition)
                        controls.onUserInteraction()
                    }
                    scrub.update(value.toLong())
                },
                onScrubEnd = {
                    scrub.commit()?.let { controller.seekTo(it) }
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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 锁定态：只保留锁图标（点击解锁）
        if (controlsLocked) {
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
            overlay = PlaybackUiMapper.overlayFor(snapshot.state),
            seekHint = seekHint,
            seekDurationMs = snapshot.duration,
            brightnessPct = brightnessPct,
            volumePct = volumePct,
            tempSpeedActive = speed.tempActive,
            hasNext = contextState.hasNext,
            onRestart = { togglePlayPause() },
            onNext = { goNext() },
            onRetry = { controller.retry() },
            onBack = { onBack() },
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
            current = speed.userSpeed,
            onSelect = { s ->
                speed.setSpeed(s)
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
            speedLabel = formatSpeedLabel(speed.userSpeed),
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
