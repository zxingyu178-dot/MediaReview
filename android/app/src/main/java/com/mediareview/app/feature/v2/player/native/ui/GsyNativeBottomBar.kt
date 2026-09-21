package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary

/**
 * 底部控制层：渐变遮罩 + 进度条（拖动本地预览、松手提交）+ 时间 + 倍速/比例/锁定/全屏。
 * 进度区分：已播放（强调色）/ 已缓冲（半透明）/ 未缓冲（暗色）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsyNativeBottomBar(
    positionMs: Long,
    durationMs: Long,
    bufferPercent: Int,
    speedLabel: String,
    scaleLabel: String,
    locked: Boolean,
    isFullscreen: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenScale: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleFullscreen: () -> Unit,
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
                    track = { sliderState ->
                        Box(Modifier.fillMaxWidth()) {
                            val total = (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                                .coerceAtLeast(1f)
                            val bufFrac = (bufferPercent.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(bufFrac)
                                    .background(MediaTextPrimary.copy(alpha = 0.22f)),
                            )
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                )
                Text(
                    formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextPrimary.copy(alpha = 0.7f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenSpeed, enabled = !locked) {
                    Text("${speedLabel}x", color = MediaTextPrimary)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenScale, enabled = !locked) {
                    Text(scaleLabel, color = MediaTextPrimary)
                }
                IconButton(onClick = onToggleLock) {
                    Icon(
                        if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        if (locked) "已锁定" else "锁定",
                        tint = MediaTextPrimary,
                    )
                }
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        if (isFullscreen) "退出全屏" else "全屏",
                        tint = MediaTextPrimary,
                    )
                }
            }
        }
    }
}
