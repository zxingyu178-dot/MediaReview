package com.mediareview.app.feature.v2.player

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

private const val DOUBLE_TAP_MS = 300L
private var lastTapTime = 0L

/**
 * Next Player 风格播放器手势：
 * - 单击：回调（延迟 300ms 区分双击）
 * - 双击：按 x 位置回调（左 1/3=快退、右 1/3=快进、中=播放暂停）
 * - 左半屏上下滑：亮度；右半屏上下滑：音量
 * - 横向拖动：Seek（start/delta/end 回调）
 */
private suspend fun PointerInputScope.detectPlayerGesture(
    onTap: () -> Unit,
    onDoubleTap: (xFraction: Float) -> Unit,
    onVerticalDrag: (deltaY: Float, isLeftHalf: Boolean) -> Unit,
    onSeekDragStart: () -> Unit,
    onSeekDrag: (deltaX: Float) -> Unit,
    onSeekDragEnd: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downPos = down.position
        var lastPos = downPos
        var dragged = false
        var dragMode = 0 // 0 none, 1 vertical, 2 horizontal

        val now = SystemClock.uptimeMillis()
        val isDouble = now - lastTapTime < DOUBLE_TAP_MS
        lastTapTime = now

        if (isDouble) {
            // 双击：触发后消费到手指抬起
            onDoubleTap((downPos.x / size.width).coerceIn(0f, 1f))
            lastTapTime = 0L
            do {
                val ev = awaitPointerEvent()
            } while (ev.changes.any { it.pressed })
            return@awaitEachGesture
        }

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue

            if (change.pressed) {
                if (change.positionChanged()) {
                    val dx = change.position.x - lastPos.x
                    val dy = change.position.y - lastPos.y
                    if (dragMode == 0) {
                        if (abs(dx) > slop || abs(dy) > slop) {
                            dragged = true
                            dragMode = if (abs(dy) > abs(dx)) 1 else 2
                            if (dragMode == 2) {
                                onSeekDragStart()
                            }
                        }
                    } else if (dragMode == 1) {
                        onVerticalDrag(dy, downPos.x < size.width / 2f)
                        change.consume()
                    } else if (dragMode == 2) {
                        onSeekDrag(change.position.x - lastPos.x)
                        change.consume()
                    }
                }
                lastPos = change.position
            } else {
                if (dragMode == 2) onSeekDragEnd()
                if (dragMode == 0 && !dragged) {
                    onTap()
                }
                break
            }
        }
    }
}

/** 便捷扩展：挂到播放器手势层。 */
fun Modifier.playerGestures(
    onTap: () -> Unit,
    onDoubleTap: (Float) -> Unit,
    onVerticalDrag: (Float, Boolean) -> Unit,
    onSeekDragStart: () -> Unit,
    onSeekDrag: (Float) -> Unit,
    onSeekDragEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    detectPlayerGesture(onTap, onDoubleTap, onVerticalDrag, onSeekDragStart, onSeekDrag, onSeekDragEnd)
}
