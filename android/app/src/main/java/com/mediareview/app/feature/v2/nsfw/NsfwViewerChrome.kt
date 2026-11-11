package com.mediareview.app.feature.v2.nsfw

/** 缩放显隐带容差；手动隐藏的信息不会在缩回适屏时被强行打开。 */
internal data class NsfwViewerChrome(val manuallyVisible: Boolean = true, val zoomed: Boolean = false) {
    val visible: Boolean get() = manuallyVisible && !zoomed
    fun scaleChanged(scale: Float): NsfwViewerChrome = copy(zoomed = when {
        scale <= 1.01f -> false
        scale >= 1.05f -> true
        else -> zoomed
    })
    fun toggle(): NsfwViewerChrome = copy(manuallyVisible = !manuallyVisible)
}
