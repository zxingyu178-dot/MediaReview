@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mediareview.app.feature.v2.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.player.gesture.PlayerGestureHandlers
import com.mediareview.app.feature.v2.player.ui.PlayerBottomControls
import com.mediareview.app.feature.v2.player.ui.PlayerGestureOverlay
import com.mediareview.app.feature.v2.player.ui.PlayerIndicators
import com.mediareview.app.feature.v2.player.ui.PlayerTopControls
import com.mediareview.app.feature.v2.player.ui.SpeedSheet
import com.mediareview.app.ui.theme.MediaControlScrimSoft
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary

/**
 * V2 播放器（Next Player 风格正式播放器基础）。
 *
 * 本文件只负责组装：PlayerController（引擎）+ 各手势 / 状态 + UI 组件。
 * 全部播放 / 手势 / 状态逻辑在 [V2PlayerViewModel] 与 player/state、player/gesture 中，
 * 批阅模式可复用同一 PlayerController / ViewModel 结构。
 */
@androidx.compose.runtime.Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
fun V2PlayerScreen(
    media: V2Media,
    playbackUri: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val vm: V2PlayerViewModel = viewModel(
        key = "v2-player-${media.id}",
        factory = viewModelFactory {
            initializer {
                V2PlayerViewModel(context.applicationContext, media, playbackUri)
            }
        },
    )
    val state = vm.uiState
    var showSpeedSheet by remember { mutableStateOf(false) }

    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        vm.onEnter(activity)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.onAppBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 兜底恢复：返回按钮路径也会调用，此处幂等
            vm.onExit(activity)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MediaImmersiveBackground),
    ) {
        // 视频层
        AndroidView(
            factory = { playerView },
            update = { view -> vm.bindPlayerView(view, state.scaleMode) },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层（位于视频之上、控制层之下）
        PlayerGestureOverlay(
            handlers = remember {
                PlayerGestureHandlers(
                    onTap = { vm.onSingleTap() },
                    onDoubleTap = { vm.onDoubleTap(it) },
                    onLongPressStart = { vm.onLongPressStart() },
                    onLongPressEnd = { vm.onLongPressEnd() },
                    onSeekStart = { x, w -> vm.onSeekStart(x, w) },
                    onSeekDrag = { vm.onSeekDrag(it) },
                    onSeekEnd = { vm.onSeekEnd() },
                    onSeekCancel = { vm.onSeekCancel() },
                    onVerticalStart = { y, h -> vm.onVerticalStart(activity, y, h) },
                    onVerticalDrag = { vm.onVerticalDrag(activity, it) },
                    onVerticalEnd = { vm.onVerticalEnd() },
                    onVerticalCancel = { vm.onVerticalCancel() },
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 控制层（播放中 3s 无操作自动隐藏 / 暂停保持）
        if (state.controlsVisible && !state.isLocked) {
            PlayerTopControls(
                title = media.name,
                subtitle = "${media.code} · ${media.folderName}",
                isLandscape = state.isLandscape,
                onBack = {
                    vm.onExit(activity)
                    onBack()
                },
                onRotate = { vm.toggleRotation(activity) },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            CenterPlayPauseButton(
                playing = state.phase == PlaybackPhase.PLAYING,
                onToggle = vm::togglePlayPause,
                modifier = Modifier.align(Alignment.Center),
            )

            PlayerBottomControls(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                speed = state.speed,
                scaleMode = state.scaleMode,
                locked = state.isLocked,
                onScrub = vm::sliderScrub,
                onScrubEnd = vm::sliderScrubEnd,
                onOpenSpeedSheet = {
                    showSpeedSheet = true
                    vm.controlsInteraction()
                },
                onCycleScale = vm::cycleScale,
                onToggleLock = vm::toggleLock,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 锁定态：只保留解锁按钮（点击锁图标解锁，禁任意双击解锁）
        if (state.isLocked) {
            IconButton(
                onClick = vm::unlock,
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

        // 指示器（Loading / Seek 提示 / 音量亮度 / 临时 2x / Ended / Error）
        PlayerIndicators(
            phase = state.phase,
            seekHintDeltaMs = state.seekHintDeltaMs,
            seekHintPositionMs = state.seekHintPositionMs,
            durationMs = state.durationMs,
            volumeHintPct = state.volumeHintPct,
            brightnessHintPct = state.brightnessHintPct,
            tempSpeedActive = state.isTempSpeedActive,
            onRetry = vm::retry,
            onRestart = vm::restart,
            onBack = {
                vm.onExit(activity)
                onBack()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showSpeedSheet) {
        SpeedSheet(
            current = state.speed,
            onSelect = { s ->
                vm.setSpeed(s)
                showSpeedSheet = false
            },
            onDismiss = { showSpeedSheet = false },
        )
    }
}

/** 中央大号播放 / 暂停按钮（按下轻微缩放）。 */
@Composable
private fun CenterPlayPauseButton(
    playing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(88.dp)
            .graphicsLayer {
                scaleX = if (pressed) 0.92f else 1f
                scaleY = if (pressed) 0.92f else 1f
            }
            .clip(CircleShape)
            .background(MediaControlScrimSoft)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (playing) "暂停" else "播放",
            tint = MediaTextPrimary,
            modifier = Modifier.size(56.dp),
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
