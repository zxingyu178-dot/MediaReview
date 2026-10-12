package com.mediareview.app.feature.v2.player.native.state

import kotlin.math.roundToLong

object PlayerSeekMapping {
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
