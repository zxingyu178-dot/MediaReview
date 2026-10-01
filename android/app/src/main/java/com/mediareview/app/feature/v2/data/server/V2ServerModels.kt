package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2TypeFilter

/**
 * V2 排序/筛选 → Server 查询参数（`GET /api/v1/media`）的唯一映射点。
 *
 * 参数名与取值必须与 Server 合同一致：
 * sort_by ∈ name / created / size / duration / resolution / random；
 * sort_order ∈ asc / desc；media_type ∈ video / image（ALL 不下发该参数）。
 */
internal object V2ServerWire {

    fun sortBy(field: V2SortField): String = when (field) {
        V2SortField.RECENT -> "created"
        V2SortField.NAME -> "name"
        V2SortField.DURATION -> "duration"
        V2SortField.SIZE -> "size"
    }

    fun sortOrder(order: V2SortOrder): String = when (order) {
        V2SortOrder.ASC -> "asc"
        V2SortOrder.DESC -> "desc"
    }

    fun mediaType(filter: V2TypeFilter): String? = when (filter) {
        V2TypeFilter.ALL -> null
        V2TypeFilter.VIDEO -> "video"
        V2TypeFilter.IMAGE -> "image"
    }

    fun sortBy(spec: V2SortSpec): String = sortBy(spec.field)

    fun sortOrder(spec: V2SortSpec): String = sortOrder(spec.order)

    fun mediaType(spec: V2SortSpec): String? = mediaType(spec.typeFilter)
}

/**
 * Server 资源缓存（Stage 8A：Server 资源映射必须留在 Repository / Resource Cache 内）。
 *
 * 只缓存"已经映射过"的媒体与其封面 / 原图绝对 URL：
 * - V2Media 的 Demo 字段（assetPath / thumbPath / sprite*）在 Server 模式下保持空字符串，
 *   绝不把 `http://...` 塞进 Demo 语义字段；
 * - UI 通过 Repository 的 coverUri() / imageUri() / mediaById() 取值，不感知来源；
 * - LRU 上限防止长列表 / 大库把内存撑爆。
 */
class V2ServerResourceCache(private val maxEntries: Int = 2048) {

    data class Entry(
        val media: V2Media,
        val coverUrl: String?,
        val originalUrl: String?,
    )

    private val entries = object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun put(entry: Entry) {
        entries[entry.media.id] = entry
    }

    @Synchronized
    fun putAll(list: List<Entry>) {
        list.forEach { entries[it.media.id] = it }
    }

    @Synchronized
    fun media(id: String): V2Media? = entries[id]?.media

    @Synchronized
    fun coverUrl(id: String): String? = entries[id]?.coverUrl

    @Synchronized
    fun originalUrl(id: String): String? = entries[id]?.originalUrl

    @Synchronized
    fun updateFavorite(id: String, favorite: Boolean) {
        val current = entries[id] ?: return
        entries[id] = current.copy(media = current.media.copy(isFavorite = favorite))
    }

    /**
     * 移除已删除的媒体(Stage 8C §24):最终删除 success/missing 后,
     * 缓存不得继续命中已不存在的媒体,否则 mediaById / coverUri 返回陈旧数据。
     */
    @Synchronized
    fun removeAll(ids: Collection<String>) {
        ids.forEach { entries.remove(it) }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}