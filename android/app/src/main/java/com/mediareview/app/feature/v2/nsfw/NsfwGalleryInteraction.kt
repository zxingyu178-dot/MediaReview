package com.mediareview.app.feature.v2.nsfw

/** 后台读取和提交操作使用不同门禁，供图库工具栏与筛选面板共用。 */
internal data class NsfwGalleryInteraction(val canChangeQuery: Boolean, val canRefresh: Boolean)

internal fun nsfwGalleryInteraction(busy: Boolean, syncing: Boolean) = NsfwGalleryInteraction(
    canChangeQuery = !busy,
    canRefresh = !busy && !syncing,
)
