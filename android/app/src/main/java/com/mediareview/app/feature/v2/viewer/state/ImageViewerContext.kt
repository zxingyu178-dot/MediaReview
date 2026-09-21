package com.mediareview.app.feature.v2.viewer.state

import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder

/**
 * 图片查看器上下文：只包含 IMAGE，currentIndex 在过滤后按 initialMediaId 重新定位。
 */
data class ImageViewerContext(
    val mediaIds: List<String>,
    val initialIndex: Int,
    val sourceFolderId: String?,
    val sortField: V2SortField,
    val sortOrder: V2SortOrder,
) {
    init {
        require(initialIndex >= 0) { "initialIndex 不能为负" }
    }

    val size: Int get() = mediaIds.size

    fun idAt(index: Int): String? = mediaIds.getOrNull(index)

    companion object {
        /**
         * 从完整队列构建：仅保留图片，并按 initialMediaId 重算索引。
         * initialMediaId 不存在（或队列无图片）时安全 fallback 到 0。
         */
        fun build(
            allIds: List<String>,
            isImage: (String) -> Boolean,
            initialMediaId: String?,
            sourceFolderId: String?,
            sortField: V2SortField,
            sortOrder: V2SortOrder,
        ): ImageViewerContext {
            val images = allIds.filter { isImage(it) }
            val rawIndex = initialMediaId?.let { images.indexOf(it) } ?: 0
            return ImageViewerContext(
                mediaIds = images,
                initialIndex = if (rawIndex >= 0) rawIndex else 0,
                sourceFolderId = sourceFolderId,
                sortField = sortField,
                sortOrder = sortOrder,
            )
        }
    }
}
