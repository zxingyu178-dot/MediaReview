package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import com.mediareview.app.feature.v2.player.native.state.GestureDirection
import com.mediareview.app.feature.v2.player.native.state.GestureDirectionLock
import com.mediareview.app.feature.v2.player.native.state.SpeedGestureChange
import com.mediareview.app.feature.v2.player.native.state.SpeedGestureState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 每个pointer序列唯一方向owner，Full和Review共同使用；内核调用由回调承接。 */
data class PlayerBodyGestureCallbacks(
    val isPlaying: () -> Boolean,
    val onTap: () -> Unit,
    val onDoubleTap: (Float) -> Unit,
    val onSpeedChange: (SpeedGestureChange) -> Unit,
    val onSeekStart: () -> Unit,
    val onSeekMove: (Float) -> Unit,
    val onSeekEnd: () -> Unit,
    val onSeekCancel: () -> Unit,
    val onVerticalStart: (Float) -> Unit,
    val onVerticalMove: (Float, Float, Float) -> Unit,
    val onVerticalEnd: (Float) -> Unit,
    val onVerticalCancel: () -> Unit,
    val onActive: (Boolean) -> Unit,
)

private class BodyTapSequence {
    var job: Job? = null
    var timeMs: Long? = null
    var position = Offset.Zero
}

@Composable
fun rememberPlayerBodyGestures(
    enabled: Boolean,
    speed: SpeedGestureState,
    callbacks: PlayerBodyGestureCallbacks,
    handoffVerticalToPager: Boolean = false,
    handoffHorizontalToPager: Boolean = false,
): Modifier {
    val latest = rememberUpdatedState(callbacks)
    val scope = rememberCoroutineScope()
    val taps = remember { BodyTapSequence() }
    val density = LocalDensity.current.density
    DisposableEffect(Unit) { onDispose { taps.job?.cancel() } }
    LaunchedEffect(enabled) {
        if (!enabled) { taps.job?.cancel(); taps.timeMs = null }
    }
    return Modifier.pointerInput(enabled, speed, density, handoffVerticalToPager, handoffHorizontalToPager) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            // Review shares the Pager hit ancestry. Initial sees horizontal/speed before its drag
            // recognizer; Full retains its existing exclusive Main-pass volume/brightness path.
            val handoffToPager = handoffVerticalToPager || handoffHorizontalToPager
            val pass = if (handoffToPager) PointerEventPass.Initial else PointerEventPass.Main
            val down = awaitFirstDown(requireUnconsumed = true, pass = pass)
            if (handoffToPager) {
                // Child controls receive Main after this ancestor's Initial pass. Respect
                // their down consumption before scheduling any body tap/seek/speed work.
                val finalDown = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                if (finalDown?.isConsumed == true) return@awaitEachGesture
            }
            val start = down.position
            val width = size.width.coerceAtLeast(1).toFloat()
            val height = size.height.coerceAtLeast(1).toFloat()
            val side = (start.x / width).coerceIn(0f, 1f)
            val direction = GestureDirectionLock(viewConfiguration.touchSlop)
            var previous = start
            var lastTime = down.uptimeMillis
            var moved = false
            var longAttempted = false
            var ended = false
            var totalDy = 0f
            latest.value.onActive(true)
            if (!handoffToPager) down.consume()
            try {
                while (true) {
                    val remaining = (SpeedGestureState.LONG_PRESS_MS - (lastTime - down.uptimeMillis)).coerceAtLeast(1L)
                    val event = if (!longAttempted && !moved && direction.direction == GestureDirection.Undecided) {
                        withTimeoutOrNull(remaining) { awaitPointerEvent(pass) }
                    } else awaitPointerEvent(pass)
                    if (event == null) {
                        longAttempted = true
                        taps.job?.cancel()
                        taps.timeMs = null
                        speed.longPress(latest.value.isPlaying())?.let {
                            direction.claimSpeed()
                            latest.value.onSpeedChange(it)
                        }
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    lastTime = change.uptimeMillis
                    if (change.isConsumed && !handoffToPager) break
                    val offset = change.position - start
                    totalDy = offset.y / height
                    if (!longAttempted && !moved && offset.getDistance() <= viewConfiguration.touchSlop &&
                        change.uptimeMillis - down.uptimeMillis >= SpeedGestureState.LONG_PRESS_MS) {
                        longAttempted = true
                        taps.job?.cancel()
                        taps.timeMs = null
                        speed.longPress(latest.value.isPlaying())?.let {
                            direction.claimSpeed()
                            latest.value.onSpeedChange(it)
                        }
                    }
                    if (offset.getDistance() > viewConfiguration.touchSlop) moved = true
                    val before = direction.direction
                    val current = direction.update(offset.x, offset.y)
                    if (before == GestureDirection.Undecided && current != before) {
                        taps.job?.cancel()
                        taps.timeMs = null
                        when (current) {
                            GestureDirection.Horizontal -> if (!handoffHorizontalToPager) latest.value.onSeekStart()
                            GestureDirection.Vertical -> latest.value.onVerticalStart(side)
                            else -> Unit
                        }
                    }
                    when (current) {
                        GestureDirection.Speed -> speed.dragUp(-offset.y / density)?.let(latest.value.onSpeedChange)
                        GestureDirection.Horizontal -> if (!handoffHorizontalToPager) latest.value.onSeekMove(offset.x / width)
                        GestureDirection.Vertical -> latest.value.onVerticalMove((change.position.y - previous.y) / height, totalDy, side)
                        GestureDirection.Undecided -> Unit
                    }
                    previous = change.position
                    val pagerOwnsDirection =
                        (handoffVerticalToPager && current == GestureDirection.Vertical) ||
                            (handoffHorizontalToPager && current == GestureDirection.Horizontal)
                    if (!pagerOwnsDirection) {
                        change.consume()
                    }
                    if (!change.pressed) {
                        when (current) {
                            GestureDirection.Speed -> latest.value.onSpeedChange(speed.release())
                            GestureDirection.Horizontal -> if (!handoffHorizontalToPager) latest.value.onSeekEnd()
                            GestureDirection.Vertical -> latest.value.onVerticalEnd(totalDy)
                            GestureDirection.Undecided -> if (!moved && !longAttempted) {
                                val previousTap = taps.timeMs
                                val sameSide = (taps.position.x < width / 2f) == (start.x < width / 2f)
                                if (previousTap != null && sameSide && change.uptimeMillis - previousTap in
                                    viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis) {
                                    taps.job?.cancel()
                                    taps.timeMs = null
                                    latest.value.onDoubleTap(side)
                                } else {
                                    taps.job?.cancel()
                                    taps.timeMs = change.uptimeMillis
                                    taps.position = start
                                    taps.job = scope.launch {
                                        delay(viewConfiguration.doubleTapTimeoutMillis)
                                        taps.timeMs = null
                                        latest.value.onTap()
                                    }
                                }
                            }
                        }
                        ended = true
                        break
                    }
                    if (handoffToPager && current == GestureDirection.Undecided) {
                        // A diagonal sequence may cross Pager's vertical slop before our 1.2x
                        // dominance threshold. Once native drag owns it, never turn it into seek.
                        val finalChange = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                        if (finalChange?.isConsumed == true) {
                            if (handoffHorizontalToPager) {
                                direction.update(viewConfiguration.touchSlop * 2f, 0f)
                            } else {
                                direction.update(0f, viewConfiguration.touchSlop * 2f)
                            }
                            taps.job?.cancel(); taps.timeMs = null
                            if (!handoffHorizontalToPager) latest.value.onVerticalStart(side)
                        }
                    }
                }
            } finally {
                if (!ended) when (direction.direction) {
                    GestureDirection.Speed -> latest.value.onSpeedChange(speed.cancel())
                    GestureDirection.Horizontal -> if (!handoffHorizontalToPager) latest.value.onSeekCancel()
                    GestureDirection.Vertical -> latest.value.onVerticalCancel()
                    GestureDirection.Undecided -> Unit
                }
                latest.value.onActive(false)
            }
        }
    }
}
