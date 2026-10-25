package com.mediareview.app.feature.v2.review

import kotlin.math.abs

data class ReviewProgressUi(
    val position: Int,
    val total: Int,
    val reviewed: Int,
    val remaining: Int,
    val fraction: Float,
) {
    val positionLabel: String get() = "第 $position / 共 $total"
    val summaryLabel: String get() = "已批 $reviewed · 剩余 $remaining"
}

fun reviewProgress(
    absoluteIndex: Int,
    totalCount: Int,
    seenCount: Int,
    remainingCount: Int,
): ReviewProgressUi {
    val total = totalCount.coerceAtLeast(0)
    val position = if (total == 0) 0 else (absoluteIndex + 1).coerceIn(1, total)
    val reviewed = seenCount.coerceIn(0, total)
    val remaining = (if (remainingCount >= 0) remainingCount else total - reviewed).coerceIn(0, total)
    val fraction = if (total == 0) 0f else reviewed.toFloat() / total.toFloat()
    return ReviewProgressUi(position, total, reviewed, remaining, fraction)
}

enum class ReviewDecisionGesture { KEEP, DELETE }

fun reviewDecisionGesture(
    dragX: Float,
    dragY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): ReviewDecisionGesture? {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return null
    val minimum = (viewportHeight * 0.10f).coerceAtLeast(120f)
    if (abs(dragY) < minimum || abs(dragY) < abs(dragX) * 1.25f) return null
    return if (dragY < 0f) ReviewDecisionGesture.KEEP else ReviewDecisionGesture.DELETE
}

fun canGoPrevious(currentIndex: Int): Boolean = currentIndex > 0

fun canGoNext(currentIndex: Int, itemCount: Int): Boolean = currentIndex >= 0 && currentIndex < itemCount - 1

fun fastForwardIndex(currentIndex: Int, itemCount: Int, screenSize: Int = 10): Int {
    if (itemCount <= 0) return 0
    return (currentIndex + screenSize.coerceAtLeast(1)).coerceIn(0, itemCount - 1)
}

fun fastForwardHint(currentIndex: Int, itemCount: Int, screenSize: Int = 10): String? {
    if (itemCount <= 0) return null
    val target = fastForwardIndex(currentIndex, itemCount, screenSize)
    val position = target + 1
    return if (target == itemCount - 1) "已到最后一项（第 $position / 共 $itemCount）"
    else "已快进到第 $position / 共 $itemCount"
}

fun reviewResumeHint(absoluteIndex: Int, totalCount: Int): String? =
    if (absoluteIndex > 0 && totalCount > absoluteIndex) "已恢复到第 ${absoluteIndex + 1} 项" else null

fun reviewUndoTarget(lastDeleteMediaId: String?, confirmedPendingDeleteIds: Set<String>): String? =
    lastDeleteMediaId?.takeIf(confirmedPendingDeleteIds::contains)
