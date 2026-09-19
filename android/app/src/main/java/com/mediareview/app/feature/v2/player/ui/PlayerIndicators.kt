package com.mediareview.app.feature.v2.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.formatDuration
import com.mediareview.app.feature.v2.player.PlaybackPhase
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaControlScrimSoft
import com.mediareview.app.ui.theme.MediaControlSurface
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlinx.coroutines.delay

/**
 * 播放器中央指示器：缓冲 Loading（>200ms 才显示，避免闪烁）、Seek 提示（+00:15 / -00:12）、
 * 音量 / 亮度百分比、临时 2x 徽标、Ended 重播、Error 重试。
 */
@Composable
fun PlayerIndicators(
    phase: PlaybackPhase,
    seekHintDeltaMs: Long?,
    seekHintPositionMs: Long,
    durationMs: Long,
    volumeHintPct: Int?,
    brightnessHintPct: Int?,
    tempSpeedActive: Boolean,
    onRetry: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (phase == PlaybackPhase.PREPARING || phase == PlaybackPhase.BUFFERING) {
            BufferingIndicator(
                phase = phase,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        seekHintDeltaMs?.let { delta ->
            SeekHintOverlay(
                deltaMs = delta,
                positionMs = seekHintPositionMs,
                durationMs = durationMs,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        volumeHintPct?.let { pct ->
            HintBadge(
                icon = Icons.Default.VolumeUp,
                text = "$pct%",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        brightnessHintPct?.let { pct ->
            HintBadge(
                icon = Icons.Default.BrightnessMedium,
                text = "$pct%",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (tempSpeedActive) {
            SpeedBadge(modifier = Modifier.align(Alignment.TopCenter))
        }

        when (phase) {
            PlaybackPhase.ENDED -> EndedOverlay(
                onRestart = onRestart,
                modifier = Modifier.align(Alignment.Center),
            )
            PlaybackPhase.ERROR -> ErrorOverlay(
                onRetry = onRetry,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Unit
        }
    }
}

/** 缓冲指示：持续超过 200ms 才显示 Loading，避免几十毫秒缓冲闪圈。 */
@Composable
private fun BufferingIndicator(phase: PlaybackPhase, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        show = false
        delay(200L)
        show = true
    }
    if (show) {
        CircularProgressIndicator(
            color = MediaTextPrimary,
            strokeWidth = 3.dp,
            modifier = modifier.size(44.dp),
        )
    }
}

/** Seek 提示：+00:15 / -00:12，下方为当前 / 总时长。 */
@Composable
private fun SeekHintOverlay(
    deltaMs: Long,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val prefix = if (deltaMs >= 0) "+" else "-"
    Column(
        modifier = modifier
            .background(MediaControlSurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$prefix${formatDuration(kotlin.math.abs(deltaMs))}",
            style = MaterialTheme.typography.titleLarge,
            color = if (deltaMs >= 0) V2Colors.Accent else MediaTextPrimary,
        )
        Text(
            text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MediaTextPrimary.copy(alpha = 0.7f),
        )
    }
}

/** 音量 / 亮度百分比提示。 */
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
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MediaTextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 长按临时 2x 徽标。 */
@Composable
private fun SpeedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 64.dp)
            .background(MediaControlScrimSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "2.0x",
            style = MaterialTheme.typography.titleMedium,
            color = V2Colors.Accent,
        )
    }
}

/** Ended：重新播放。 */
@Composable
private fun EndedOverlay(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MediaControlScrimSoft, CircleShape)
                .clickable(onClick = onRestart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Replay,
                "重新播放",
                tint = MediaTextPrimary,
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            text = "重新播放",
            style = MaterialTheme.typography.bodyMedium,
            color = MediaTextPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Error：错误说明 + 重试 + 返回。 */
@Composable
private fun ErrorOverlay(
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            "错误",
            tint = MediaTextPrimary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "无法播放",
            style = MaterialTheme.typography.titleMedium,
            color = MediaTextPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, "重试", tint = MediaTextPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("重试", color = MediaTextPrimary)
            }
            TextButton(onClick = onBack) {
                Text("返回", color = MediaTextSecondary)
            }
        }
    }
}
