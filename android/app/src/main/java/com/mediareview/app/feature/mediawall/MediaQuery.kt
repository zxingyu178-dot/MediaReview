package com.mediareview.app.feature.mediawall

import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder

/**
 * 媒体墙查询的不可变快照。
 *
 * 每次任何字段变化都必须产生新的 [MediaQuery] 实例;ViewModel 以它为单位创建新 Pager,
 * 旧查询的分页流随 flatMapLatest 取消,cachedIn(viewModelScope) 是唯一保留的页缓存。
 * [folderId] 是服务器派生的不透明 ID(GET /media/folders),绝不是文件路径。
 */
data class MediaQuery(
    val libraryId: String? = null,
    val type: MediaTypeFilter = MediaTypeFilter.All,
    val sortBy: SortField = SortField.Name,
    val sortOrder: SortOrder = SortOrder.Asc,
    val search: String? = null,
    val excludeFavorites: Boolean = false,
    val folderId: String? = null,
)
