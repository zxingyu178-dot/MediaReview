package com.mediareview.app.feature.v2.viewer.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 图片查看器页面级状态：currentIndex / uiVisible / pendingDeleteIds。
 * 缩放状态保持每页局部（见 ZoomableImage）。
 */
class ImageViewerState(initialIndex: Int = 0) {

    var currentIndex by mutableStateOf(initialIndex)
        private set

    var uiVisible by mutableStateOf(true)
        private set

    /** 待删除标记（会话内状态；未来正式接 Server 时映射到真实 Delete Queue）。 */
    var pendingDeleteIds: Set<String> by mutableStateOf(emptySet())
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

    fun togglePendingDelete(mediaId: String) {
        pendingDeleteIds = if (mediaId in pendingDeleteIds) {
            pendingDeleteIds - mediaId
        } else {
            pendingDeleteIds + mediaId
        }
    }

    fun isPendingDelete(mediaId: String): Boolean = mediaId in pendingDeleteIds
}
