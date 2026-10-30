package com.mediareview.app.feature.v2.player.native.state

import kotlin.math.abs

/** 松手后保留目标，直到异步内核确认；取消/切源时由调用方明确清理。 */
class SeekPositionLatch {
    var pendingTarget: Long? = null
        private set
    fun commit(target: Long) { pendingTarget = target.coerceAtLeast(0) }
    fun clear() { pendingTarget = null }
    fun display(kernelPosition: Long): Long {
        val target = pendingTarget ?: return kernelPosition
        if (abs(kernelPosition - target) <= 750) { clear(); return kernelPosition }
        return target
    }
}
