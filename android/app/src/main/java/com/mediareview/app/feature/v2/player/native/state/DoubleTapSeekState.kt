package com.mediareview.app.feature.v2.player.native.state

data class SeekTap(val targetMs: Long, val accumulatedDeltaMs: Long)

/** 左右各半屏；同侧800ms内双击反馈累积，基准位置固定，不重复叠加内核已seek位置。 */
class DoubleTapSeekState {
    private var lastTapMs: Long? = null
    private var side = 0
    private var anchorMs = 0L
    private var deltaMs = 0L

    fun tap(xFraction: Float, nowMs: Long, positionMs: Long, durationMs: Long): SeekTap {
        val nextSide = if (xFraction < .5f) -1 else 1
        val previous = lastTapMs
        if (previous == null || nextSide != side || nowMs - previous !in 0L..800L) {
            anchorMs = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
            deltaMs = 0L
        }
        side = nextSide
        lastTapMs = nowMs
        deltaMs += side * 10_000L
        return SeekTap((anchorMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L)), deltaMs)
    }

    fun reset() { lastTapMs = null; side = 0; anchorMs = 0L; deltaMs = 0L }
}
