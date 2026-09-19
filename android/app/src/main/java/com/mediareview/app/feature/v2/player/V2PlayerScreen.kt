@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mediareview.app.feature.v2.player

import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mediareview.app.feature.v2.home.formatDuration
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaControlScrim
import com.mediareview.app.ui.theme.MediaControlSurface
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import kotlinx.coroutines.delay

/**
 * V2 播放器（Next Player 风格核心体验）：
 * 单击显隐控制层、双击左/右快退快进、双击中央播放暂停、左半屏上下滑亮度、
 * 右半屏上下滑音量、横向拖动 Seek、底部进度条、横竖屏/画面适应/缩放/倍速/锁定。
 */
@OptIn(UnstableApi::class)
@Composable
fun V2PlayerScreen(
    media: V2Media,
    playbackUri: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var resizeFill by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(true) }
    var seekHintMs by remember { mutableStateOf<Long?>(null) }
    var brightnessHint by remember { mutableStateOf<Float?>(null) }
    var volumeHint by remember { mutableStateOf<Int?>(null) }
    var seekHintActive by remember { mutableStateOf(false) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val window = remember { context.findActivity()?.window }
    var brightness by remember {
        mutableFloatStateOf(window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f)
    }

    DisposableEffect(player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L).toFloat()
                }
            }
        })
        player.setMediaItem(MediaItem.fromUri(playbackUri))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!seekHintActive) {
                positionMs = player.currentPosition.toFloat()
            }
            delay(250)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MediaImmersiveBackground)
            .playerGestures(
                onTap = {
                    if (locked) return@playerGestures
                    controlsVisible = !controlsVisible
                },
                onDoubleTap = { x ->
                    if (locked) {
                        locked = false
                        controlsVisible = true
                        return@playerGestures
                    }
                    when {
                        x < 0.33f -> {
                            val t = (player.currentPosition - 10_000L).coerceAtLeast(0L)
                            player.seekTo(t)
                            seekHintMs = t
                        }
                        x > 0.66f -> {
                            val t = (player.currentPosition + 10_000L).coerceAtMost(player.duration.coerceAtLeast(0L))
                            player.seekTo(t)
                            seekHintMs = t
                        }
                        else -> togglePlayPause(player, isPlaying)
                    }
                },
                onVerticalDrag = { deltaY, isLeft ->
                    if (locked) return@playerGestures
                    if (isLeft) {
                        // 亮度
                        brightness = (brightness - deltaY / 600f).coerceIn(0.05f, 1f)
                        window?.attributes = window.attributes.apply {
                            screenBrightness = brightness
                        }
                        brightnessHint = brightness
                    } else {
                        // 音量
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val next = (cur - deltaY / 80f).toInt().coerceIn(0, max)
                        if (next != cur) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                        }
                        volumeHint = next
                    }
                },
                onSeekDragStart = {
                    if (!locked) {
                        seekHintActive = true
                    }
                },
                onSeekDrag = { deltaX ->
                    if (!locked) {
                        val seek = (positionMs + deltaX * 500f).coerceIn(0f, durationMs.coerceAtLeast(0f))
                        positionMs = seek
                        seekHintMs = seek.toLong()
                    }
                },
                onSeekDragEnd = {
                    if (seekHintActive) {
                        player.seekTo(positionMs.toLong())
                        seekHintActive = false
                        seekHintMs = null
                    }
                },
            ),
    ) {
        // 视频画面
        AndroidView(
            factory = { playerView },
            update = { view ->
                view.player = player
                view.resizeMode = if (resizeFill) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.keepScreenOn = true
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部信息栏
        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(MediaControlScrim)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MediaTextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(media.name, style = MaterialTheme.typography.titleMedium, color = MediaTextPrimary)
                    Text(
                        text = "${media.code} · ${media.folderName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MediaTextPrimary.copy(alpha = 0.7f),
                    )
                }
                IconButton(onClick = { isFullscreen = !isFullscreen }) {
                    Icon(
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        "全屏切换",
                        tint = MediaTextPrimary,
                    )
                }
            }
        }

        // 中央播放/暂停
        if (controlsVisible && !locked) {
            IconButton(
                onClick = { togglePlayPause(player, isPlaying) },
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "暂停" else "播放",
                    tint = MediaTextPrimary,
                    modifier = Modifier.size(64.dp),
                )
            }
        }

        // 快进/快退提示
        seekHintMs?.let { hint ->
            SeekHintOverlay(
                position = hint,
                duration = durationMs.toLong(),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 亮度/音量提示
        brightnessHint?.let { b ->
            HintBadge(
                icon = Icons.Default.BrightnessMedium,
                text = "${(b * 100).toInt()}%",
                modifier = Modifier.align(Alignment.Center),
            )
        }
        volumeHint?.let { v ->
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            HintBadge(
                icon = Icons.Default.VolumeUp,
                text = "${if (max > 0) v * 100 / max else 0}%",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 锁定提示
        if (locked && !controlsVisible) {
            Icon(
                Icons.Default.Lock,
                "已锁定，双击解锁",
                tint = MediaTextPrimary.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }

        // 底部控制层
        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MediaControlScrim)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
            ) {
                // 进度条
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatDuration(positionMs.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MediaTextPrimary,
                    )
                    Slider(
                        value = positionMs,
                        onValueChange = {
                            if (!locked) {
                                positionMs = it
                                player.seekTo(it.toLong())
                            }
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = V2Colors.Accent,
                            activeTrackColor = V2Colors.Accent,
                            inactiveTrackColor = MediaTextPrimary.copy(alpha = 0.3f),
                        ),
                    )
                    Text(
                        formatDuration(durationMs.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MediaTextPrimary.copy(alpha = 0.7f),
                    )
                }
                // 控制按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(Icons.Default.FastRewind, "快退10秒", locked) {
                        val t = (player.currentPosition - 10_000L).coerceAtLeast(0L)
                        player.seekTo(t)
                        seekHintMs = t
                    }
                    ControlButton(Icons.Default.FastForward, "快进10秒", locked) {
                        val t = (player.currentPosition + 10_000L).coerceAtMost(player.duration.coerceAtLeast(0L))
                        player.seekTo(t)
                        seekHintMs = t
                    }
                    Text(
                        "${speed}x",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediaTextPrimary,
                        modifier = Modifier
                            .clickable(enabled = !locked) {
                                speed = if (speed >= 2f) 1f else speed + 0.5f
                                player.setPlaybackSpeed(speed)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ControlButton(
                        if (resizeFill) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                        "画面适应",
                        locked,
                    ) {
                        resizeFill = !resizeFill
                        playerView.resizeMode = if (resizeFill) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    ControlButton(
                        Icons.Default.Lock,
                        if (locked) "已锁定" else "锁定",
                        locked = false,
                    ) {
                        locked = !locked
                        if (locked) controlsVisible = false
                    }
                }
            }
        }
    }
}

private fun togglePlayPause(player: Player, isPlaying: Boolean) {
    if (isPlaying) player.pause() else player.play()
}

private fun Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    desc: String,
    locked: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = !locked) {
        Icon(icon, desc, tint = MediaTextPrimary)
    }
}

@Composable
private fun SeekHintOverlay(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MediaControlSurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            formatDuration(position),
            style = MaterialTheme.typography.titleLarge,
            color = MediaTextPrimary,
        )
        Text(
            "${formatDuration(position)} / ${formatDuration(duration)}",
            style = MaterialTheme.typography.bodySmall,
            color = MediaTextPrimary.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun HintBadge(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MediaControlSurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MediaTextPrimary, modifier = Modifier.size(24.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MediaTextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
