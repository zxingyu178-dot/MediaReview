package com.mediareview.app.feature.v2.model

/** 搜索/收藏在点击时冻结已加载同类型窗口，不读取首页筛选。 */
internal fun buildBrowseContext(items: List<V2Media>, mediaId: String): V2ContextQueue? {
    val target = items.firstOrNull { it.id == mediaId } ?: return null
    val window = items.filter { it.type == target.type }.distinctBy { it.id }
    return V2ContextQueue(
        folderId = null,
        sortField = V2SortField.RECENT,
        sortOrder = V2SortOrder.DESC,
        typeFilter = if (target.isVideo) V2TypeFilter.VIDEO else V2TypeFilter.IMAGE,
        mediaIds = window.map { it.id },
        currentIndex = window.indexOfFirst { it.id == mediaId },
    )
}
