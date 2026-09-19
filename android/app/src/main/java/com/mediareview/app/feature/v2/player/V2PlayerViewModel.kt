package com.mediareview.app.feature.v2.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.player.gesture.SeekGestureState
import com.mediareview.app.feature.v2.player.gesture.TapGestureState
import com.mediareview.app.feature.v2.player.gesture.VolumeBrightnessGestureState
import com.mediareview.app.feature.v2.player.state.ControlsVisibilityState
import com.mediareview.app.feature.v2.player.state.PlaybackSpeedState
import com.mediareview.app.feature.v2.player.state.RotationState
import com.mediareview.app.feature.v2.player.state.VideoScaleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * V2 播放器 ViewModel：组合 PlayerController 与各手势 / 状态对象，
 * UI 层只订阅 [uiState]。
 *
 * 进入播放器前由外部调用 [onEnter] 记录方向 / 沉浸；离开时调用 [onExit] 恢复，
 * 恢复逻辑幂等（同时会被 onDispose 兜底调用）。
 */
@OptIn(UnstableApi::class)
class V2PlayerViewModel(
    appContext: Context,
    val media: V2Media,
    private val playbackUri: String,
) : ViewModel() {

    companion object {
        /** 提示（Seek / 音量 / 亮度）自动消失时间。 */
        const val HINT_DISMISS_MS = 800L
        /** 位置轮询间隔。 */
        const val POSITION_TICK_MS = 250L
    }

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val controller = PlayerController(appContext)

    // ---------- 状态对象 ----------
    val tap = TapGestureState()
    val seek = SeekGestureState()
    val volumeBrightness = VolumeBrightnessGestureState()
    val controls = ControlsVisibilityState(
        scope = viewModelScope,
        onVisibilityChange = { publish() },
        onLockChange = { publish() },
    )
    val rotation = RotationState()
    val scale = VideoScaleState()
    val speed = PlaybackSpeedState()

    /** UI 唯一数据源。 */
    var uiState by mutableStateOf(PlayerUiState())
        private set

    // ---------- 内部状态 ----------
    private var lastPlaying: Boolean? = null
    private var scrubbingValue: Float? = null
    private var accumulatedDx = 0f
    private var accumulatedDy = 0f
    private var hintJob: Job? = null
    /** 进入播放器前的窗口亮度（<0 表示自动亮度）。 */
    private var savedBrightness: Float? = null
    private var brightnessPct = 50f

    init {
        controller.listener = object : PlayerController.Listener {
            override fun onStateChanged() = publish()
        }
        controller.prepare(playbackUri)
        viewModelScope.launch {
            while (isActive) {
                publish()
                delay(POSITION_TICK_MS)
            }
        }
    }

    // ---------- 生命周期 ----------

    /** 进入播放器：记录方向 + 沉浸隐藏系统栏 + 保持屏幕常亮。 */
    fun onEnter(activity: Activity?) {
        activity ?: return
        rotation.initialize(activity.requestedOrientation)
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 离开播放器：恢复方向 / 亮度 / 系统栏 / 屏幕常亮（幂等）。 */
    fun onExit(activity: Activity?) {
        rotation.restore()?.let { orientation -> activity?.requestedOrientation = orientation }
        val window = activity?.window ?: return
        savedBrightness?.let { saved ->
            val current = window.attributes.screenBrightness
            if (current != saved) {
                window.attributes = window.attributes.apply { screenBrightness = saved }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** App 进入后台：自动暂停，回前台不擅自自动播放。 */
    fun onAppBackground() {
        controller.pause()
        publish()
    }

    /** 绑定 PlayerView（AndroidView update 每次重绘调用）。 */
    fun bindPlayerView(view: PlayerView, scaleMode: VideoScaleState.ScaleMode) {
        controller.bindView(view)
        view.resizeMode = scaleMode.toResizeMode()
    }

    // ---------- 播放控制 ----------

    fun togglePlayPause() {
        if (controls.locked) return
        controller.togglePlayPause()
        controls.onUserInteraction()
        publish()
    }

    fun restart() {
        controller.restart()
        controls.onUserInteraction()
        publish()
    }

    fun retry() {
        controller.retry()
        publish()
    }

    fun setSpeed(speedValue: Float) {
        speed.setSpeed(speedValue)
        controller.setSpeed(speedValue)
        controls.onUserInteraction()
        publish()
    }

    fun cycleScale() {
        if (controls.locked) return
        scale.cycle()
        controls.onUserInteraction()
        publish()
    }

    fun toggleRotation(activity: Activity?) {
        activity ?: return
        rotation.toggle()
        activity.requestedOrientation = rotation.requestedCode()
        controls.onUserInteraction()
        publish()
    }

    // ---------- 锁定 ----------

    fun toggleLock() {
        val next = !controls.locked
        controls.setLocked(next)
        if (next) {
            // 结束进行中的手势，避免锁定态残留提示
            seek.reset()
            volumeBrightness.onCancel()
            hintJob?.cancel()
            clearHints()
        }
        publish()
    }

    fun unlock() {
        controls.setLocked(false)
        publish()
    }

    // ---------- 单击 / 双击 / 长按 ----------

    fun onSingleTap() {
        if (controls.locked) return
        controls.toggleVisibility()
    }

    fun onDoubleTap(xFraction: Float) {
        if (controls.locked) return
        val action = tap.onDoubleTap(xFraction, SystemClock.uptimeMillis())
        when (action) {
            TapGestureState.DoubleTapAction.SEEK_BACK -> {
                val delta = -tap.deltaSeconds() * 1000L
                controller.seekBy(delta)
                showSeekHint(delta, controller.currentPosition)
            }
            TapGestureState.DoubleTapAction.SEEK_FORWARD -> {
                val delta = tap.deltaSeconds() * 1000L
                controller.seekBy(delta)
                showSeekHint(delta, controller.currentPosition)
            }
            TapGestureState.DoubleTapAction.TOGGLE_PLAY_PAUSE -> {
                tap.reset()
                controller.togglePlayPause()
            }
        }
        controls.onUserInteraction()
        publish()
    }

    fun onLongPressStart() {
        if (controls.locked) return
        speed.startTempSpeed(controller.isPlaying)
        if (speed.tempActive) {
            controller.setSpeed(PlaybackSpeedState.TEMP_SPEED)
        }
        publish()
    }

    fun onLongPressEnd() {
        if (speed.tempActive) {
            speed.endTempSpeed()
            controller.setSpeed(speed.userSpeed)
            publish()
        }
    }

    // ---------- 横向 Seek（画面拖拽） ----------

    fun onSeekStart(startX: Float, widthPx: Float) {
        if (controls.locked) return
        seek.onStart(controller.currentPosition, startX, widthPx)
        accumulatedDx = 0f
    }

    fun onSeekDrag(deltaX: Float) {
        if (!seek.isActive || controls.locked) return
        accumulatedDx += deltaX
        seek.onDrag(seek.startX + accumulatedDx, controller.duration.coerceAtLeast(0L))
        if (seek.deltaMs != 0L) {
            showSeekHint(seek.deltaMs, seek.currentTargetMs)
        }
        publish()
    }

    fun onSeekEnd() {
        if (!seek.isActive) return
        val target = seek.onEnd()
        controller.seekTo(target)
        scheduleHintDismiss()
        publish()
    }

    fun onSeekCancel() {
        if (!seek.isActive) return
        seek.onCancel()
        hintJob?.cancel()
        clearSeekHint()
        publish()
    }

    // ---------- 竖向音量 / 亮度 ----------

    fun onVerticalStart(activity: Activity?, startY: Float, heightPx: Float) {
        if (controls.locked) return
        val window = activity?.window
        val windowBrightness = window?.attributes?.screenBrightness ?: -1f
        savedBrightness = savedBrightness ?: windowBrightness
        brightnessPct = if (windowBrightness >= 0f) windowBrightness * 100f else 50f
        volumeBrightness.onStart(startY, currentVolumePct(), brightnessPct)
        dragHeightPx = if (heightPx > 0f) heightPx else 1f
        accumulatedDy = 0f
        showVolumeBrightnessHint(volumeBrightness.currentVolumePct, volumeBrightness.currentBrightnessPct)
    }

    fun onVerticalDrag(activity: Activity?, deltaY: Float) {
        if (!volumeBrightness.isActive || controls.locked) return
        accumulatedDy += deltaY
        volumeBrightness.onDrag(volumeBrightness.startY - accumulatedDy, dragHeightPx)
        brightnessPct = volumeBrightness.currentBrightnessPct
        activity?.window?.let { w ->
            val next = brightnessPct / 100f
            val attrs = w.attributes
            if (attrs.screenBrightness != next) {
                w.attributes = attrs.apply { screenBrightness = next }
            }
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (volumeBrightness.currentVolumePct / 100f * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        showVolumeBrightnessHint(volumeBrightness.currentVolumePct, volumeBrightness.currentBrightnessPct)
    }

    fun onVerticalEnd() {
        if (!volumeBrightness.isActive) return
        volumeBrightness.onEnd()
        scheduleHintDismiss()
    }

    fun onVerticalCancel() {
        if (!volumeBrightness.isActive) return
        volumeBrightness.onCancel()
        hintJob?.cancel()
        clearVolumeBrightnessHint()
        publish()
    }

    /** 竖向拖动开始时由外层传入的手势区域高度（换算 drag 距离 → 百分比）。 */
    private var dragHeightPx = 1f

    private fun showVolumeBrightnessHint(volumePct: Float, brightnessPctValue: Float) {
        volumeHintPct = volumePct.roundToInt().coerceIn(0, 100)
        brightnessHintPct = brightnessPctValue.roundToInt().coerceIn(0, 100)
        publish()
    }

    // ---------- Slider Scrubbing ----------

    fun sliderScrub(value: Float) {
        if (scrubbingValue == null) {
            scrubbingValue = uiState.positionMs.toFloat()
            controls.onUserInteraction()
        }
        scrubbingValue = value
        publish()
    }

    fun sliderScrubEnd() {
        val value = scrubbingValue
        scrubbingValue = null
        if (value != null) {
            controller.seekTo(value.toLong())
        }
        controls.onUserInteraction()
        publish()
    }

    /** 底部按钮 / 面板操作：重置自动隐藏计时。 */
    fun controlsInteraction() {
        controls.onUserInteraction()
    }

    // ---------- 内部 ----------

    private fun publish() {
        val playing = controller.isPlaying
        if (playing != lastPlaying) {
            lastPlaying = playing
            controls.updatePlayback(playing)
        }
        val duration = controller.duration.coerceAtLeast(0L)
        val phase = mapPlaybackPhase(
            controller.playbackState,
            controller.isPlaying,
            controller.everReady,
            controller.hasError,
        )
        val position = when {
            seek.isActive -> seek.currentTargetMs
            scrubbingValue != null -> scrubbingValue!!.toLong()
            else -> controller.currentPosition
        }
        uiState = PlayerUiState(
            phase = phase,
            positionMs = position,
            durationMs = duration,
            bufferedMs = controller.bufferedPosition.coerceAtLeast(0L),
            speed = speed.effectiveSpeed,
            isTempSpeedActive = speed.tempActive,
            isLocked = controls.locked,
            controlsVisible = controls.visible,
            scaleMode = scale.mode,
            isLandscape = rotation.orientation == RotationState.Orientation.LANDSCAPE,
            seekHintDeltaMs = seekHintDeltaMs,
            seekHintPositionMs = seekHintPositionMs,
            volumeHintPct = volumeHintPct,
            brightnessHintPct = brightnessHintPct,
            errorMessage = if (phase == PlaybackPhase.ERROR) "无法播放此视频" else null,
        )
    }

    // ---------- 提示 ----------

    private var seekHintDeltaMs: Long? = null
    private var seekHintPositionMs = 0L
    private var volumeHintPct: Int? = null
    private var brightnessHintPct: Int? = null

    private fun showSeekHint(deltaMs: Long, positionMs: Long) {
        seekHintDeltaMs = deltaMs
        seekHintPositionMs = positionMs
    }

    private fun scheduleHintDismiss() {
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            delay(HINT_DISMISS_MS)
            tap.reset()
            clearHints()
            publish()
        }
    }

    private fun clearHints() {
        clearSeekHint()
        clearVolumeBrightnessHint()
    }

    private fun clearSeekHint() {
        seekHintDeltaMs = null
        seekHintPositionMs = 0L
    }

    private fun clearVolumeBrightnessHint() {
        volumeHintPct = null
        brightnessHintPct = null
    }

    private fun currentVolumePct(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) current * 100f / max else 0f
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
