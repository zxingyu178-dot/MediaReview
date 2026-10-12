package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.player.native.state.PlayerSeekMapping
import com.mediareview.app.feature.v2.ui.V2Colors

/** Full / Review 共享：32dp触控区与细轨道独立，pointer移动只输出预览，松手仅一次提交。 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    onDragStart: () -> Unit,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    bufferPercent: Int = 0,
    trackHeight: Dp = 4.dp,
    enabled: Boolean = true,
    tag: String = "player_seek_bar",
) {
    val start by rememberUpdatedState(onDragStart)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val fraction = if (durationMs > 0) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .testTag(tag)
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled && durationMs > 0L) setProgress { target ->
                    start()
                    preview(PlayerSeekMapping.position(target, durationMs))
                    commit()
                    true
                }
            }
            .pointerInput(enabled, durationMs) {
                if (!enabled || durationMs <= 0L) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var released = false
                    start()
                    try {
                        preview(PlayerSeekMapping.fromTouch(down.position.x, size.width.toFloat(), durationMs))
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            // 取消/外部接管不提交seek；多指不能替代最初的拖动指针。
                            if (pointer.isConsumed) break
                            if (!pointer.pressed) {
                                preview(PlayerSeekMapping.fromTouch(pointer.position.x, size.width.toFloat(), durationMs))
                                pointer.consume()
                                released = true
                                commit()
                                break
                            }
                            preview(PlayerSeekMapping.fromTouch(pointer.position.x, size.width.toFloat(), durationMs))
                            pointer.consume()
                        }
                    } finally {
                        if (!released) cancel()
                    }
                }
            },
    ) {
        val y = size.height / 2f
        val thickness = trackHeight.toPx()
        drawLine(Color.White.copy(alpha = .2f), Offset(0f, y), Offset(size.width, y), thickness, StrokeCap.Round)
        val buffered = bufferPercent.coerceIn(0, 100) / 100f
        if (buffered > 0f) drawLine(Color.White.copy(alpha = .35f), Offset(0f, y), Offset(size.width * buffered, y), thickness, StrokeCap.Round)
        if (fraction > 0f) drawLine(V2Colors.Accent, Offset(0f, y), Offset(size.width * fraction, y), thickness, StrokeCap.Round)
        drawCircle(V2Colors.Accent, radius = 4.dp.toPx(), center = Offset(size.width * fraction, y))
    }
}
