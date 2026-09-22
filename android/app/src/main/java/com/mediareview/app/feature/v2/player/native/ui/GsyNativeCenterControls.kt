package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaTextPrimary

/**
 * 中央控制区（Stage6：成熟播放器标准 5 键布局）。
 *
 * 布局（与 Media3 PlayerControlView / 国内主流视频播放器一致）：
 *
 *     上一条 │ 快退10s │ 播放/暂停 │ 快进10s │ 下一条
 *
 * - 上一条 / 下一条复用 PlaybackContext 的列表导航，首集 / 末集自动置灰禁用；
 * - 快退 / 快进为片内 ±10s；播放 / 暂停为中央主按钮；
 * - 圆形半透明底，克制不挡画面；尺寸由外向内递增，突出播放主按钮。
 */
@Composable
fun GsyNativeCenterControls(
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .testTag("player_center_controls")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CenterIconButton(
            icon = Icons.Filled.SkipPrevious,
            label = "上一条",
            boxSize = 48.dp,
            iconSize = 28.dp,
            enabled = hasPrevious,
            onClick = onPrevious,
            tag = "player_btn_previous",
        )
        CenterIconButton(
            icon = Icons.Filled.Replay10,
            label = "快退10秒",
            boxSize = 52.dp,
            iconSize = 30.dp,
            onClick = onRewind,
        )
        CenterPlayButton(isPlaying = isPlaying, onClick = onPlayPause)
        CenterIconButton(
            icon = Icons.Filled.Forward10,
            label = "快进10秒",
            boxSize = 52.dp,
            iconSize = 30.dp,
            onClick = onForward,
        )
        CenterIconButton(
            icon = Icons.Filled.SkipNext,
            label = "下一条",
            boxSize = 48.dp,
            iconSize = 28.dp,
            enabled = hasNext,
            onClick = onNext,
            tag = "player_btn_next",
        )
    }
}

/**
 * 中央圆形播放 / 暂停主按钮：68dp 点击区，42dp 图标，圆形半透明底。
 */
@Composable
private fun CenterPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(CircleScrim)
            .testTag("player_btn_play_pause"),
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            modifier = Modifier.size(42.dp),
            tint = MediaTextPrimary,
        )
    }
}

/**
 * 中央圆形图标按钮（上一条 / 快退 / 快进 / 下一条）。
 *
 * @param enabled false 时按钮置灰且不响应点击（用于首集 / 末集边界）。
 */
@Composable
private fun CenterIconButton(
    icon: ImageVector,
    label: String,
    boxSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(boxSize)
            .clip(CircleShape)
            .background(CircleScrim)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(iconSize),
            tint = MediaTextPrimary.copy(alpha = if (enabled) 1f else 0.32f),
        )
    }
}

/** 中央按钮圆形半透明背景：克制、不遮挡过多视频画面。 */
private val CircleScrim = Color(0x33000000)
