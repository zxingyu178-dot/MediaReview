package com.mediareview.app.feature.v2.review

/**
 * 批阅会话快照：进入 Review 时的队列与进度，用于区分两种返回语义——
 * - Review → 完整播放器 → Back：恢复本会话（不跳回第 1 条）；
 * - 离开 Review Tab 再进入：重新检查 Repository 建立最新队列（[V2ReviewViewModel.enterReview]）。
 */
data class ReviewSession(
    val queueIds: List<String>,
    val currentMediaId: String?,
    val reviewedIds: Set<String>,
    val pendingDeleteIds: Set<String>,
    val sessionStarted: Long,
)