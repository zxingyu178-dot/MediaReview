@file:androidx.media3.common.util.UnstableApi

package com.mediareview.app.feature.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import com.mediareview.app.ui.theme.MediaControlScrim
import com.mediareview.app.ui.theme.MediaControlScrimMedium
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaOnImmersive
import androidx.navigation.navArgument
import com.mediareview.app.core.media.PlaybackPhase
import com.mediareview.app.core.media.PlaybackStatus
import com.mediareview.app.core.media.PlayerCore
import com.mediareview.app.ui.theme.MediaAccent
import com.mediareview.app.ui.theme.MediaDimensions
import com.mediareview.app.ui.theme.MediaSpacing
import kotlinx.coroutines.delay

/** 普通播放器导航路由:只传 mediaId。 */
object PlayerDestinations {
    const val ROUTE = "player/{mediaId}"
    fun build(mediaId: String): String = "player/$mediaId"
}

/** 注册普通播放器目的地。 */
fun NavGraphBuilder.playerGraph(navController: NavController) {
    composable(
        route = PlayerDestinations.ROUTE,
        arguments = listOf(navArgument("mediaId") { type = NavType.StringType }),
    ) { entry ->
        val mediaId = entry.arguments?.getString("mediaId").orEmpty()
        PlayerScreen(
            mediaId = mediaId,
            onBack = { navController.popBackStack() },
        )
    }
}

/**
 * 普通播放器:Media3 PlayerView 全屏播放 Jellyfin 直连流。
 *
 * 交互:
 * - 单击: 显示/隐藏控制层
 * - 双击左半屏: 快退 10s;右半屏: 快进 10s
 * - 左半屏上下滑动: 调节亮度;右半屏上下滑动: 调节音量
 * - 左右滑动: seek 快进/快退
 * - 控制层: 播放/暂停、进度拖动、倍速、静音、画面比例、音轨、字幕、横竖屏、锁定
 * 底部控制层使用 Column 堆叠,避免各控制行互相重叠。
 */
@Composable
fun PlayerScreen(
    mediaId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val core = viewModel.core
    val status by core.status.collectAsState()
    val appContext = LocalContext.current
    val activity = appContext as? Activity

    LaunchedEffect(mediaId) { viewModel.load(mediaId) }

    // 进度轮询
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = core.player.currentPosition
            delay(500)
        }
    }
    // 供切换画面比例时引用 PlayerView
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // 交互状态
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var boxWidth by remember { mutableIntStateOf(1) }
    var boxHeight by remember { mutableIntStateOf(1) }
    val audioManager = remember {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val errorText = ui.error
    val ready = !ui.loading && errorText == null

    Box(
        Modifier
            .fillMaxSize()
            .background(MediaImmersiveBackground)
            .onSizeChanged { boxWidth = it.width; boxHeight = it.height },
    ) {
        if (ui.loading) {
            CircularProgressIndicator(
                color = MediaOnImmersive,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (errorText != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(errorText, color = MediaOnImmersive)
                Spacer(Modifier.padding(MediaSpacing.Small))
                TextButton(onClick = { viewModel.load(mediaId) }) { Text("重试", color = MediaOnImmersive) }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = core.player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        playerViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 手势层(覆盖视频;锁定时禁用)
        if (ready && !locked) {
            GestureLayer(
                modifier = Modifier.matchParentSize(),
                boxWidth = boxWidth,
                boxHeight = boxHeight,
                onTap = { controlsVisible = !controlsVisible },
                onDoubleTap = { offset ->
                    val seekMs = 10_000L
                    if (offset.x < boxWidth / 2f) {
                        viewModel.seekTo((core.player.currentPosition - seekMs).coerceAtLeast(0))
                    } else {
                        viewModel.seekTo(core.player.currentPosition + seekMs)
                    }
                },
                onVerticalDrag = { startX, dragAmount ->
                    val delta = -dragAmount / boxHeight.toFloat()
                    if (startX < boxWidth / 2f) {
                        // 亮度(左半屏)
                        val base = activity?.window?.attributes?.screenBrightness?.takeIf { it > 0f }
                            ?: 0.5f
                        activity?.setBrightness((base + delta).coerceIn(0.01f, 1f))
                    } else {
                        // 音量(右半屏)
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val target = (cur + delta * max).toInt().coerceIn(0, max)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    }
                },
                onHorizontalDrag = { dragAmount ->
                    val deltaMs = (dragAmount * 250).toLong()
                    viewModel.seekTo((core.player.currentPosition + deltaMs).coerceAtLeast(0))
                },
            )
        }

        // 顶栏:返回 + 标题 + 锁定/解锁
        if (controlsVisible && ready) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MediaControlScrim)
                    .padding(horizontal = MediaSpacing.XSmall, vertical = MediaSpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回", color = MediaOnImmersive) }
                Spacer(Modifier.weight(1f))
                Text(
                    text = ui.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediaOnImmersive,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = MediaSpacing.Regular),
                )
                IconButton(
                    onClick = { locked = true },
                    modifier = Modifier.sizeIn(
                        minWidth = MediaDimensions.MinimumTouchTarget,
                        minHeight = MediaDimensions.MinimumTouchTarget,
                    ),
                ) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = "锁定控制", tint = MediaOnImmersive)
                }
            }
        }

        // 底部控制(Column 堆叠,避免重叠)
        if (controlsVisible && ready) {
            BottomControls(
                core = core,
                status = status,
                positionMs = positionMs,
                playerViewRef = playerViewRef,
                onTogglePlay = { viewModel.togglePlayPause() },
                onSeekTo = { viewModel.seekTo(it) },
                onSpeed = { viewModel.setPlaybackSpeed(it) },
                onVolume = { viewModel.setVolume(it) },
                onLock = { locked = true },
                onReportProgress = { viewModel.reportProgress() },
                onReportFinalProgress = { viewModel.reportFinalProgress() },
                appContext = appContext,
            )
        }

        // 锁定后常驻解锁入口(小锁图标,点击解锁)
        if (locked) {
            IconButton(
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MediaSpacing.Regular)
                    .background(MediaControlScrimMedium, MaterialTheme.shapes.medium)
                    .sizeIn(
                        minWidth = MediaDimensions.MinimumTouchTarget,
                        minHeight = MediaDimensions.MinimumTouchTarget,
                    ),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = "解锁控制", tint = MediaOnImmersive)
            }
        }
    }
}

/** 单击 / 双击 / 上下左右拖动 手势层(由调用方从 BoxScope 传入 matchParentSize 修饰符)。 */
@Composable
private fun GestureLayer(
    modifier: Modifier,
    boxWidth: Int,
    boxHeight: Int,
    onTap: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onVerticalDrag: (startX: Float, dragAmount: Float) -> Unit,
    onHorizontalDrag: (dragAmount: Float) -> Unit,
) {
    var dragStartX by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .pointerInput(boxWidth, boxHeight) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset -> onDoubleTap(offset) },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> dragStartX = offset.x },
                    onVerticalDrag = { change, dragAmount ->
                        onVerticalDrag(dragStartX, dragAmount)
                        change.consume()
                    },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        onHorizontalDrag(dragAmount)
                        change.consume()
                    },
                )
            },
    )
}

/** 底部控制层:播放/暂停 + 进度 Slider + 倍速/音量/比例/音轨/字幕/横竖屏/锁定(Column 堆叠)。 */
@Composable
private fun BottomControls(
    core: PlayerCore,
    status: PlaybackStatus,
    positionMs: Long,
    playerViewRef: PlayerView?,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onLock: () -> Unit,
    onReportProgress: () -> Unit,
    onReportFinalProgress: () -> Unit,
    appContext: Context,
) {
    val playing = status.phase == PlaybackPhase.Playing
    var dragging by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var showTracks by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs) {
        if (!dragging) sliderPos = positionMs
    }

    // 播放进度闭环:每 10s 回传(读取实时状态);退出时补最后一条
    LaunchedEffect(Unit) {
        try {
            while (true) {
                onReportProgress()
                delay(10_000)
            }
        } finally {
            onReportFinalProgress()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MediaControlScrim)
            .padding(MediaSpacing.Small),
    ) {
        // 第一行:播放/暂停 + 进度 + 时间
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = MediaOnImmersive,
                )
            }
            val duration = core.player.duration.coerceAtLeast(0L)
            Slider(
                value = sliderPos.toFloat(),
                onValueChange = {
                    dragging = true
                    sliderPos = it.toLong()
                },
                onValueChangeFinished = {
                    onSeekTo(sliderPos)
                    dragging = false
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${fmt(positionMs)} / ${fmt(duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = MediaOnImmersive,
                modifier = Modifier.padding(start = MediaSpacing.Small),
            )
        }

        // 第二行:倍速 / 静音 / 画面比例 / 音轨字幕 / 横竖屏 / 锁定
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MediaSpacing.XSmall),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedMenu(speed) { s ->
                speed = s
                onSpeed(s)
            }
            IconButton(onClick = {
                muted = !muted
                onVolume(if (muted) 0f else 1f)
            }) {
                Icon(
                    imageVector = if (muted) {
                        Icons.AutoMirrored.Outlined.VolumeOff
                    } else {
                        Icons.AutoMirrored.Outlined.VolumeUp
                    },
                    contentDescription = if (muted) "取消静音" else "静音",
                    tint = MediaOnImmersive,
                )
            }
            AspectMenu(resizeMode) { m ->
                resizeMode = m
                playerViewRef?.resizeMode = m
            }
            IconButton(onClick = { showTracks = true }) {
                Icon(Icons.Outlined.Subtitles, contentDescription = "音轨与字幕", tint = MediaOnImmersive)
            }
            IconButton(onClick = { appContext.toggleOrientation() }) {
                Icon(Icons.Outlined.ScreenRotation, contentDescription = "切换屏幕方向", tint = MediaOnImmersive)
            }
            IconButton(onClick = onLock) {
                Icon(Icons.Filled.Lock, contentDescription = "锁定控制", tint = MediaOnImmersive)
            }
        }
    }

    if (showTracks) {
        TrackDialog(
            tracks = core.player.currentTracks,
            onSelect = { group, track -> core.selectTrack(group, track) },
            onClearSubtitles = { core.clearTracksOfType(C.TRACK_TYPE_TEXT) },
            onDismiss = { showTracks = false },
        )
    }
}

/** 音轨/字幕选择对话框。 */
@Composable
private fun TrackDialog(
    tracks: Tracks,
    onSelect: (Int, Int) -> Unit,
    onClearSubtitles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val audioGroups = tracks.groups.mapIndexedNotNull { gi, g ->
        if (g.type == C.TRACK_TYPE_AUDIO) gi to g else null
    }
    val textGroups = tracks.groups.mapIndexedNotNull { gi, g ->
        if (g.type == C.TRACK_TYPE_TEXT) gi to g else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音轨 / 字幕") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MediaSpacing.Small)) {
                if (audioGroups.isEmpty() && textGroups.isEmpty()) {
                    Text("当前媒体没有可切换的附加轨道")
                }
                if (audioGroups.isNotEmpty()) {
                    Text("音轨", style = MaterialTheme.typography.titleSmall)
                    audioGroups.forEach { (gi, g) ->
                        (0 until g.length).forEach { ti ->
                            val label = g.getTrackFormat(ti).language?.let { "语言 $it" }
                                ?: "音轨 ${ti + 1}"
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (g.isTrackSelected(ti)) MediaAccent
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = MediaDimensions.MinimumTouchTarget)
                                    .clickable { onSelect(gi, ti); onDismiss() }
                                    .padding(vertical = MediaSpacing.Small),
                            )
                        }
                    }
                }
                if (textGroups.isNotEmpty()) {
                    Text("字幕", style = MaterialTheme.typography.titleSmall)
                    textGroups.forEach { (gi, g) ->
                        (0 until g.length).forEach { ti ->
                            val label = g.getTrackFormat(ti).language?.let { "语言 $it" }
                                ?: "字幕 ${ti + 1}"
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (g.isTrackSelected(ti)) MediaAccent
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = MediaDimensions.MinimumTouchTarget)
                                    .clickable { onSelect(gi, ti); onDismiss() }
                                    .padding(vertical = MediaSpacing.Small),
                            )
                        }
                    }
                    TextButton(onClick = { onClearSubtitles(); onDismiss() }) {
                        Text("关闭字幕")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** 倍速菜单。 */
@Composable
private fun SpeedMenu(current: Float, onSelect: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PlayerTextMenuButton(label = "${current}x", onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                DropdownMenuItem(text = { Text("${s}x") }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}

/** 画面比例菜单。 */
@Composable
private fun AspectMenu(current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "缩放"
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "填充"
        else -> "适应"
    }
    Box {
        PlayerTextMenuButton(label = label, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                AspectRatioFrameLayout.RESIZE_MODE_FIT,
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                AspectRatioFrameLayout.RESIZE_MODE_FILL,
            ).forEach { m ->
                val l = when (m) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "缩放"
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "填充"
                    else -> "适应"
                }
                DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(m); expanded = false })
            }
        }
    }
}

@Composable
internal fun PlayerTextMenuButton(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(
            minWidth = MediaDimensions.MinimumTouchTarget,
            minHeight = MediaDimensions.MinimumTouchTarget,
        ),
    ) {
        Text(
            text = label,
            color = MediaOnImmersive,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 横竖屏切换。 */
private fun Context.toggleOrientation() {
    val activity = this as? Activity ?: return
    activity.requestedOrientation =
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
}

/** 设置窗口亮度。 */
private fun Activity.setBrightness(value: Float) {
    window.attributes = window.attributes.apply { screenBrightness = value }
}
