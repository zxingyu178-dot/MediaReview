package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaTextPrimary

/** 播放中不遮挡画面；暂停时只提供一个播放入口。 */
@Composable
fun GsyNativeCenterControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isPlaying) return
    IconButton(
        onClick = onPlayPause,
        modifier = modifier.size(60.dp).clip(CircleShape)
            .background(Color(0x33000000)).testTag("player_btn_play_pause"),
    ) {
        Icon(Icons.Filled.PlayArrow, "播放", Modifier.size(36.dp), tint = MediaTextPrimary)
    }
}
