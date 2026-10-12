package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.formatDuration
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary

/** 两层底栏；进度条32dp触区，导航和播放设置始终留在底部。 */
@Composable
fun GsyNativeBottomBar(
    positionMs: Long,
    durationMs: Long,
    bufferPercent: Int,
    speedLabel: String,
    scaleLabel: String,
    locked: Boolean,
    isFullscreen: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onScrubCancel: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenScale: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    seekEnabled: Boolean = true,
) {
    Column(
        modifier.fillMaxWidth().testTag("player_bottom_bar")
            .background(Brush.verticalGradient(listOf(Color.Transparent, MediaImmersiveBackground.copy(alpha = 0.8f))))
            .navigationBarsPadding().padding(horizontal = 8.dp).padding(top = 16.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(positionMs), Modifier.width(46.dp), color = MediaTextPrimary,
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            PlayerSeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onDragStart = onScrubStart,
                onPreview = onScrub,
                onCommit = onScrubEnd,
                onCancel = onScrubCancel,
                bufferPercent = bufferPercent,
                enabled = seekEnabled && !locked && durationMs > 0L,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            Text(formatDuration(durationMs), Modifier.width(46.dp), color = MediaTextPrimary,
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onPrevious, Modifier.weight(1f).testTag("player_btn_previous"), enabled = hasPrevious && !locked) {
                Icon(Icons.Filled.SkipPrevious, "上一条", tint = MediaTextPrimary)
            }
            TextButton(onOpenSpeed, Modifier.weight(1f), enabled = !locked, contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text("${speedLabel}×", color = MediaTextPrimary, maxLines = 1)
            }
            TextButton(onOpenScale, Modifier.weight(1f), enabled = !locked, contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text(scaleLabel, color = MediaTextPrimary, maxLines = 1)
            }
            IconButton(onToggleLock, Modifier.weight(1f)) {
                Icon(Icons.Filled.Lock, "锁定", tint = MediaTextPrimary)
            }
            IconButton(onNext, Modifier.weight(1f).testTag("player_btn_next"), enabled = hasNext && !locked) {
                Icon(Icons.Filled.SkipNext, "下一条", tint = MediaTextPrimary)
            }
            IconButton(onToggleFullscreen, Modifier.weight(1f)) {
                Icon(if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    if (isFullscreen) "退出全屏" else "全屏", tint = MediaTextPrimary)
            }
        }
    }
}
