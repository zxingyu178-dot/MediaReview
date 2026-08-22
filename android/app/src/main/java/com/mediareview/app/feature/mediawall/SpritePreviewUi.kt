package com.mediareview.app.feature.mediawall

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 长按进入横向滑动预览的最小按住时长(毫秒)。 */
private const val LONG_PRESS_MS = 500L

/**
 * 长按 + 横向滑动手势:
 * - 快速点击 → onTap(图片打开查看器;视频本阶段不响应)
 * - 按住 500ms 且位移未超过触摸容差 → onLongPressStart,此后横向位移映射到 [0,1] 传给 onScrub
 * - 松手 → onScrubEnd;若滚动容器消费了事件则放弃本次手势
 */
fun Modifier.longPressScrub(
    enabled: Boolean,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
): Modifier {
    if (!enabled) return this
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downPos = down.position
            var scrubbing = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    onScrubEnd()
                    break
                }
                if (!change.pressed) {
                    // 松手:轻点 → onTap;长按预览过 → 结束预览
                    if (!scrubbing) onTap()
                    onScrubEnd()
                    break
                }
                if (change.isConsumed) {
                    // 滚动容器接管(如垂直滚动)则放弃,避免与媒体墙滚动冲突
                    onScrubEnd()
                    break
                }
                if (!scrubbing) {
                    val elapsed = change.uptimeMillis - down.uptimeMillis
                    val dist = (change.position - downPos).getDistance()
                    if (dist > viewConfiguration.touchSlop) break
                    if (elapsed >= LONG_PRESS_MS) {
                        scrubbing = true
                        onLongPressStart()
                    }
                } else {
                    change.consume()
                    val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onScrub(fraction)
                }
            }
        }
    }
}

/**
 * 从雪碧图大图中裁剪并绘制第 [index] 格。
 * 雪碧图整体为 columns×rows 网格,每格对应一个时间点。
 */
@Composable
fun SpriteTile(
    bitmap: ImageBitmap,
    index: Int,
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
) {
    val cols = columns.coerceAtLeast(1)
    val rowsV = rows.coerceAtLeast(1)
    Canvas(modifier) {
        val col = index % cols
        val row = index / cols
        val srcW = bitmap.width / cols
        val srcH = bitmap.height / rowsV
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(col * srcW, row * srcH),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
}

/** 视频卡片长按期间的雪碧图预览覆盖层。 */
@Composable
fun SpritePreviewOverlay(state: SpriteScrubState, modifier: Modifier = Modifier) {
    if (!state.scrubbing) return
    Box(modifier.background(Color.Black)) {
        when {
            state.showScrub -> {
                val manifest = state.manifest!!
                SpriteTile(
                    bitmap = state.bitmap!!,
                    index = state.tileIndex,
                    columns = manifest.columns,
                    rows = manifest.rows,
                    modifier = Modifier.fillMaxSize(),
                )
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
                Text(
                    text = formatPosition(manifest.total_duration_ms, state.fraction),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                        .background(Color(0x88000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            state.showWaiting -> {
                Text(
                    text = if (state.pending) "雪碧图生成中,请稍后再试" else "雪碧图加载中…",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private fun formatPosition(totalDurationMs: Long, fraction: Float): String {
    val ms = (totalDurationMs * fraction.coerceIn(0f, 1f)).toLong()
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
