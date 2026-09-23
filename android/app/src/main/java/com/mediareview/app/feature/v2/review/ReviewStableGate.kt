package com.mediareview.app.feature.v2.review

/**
 * 批阅页"稳定停留"判定（纯逻辑，可 JVM 单测）。
 *
 * 语义：同一页无滚动连续停留 >= [stableMs] 才给出"可批阅"信号；
 * 任何滚动开始或换页都会取消等待，防止"已经离开该页却仍被标记"。
 *
 * 调用方式：每次 `settledPage` / `isScrollInProgress` 变化时调用 [onEvent]，
 * 传入虚拟时间戳（UI 用 SystemClock.uptimeMillis，测试用构造出的时间）。
 * 返回非 null (= 目标页) 才允许 [ReviewStableDecider.markReviewed][V2ReviewViewModel.markReviewed]。
 */
class ReviewStableGate(
    private val stableMs: Long = REVIEW_STABLE_MS_DEFAULT,
) {

    private var baseline: Baseline? = null

    /**
     * @param page 当前停稳页（-1 表示无页面/空队列）。
     * @param scrollInProgress 当前是否仍在滚动。
     * @param nowMs 当前时间戳（单调时钟即可，只用于差值比较）。
     * @return 稳定已达标的页（page），否则 null。
     */
    fun onEvent(page: Int, scrollInProgress: Boolean, nowMs: Long): Int? {
        return if (scrollInProgress || page < 0) {
            // 任何滚动/无效页：立即取消等待
            baseline = null
            null
        } else {
            val b = baseline
            if (b == null || b.page != page) {
                baseline = Baseline(page, nowMs)
                null
            } else if (nowMs - b.startMs >= stableMs) {
                baseline = null
                page
            } else {
                null
            }
        }
    }

    /** 清除当前等待状态（标记完成后调用，避免同一停留周期重复触发）。 */
    fun reset() { baseline = null }

    /** 当前等待目标页（无等待时 -1，供测试/调试）。 */
    fun pendingPage(): Int = baseline?.page ?: -1

    private class Baseline(val page: Int, val startMs: Long)
}

/** 批阅页稳定停留时长（与规格一致：约 480ms）。 */
const val REVIEW_STABLE_MS_DEFAULT: Long = 480L