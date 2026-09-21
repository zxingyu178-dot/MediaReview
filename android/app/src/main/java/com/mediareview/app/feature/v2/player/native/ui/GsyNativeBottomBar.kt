package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.formatDuration
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary

/**
 * 底部控制层（Stage 2.2.1 布局修复版）。
 *
 * 硬性布局规则：
 * - 整体 wrap content 高度，由调用方在父 Box 中以 Alignment.BottomCenter 锚定；
 * - 内部禁止使用 fillMaxHeight()/fillMaxSize() 决定轨道高度（Slider track 槽位高度约束
 *   为松约束，fillMaxHeight 会导致控制层异常扩张，进度条跑到屏幕中央）；
 * - 明确两行：第一行 时间/Slider/时间，第二行 倍速/比例/锁定/全屏；
 * - 进度轨道固定 4dp、thumb 正常 12dp / 拖动 16dp，全部为固定 dp，绝不随父约束伸缩；
 * - navigationBarsPadding 保证不与导航栏重叠。
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("player_bottom_bar")
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        MediaImmersiveBackground.copy(alpha = 0.42f),
                        MediaImmersiveBackground.copy(alpha = 0.72f),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(top = 24.dp, bottom = 4.dp),
        ) {
            // ---------- 第一行：当前时间 / 进度条 / 总时长 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDuration(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(46.dp),
                )

                // 进度条区域：固定 36dp 行高。
                // 底层：4dp 缓冲条（固定高度，按缓冲比例宽度）。
                // 上层：Material3 Slider，自定义 4dp 轨道 + 12/16dp 圆形 thumb；
                // inactive 轨道透明，露出底层缓冲条与黑底。
                // 任何子元素都禁止 fillMaxHeight/fillMaxSize 决定轨道高度。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .height(36.dp),
                ) {
                    // 全宽暗轨道：保证未缓冲 / 未播放段在黑底上始终可见
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                MediaTextPrimary.copy(alpha = 0.18f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    val bufFrac = (bufferPercent.coerceIn(0, 100) / 100f)
                        .coerceIn(0f, 1f)
                    if (bufFrac > 0.01f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth(bufFrac)
                                .height(4.dp)
                                .background(
                                    MediaTextPrimary.copy(alpha = 0.34f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                    Slider(
                        value = positionMs.coerceIn(0L, duration).toFloat(),
                        onValueChange = { onScrub(it) },
                        onValueChangeFinished = { onScrubEnd() },
                        valueRange = 0f..duration.toFloat(),
                        interactionSource = interaction,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = V2Colors.Accent,
                            activeTrackColor = V2Colors.Accent,
                            inactiveTrackColor = Color.Transparent,
                        ),
                        thumb = {
                            // 正常 12dp 小圆点，拖动时 16dp，均为圆形。
                            val thumbSize = if (pressed) 16.dp else 12.dp
                            Box(
                                modifier = Modifier
                                    .size(thumbSize)
                                    .background(V2Colors.Accent, CircleShape),
                            )
                        },
                        track = { sliderState ->
                            val range = sliderState.valueRange
                            val span = (range.endInclusive - range.start).coerceAtLeast(1f)
                            val fraction =
                                ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
                            // 轨道触摸容器固定高度，内部 4dp 条垂直居中，不随槽位约束伸缩。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.Transparent),
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .fillMaxWidth(fraction)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(V2Colors.Accent),
                                )
                            }
                        },
                    )
                }
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextPrimary.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(46.dp),
                )
            }

            // ---------- 第二行：倍速 | 比例 / 锁定 / 全屏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
