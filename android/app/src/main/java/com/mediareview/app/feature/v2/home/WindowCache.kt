package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SortSpec

/** 仅保存已加载分页窗口，绝不主动读取全库。 */
data class WindowKey(
    val sourceGeneration: Long,
    val folderId: String?,
    val sort: V2SortSpec,
    val includeDescendants: Boolean = true,
)

data class CachedWindow(
    val items: List<V2Media>,
    val page: Int,
    val total: Int,
    val sort: V2SortSpec,
    val anchor: HomeGridAnchor? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceGeneration: Long,
) {
    val hasMore: Boolean get() = page * 50 < total
}

open class WindowCache {
    private val entries = LinkedHashMap<WindowKey, CachedWindow>(8, 0.75f, true)
    @Synchronized fun get(key: WindowKey): CachedWindow? = entries[key]
    @Synchronized fun put(key: WindowKey, window: CachedWindow) {
        require(key.sourceGeneration == window.sourceGeneration && key.sort == window.sort)
        entries[key] = window.copy(items = window.items.toList())
        while (entries.size > 8) entries.remove(entries.keys.first())
    }
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun patchFavorite(id: String, favorite: Boolean) {
        entries.replaceAll { _, window -> window.copy(items = window.items.map {
            if (it.id == id) it.copy(isFavorite = favorite) else it
        }) }
    }
}

class AlbumWindowCache : WindowCache()
class BrowseWindowCache : WindowCache()
