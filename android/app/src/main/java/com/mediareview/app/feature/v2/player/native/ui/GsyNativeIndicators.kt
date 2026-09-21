package com.mediareview.app.feature.v2.player.native.ui

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.formatDuration
import com.mediareview.app.feature.v2.player.native.state.PlaybackUiMapper
import com.mediareview.app.feature.v2.player.native.state.PlaybackUiMapper.CenterOverlay
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaControlScrimSoft
import com.mediareview.app.ui.theme.MediaControlSurface
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Seek 提示数据（+00:18 / -00:12 + 当前/总时长）。 */
data class GsyNativeSeekHint(
    val deltaMs: Long,
    val positionMs: Long,
)

/**
 * 播放器中央指示器：Loading（>200ms 防闪）、Seek 提示、亮度/音量浮层、临时 2x、
 * Completed（重播/下一条）、Error（重试/返回）。
 */
@Composable
fun GsyNativeIndicators(
    overlay: CenterOverlay,
    seekHint: GsyNativeSeekHint?,
    seekDurationMs: Long,
    brightnessPct: Int?,
    volumePct: Int?,
    tempSpeedActive: Boolean,
    hasNext: Boolean,
    onRestart: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (overlay == CenterOverlay.LOADING) {
            BufferingIndicator(modifier = Modifier.align(Alignment.Center))
        }

        seekHint?.let { hint ->
            SeekHintOverlay(
                deltaMs = hint.deltaMs,
                positionMs = hint.positionMs,
                durationMs = seekDurationMs,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        volumePct?.let { pct ->
            HintBadge(
                icon = Icons.Default.VolumeUp,
                text = "$pct%",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        brightnessPct?.let { pct ->
            HintBadge(
                icon = Icons.Default.BrightnessMedium,
                text = "$pct%",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (tempSpeedActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(MediaControlScrimSoft, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("2.0x >>", style = MaterialTheme.typography.titleMedium, color = V2Colors.Accent)
            }
        }

        when (overlay) {
            CenterOverlay.COMPLETED -> CompletedOverlay(
                hasNext = hasNext,
                onRestart = onRestart,
                onNext = onNext,
                modifier = Modifier.align(Alignment.Center),
            )
            CenterOverlay.ERROR -> ErrorOverlay(
                onRetry = onRetry,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Unit
        }
    }
}

/** 缓冲 Loading：持续超过 200ms 才显示，避免几十毫秒缓冲闪圈。 */
@Composable
private fun BufferingIndicator(modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(200L)
        show = true
    }
    if (show) {
        CircularProgressIndicator(color = MediaTextPrimary, strokeWidth = 3.dp, modifier = modifier.size(44.dp))
    }
}

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
            text = "$prefix${formatDuration(abs(deltaMs))}",
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

@Composable
private fun CompletedOverlay(
    hasNext: Boolean,
    onRestart: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MediaControlScrimSoft, CircleShape)
                .clickable(onClick = onRestart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Replay, "重新播放", tint = MediaTextPrimary, modifier = Modifier.size(44.dp))
        }
        Text(
            text = "重新播放",
            style = MaterialTheme.typography.bodyMedium,
            color = MediaTextPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (hasNext) {
            TextButton(onClick = onNext) {
                Text("播放下一条", color = MediaTextPrimary)
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.ErrorOutline, "错误", tint = MediaTextPrimary, modifier = Modifier.size(48.dp))
        Text(
            text = "播放失败",
            style = MaterialTheme.typography.titleMedium,
            color = MediaTextPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
