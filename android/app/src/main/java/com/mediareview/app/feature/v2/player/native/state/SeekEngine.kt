package com.mediareview.app.feature.v2.player.native.state

import kotlin.math.roundToLong

object PlayerSeekMapping {
    /** 横滑整屏仅移动时长的 2%，限制在 30 秒至 3 分钟。 */
    fun horizontalRange(durationMs: Long): Long =
        (durationMs.coerceAtLeast(0L).toDouble() * .02).coerceIn(30_000.0, 180_000.0).toLong()

    fun horizontalDelta(dx: Float, width: Float, durationMs: Long): Long {
        if (!dx.isFinite() || !width.isFinite() || width <= 0f || durationMs <= 0L) return 0L
        return horizontalFractionDelta(dx.toDouble() / width, durationMs)
    }

    /** PlayerBodyGestures 已把累计 dx / width 作为 fraction 传入。 */
    fun horizontalFractionDelta(fraction: Float, durationMs: Long): Long =
        horizontalFractionDelta(fraction.toDouble(), durationMs)

    private fun horizontalFractionDelta(fraction: Double, durationMs: Long): Long {
        if (!fraction.isFinite() || durationMs <= 0L) return 0L
        return (fraction * horizontalRange(durationMs)).toLong()
    }

    fun horizontalTarget(startMs: Long, fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val start = startMs.coerceIn(0L, durationMs)
        val delta = horizontalFractionDelta(fraction, durationMs)
        return if (delta >= 0L) start + delta.coerceAtMost(durationMs - start)
        else start + delta.coerceAtLeast(-start)
    }

    fun position(fraction: Float, durationMs: Long): Long {
        if (!fraction.isFinite() || durationMs <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f).toDouble() * durationMs).roundToLong().coerceIn(0L, durationMs)
    }

    fun fromTouch(x: Float, width: Float, durationMs: Long): Long =
        if (!width.isFinite() || width <= 0f) 0L else position(x / width, durationMs)
}

data class SeekCommit(val targetMs: Long, val resumePlaying: Boolean)

/** 拖动不包含播放器调用；只有 commit 输出一次真正 seek 指令，cancel 只恢复播放态。 */
class SeekDragEngine {
    private var durationMs = 0L
    private var wasPlaying = false
    var active = false
        private set
    var previewMs = 0L
        private set

    /** 返回是否需要暂停，重复 down 不替换原先播放状态。 */
    fun begin(positionMs: Long, durationMs: Long, playing: Boolean): Boolean {
        if (active || durationMs <= 0L) return false
        this.durationMs = durationMs
        wasPlaying = playing
        previewMs = positionMs.coerceIn(0L, durationMs)
        active = true
        return playing
    }

    fun preview(targetMs: Long) {
        if (active) previewMs = targetMs.coerceIn(0L, durationMs)
    }

    fun commit(): SeekCommit? {
        if (!active) return null
        active = false
        return SeekCommit(previewMs, wasPlaying)
    }

    fun cancel(): Boolean {
        val resume = active && wasPlaying
        active = false
        return resume
    }
}
