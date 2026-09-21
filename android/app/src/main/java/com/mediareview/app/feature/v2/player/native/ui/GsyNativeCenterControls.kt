package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaControlScrimSoft
import com.mediareview.app.ui.theme.MediaTextPrimary

/**
 * 中央控制：-10 / 播放暂停 / +10。
 * 播放状态直接来自 GSYPlayerSnapshot，本组件不维护第二套 isPlaying。
 */
@Composable
fun GsyNativeCenterControls(
    playing: Boolean,
    onTogglePlay: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        CenterIconButton(icon = Icons.Default.Replay10, desc = "快退10秒", onClick = onRewind)
        CenterPlayButton(playing = playing, onToggle = onTogglePlay)
        CenterIconButton(icon = Icons.Default.Forward10, desc = "快进10秒", onClick = onForward)
    }
}

/** 大号播放/暂停（按下轻微缩放）。 */
@Composable
private fun CenterPlayButton(
    playing: Boolean,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(76.dp)
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
            modifier = Modifier.size(48.dp),
        )
    }
}

/** 中等圆形按钮（±10s）。 */
@Composable
private fun CenterIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer {
                scaleX = if (pressed) 0.92f else 1f
                scaleY = if (pressed) 0.92f else 1f
            }
            .clip(CircleShape)
            .background(MediaControlScrimSoft)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MediaTextPrimary, modifier = Modifier.size(32.dp))
    }
}
