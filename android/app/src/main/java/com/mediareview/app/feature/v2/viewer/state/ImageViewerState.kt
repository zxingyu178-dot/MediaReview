package com.mediareview.app.feature.v2.viewer.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 图片查看器页面级状态：currentIndex / uiVisible。
 * 缩放状态保持每页局部（见 ZoomableImage）。
 */
class ImageViewerState(initialIndex: Int = 0) {

    var currentIndex by mutableStateOf(initialIndex)
        private set

    var uiVisible by mutableStateOf(true)
        private set

    fun updateCurrentIndex(index: Int) {
        currentIndex = index
    }

    fun toggleUi() {
        uiVisible = !uiVisible
    }

    fun setUiVisibility(visible: Boolean) {
        uiVisible = visible
    }

}
