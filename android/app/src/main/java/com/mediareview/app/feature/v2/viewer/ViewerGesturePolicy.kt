package com.mediareview.app.feature.v2.viewer

import kotlin.math.max

object ViewerGesturePolicy {
    private const val RestingScaleTolerance = 0.001f
    private const val DistanceFraction = 0.24f
    private const val MinimumDistance = 120f
    private const val FlingVelocity = 1_200f

    fun shouldDismiss(scale: Float, dragY: Float, velocityY: Float, viewportHeight: Float): Boolean {
        if (scale > 1f + RestingScaleTolerance || dragY <= 0f) return false
        val threshold = max(MinimumDistance, viewportHeight * DistanceFraction)
        return dragY >= threshold || (dragY >= MinimumDistance * 0.5f && velocityY >= FlingVelocity)
    }

    fun dismissProgress(dragY: Float, viewportHeight: Float): Float =
        (dragY / (viewportHeight * 0.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

fun safeViewerRelativePath(path: String, preferredRelative: String): String {
    fun normalized(value: String) = value.trim().replace('\\', '/')
    fun isAbsolute(value: String): Boolean {
        val text = normalized(value)
        return text.startsWith("/") || text.startsWith("//") || Regex("^[A-Za-z]:/").containsMatchIn(text)
    }
    val preferred = normalized(preferredRelative)
    if (preferred.isNotBlank() && !isAbsolute(preferred)) return preferred
    val candidate = normalized(path)
    return candidate.takeIf { it.isNotBlank() && !isAbsolute(it) } ?: "未提供"
}
