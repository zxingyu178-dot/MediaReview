package com.mediareview.app.feature.v2.review

import kotlinx.coroutines.delay

/** 等待当前内核从 seek 目标真实继续播放；不驱动播放时钟或修改 SDK 状态。 */
internal suspend fun awaitOwnedReviewPlaybackAdvance(
    targetMs: Long,
    stillOwned: () -> Boolean,
    isPlaying: () -> Boolean,
    positionMs: () -> Long,
    durationMs: () -> Long,
): Boolean {
    var previous: Long? = null
    var advances = 0
    while (stillOwned()) {
        val position = positionMs()
        val duration = durationMs()
        val baseline = previous
        if (baseline == null) {
            val nearTarget = kotlin.math.abs(position - targetMs) <= 500L ||
                (duration > 0 && targetMs >= duration - 250L && position < 500L)
            if (isPlaying() && nearTarget) previous = position
        } else if (isPlaying() && (position >= baseline + 20L ||
                (duration > 0 && baseline > duration * 3 / 4 && position < duration / 3))) {
            advances++
            previous = position
            if (advances >= 2) return true
        }
        delay(100L)
    }
    return false
}
