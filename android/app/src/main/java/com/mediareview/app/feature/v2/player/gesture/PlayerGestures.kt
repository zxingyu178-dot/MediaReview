package com.mediareview.app.feature.v2.player.gesture

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 播放器手势回调集合（单击 / 双击 / 长按 / 横竖拖拽）。
 */
class PlayerGestureHandlers(
    val onTap: () -> Unit = {},
    /** xFraction: 0..1，左 1/3=快退、右 1/3=快进、中央=播放暂停。 */
    val onDoubleTap: (xFraction: Float) -> Unit = {},
    val onLongPressStart: () -> Unit = {},
    val onLongPressEnd: () -> Unit = {},
    /** 横向 Seek 开始：startX=起始 x，widthPx=手势区域宽度。 */
    val onSeekStart: (startX: Float, widthPx: Float) -> Unit = { _, _ -> },
    val onSeekDrag: (deltaX: Float) -> Unit = {},
    val onSeekEnd: () -> Unit = {},
    val onSeekCancel: () -> Unit = {},
    /** 竖向（音量 / 亮度）开始：startY=起始 y，heightPx=手势区域高度。 */
    val onVerticalStart: (startY: Float, heightPx: Float) -> Unit = { _, _ -> },
    val onVerticalDrag: (deltaY: Float) -> Unit = {},
    val onVerticalEnd: () -> Unit = {},
    val onVerticalCancel: () -> Unit = {},
)

/**
 * 播放器手势修饰符（Next Player 风格）：
 *
 * - 单击 / 双击 / 长按由 [detectTapGestures] 完成，平台保证互斥（双击不会先触发单击）；
 * - 拖动由 [detectDragGestures] 完成：位移超过 slop 后进入拖动并消费事件，
 *   使 tap 层自动取消 —— 单击与拖动天然互斥；
 * - 修饰符顺序固定：先挂拖动（外层）再挂点击（内层），保证拖动优先。
 *
 * 方向路由：首次位移主导轴决定横 / 竖，之后锁定该轴。
 */
fun Modifier.playerGestures(handlers: PlayerGestureHandlers): Modifier = this
    .pointerInput(Unit) {
        var axis: Axis? = null
        var start = Offset.Zero
        var axisStarted = false
        detectDragGestures(
            onDragStart = { pos ->
                start = pos
                axis = null
                axisStarted = false
                handlers.onLongPressEnd()
            },
            onDrag = { change, amount ->
                axis = axis ?: if (abs(amount.y) > abs(amount.x)) Axis.VERTICAL else Axis.HORIZONTAL
                when (axis) {
                    Axis.VERTICAL -> {
                        if (!axisStarted) {
                            axisStarted = true
                            handlers.onVerticalStart(start.y, size.height.toFloat())
                        }
                        handlers.onVerticalDrag(amount.y)
                    }
                    Axis.HORIZONTAL -> {
                        if (!axisStarted) {
                            axisStarted = true
                            handlers.onSeekStart(start.x, size.width.toFloat())
                        }
                        handlers.onSeekDrag(amount.x)
                    }
                    null -> Unit
                }
                change.consume()
            },
            onDragEnd = {
                when (axis) {
                    Axis.VERTICAL -> handlers.onVerticalEnd()
                    Axis.HORIZONTAL -> handlers.onSeekEnd()
                    null -> Unit
                }
                axis = null
                axisStarted = false
            },
            onDragCancel = {
                when (axis) {
                    Axis.VERTICAL -> handlers.onVerticalCancel()
                    Axis.HORIZONTAL -> handlers.onSeekCancel()
                    null -> Unit
                }
                axis = null
                axisStarted = false
            },
        )
    }
    .pointerInput(Unit) {
        detectTapGestures(
            // onPress 兜底结束长按：长按后直接松手（未触发拖拽）也能恢复原倍速；
            // 拖拽路径由 onDragStart → onLongPressEnd 处理。
            onPress = { _ ->
                // 长按后直接松手（未触发拖拽）时兜底恢复原倍速；拖拽路径由 onDragStart → onLongPressEnd 处理。
                tryAwaitRelease()
                handlers.onLongPressEnd()
            },
            onTap = { handlers.onTap() },
            onDoubleTap = { handlers.onDoubleTap(it.x / size.width.coerceAtLeast(1).toFloat()) },
            onLongPress = { handlers.onLongPressStart() },
        )
    }

private enum class Axis { VERTICAL, HORIZONTAL }
