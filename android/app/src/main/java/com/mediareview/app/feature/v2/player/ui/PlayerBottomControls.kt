package com.mediareview.app.feature.v2.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.formatDuration
import com.mediareview.app.feature.v2.player.state.VideoScaleState
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import kotlin.math.abs

/**
 * 底部控制层：透明渐变遮罩 + 进度条（Scrubbing）+ 当前/总时间 + 倍速 + 比例 + 锁定。
 */
@Composable
fun PlayerBottomControls(
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    scaleMode: VideoScaleState.ScaleMode,
    locked: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onCycleScale: () -> Unit,
    onToggleLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(1L)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, MediaImmersiveBackground.copy(alpha = 0.7f)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    formatDuration(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextPrimary,
                )
                Slider(
                    value = positionMs.coerceIn(0L, duration).toFloat(),
                    onValueChange = { onScrub(it) },
                    onValueChangeFinished = { onScrubEnd() },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = V2Colors.Accent,
                        activeTrackColor = V2Colors.Accent,
                        inactiveTrackColor = MediaTextPrimary.copy(alpha = 0.3f),
                    ),
                )
                Text(
                    formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextPrimary.copy(alpha = 0.7f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenSpeedSheet, enabled = !locked) {
                    Text("${formatSpeed(speed)}x", color = MediaTextPrimary)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onCycleScale, enabled = !locked) {
                    Text("比例：${scaleMode.label}", color = MediaTextPrimary)
                }
                IconButton(onClick = onToggleLock) {
                    Icon(
                        if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        if (locked) "已锁定" else "锁定",
                        tint = MediaTextPrimary,
                    )
                }
            }
        }
    }
}

/** 倍速显示：整数值显示整数（2x），否则一位小数（1.5x）。 */
fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}" else speed.toString()

/** 倍速选择面板：0.5x ~ 2.0x，当前倍速高亮，选择后立即关闭。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "倍速",
            style = MaterialTheme.typography.titleMedium,
            color = MediaTextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            speeds.forEach { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(s) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${formatSpeed(s)}x",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (abs(s - current) < 0.001f) V2Colors.Accent else MediaTextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (abs(s - current) < 0.001f) {
                        Icon(
                            Icons.Default.Check,
                            "当前倍速",
                            tint = V2Colors.Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
