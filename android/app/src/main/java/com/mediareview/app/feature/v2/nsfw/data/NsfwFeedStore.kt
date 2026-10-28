package com.mediareview.app.feature.v2.nsfw.data

/** 一次 feed 合并的结果，用于"新图提示"与不打断浏览。 */
data class NsfwMergeResult(
    val addedIds: List<String>,
    val updatedIds: List<String>,
    val total: Int,
)

/**
 * 客户端 feed 队列（纯逻辑，JVM 可测）：
 *
 * - `image_id` 是唯一身份；同 ID 的元数据更新**原位替换**，绝不清空、绝不重排；
 * - 新 ID 只追加到队尾；不在当前浏览位置之前插入；
 * - 空增量不代表删除；v2 不处理 remove 事件。
 */
class NsfwFeedStore {
    private val order = mutableListOf<String>()
    private val byId = mutableMapOf<String, NsfwFeedItem>()

    fun merge(page: NsfwFeedPage): NsfwMergeResult {
        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        page.items.forEach { item ->
            val id = item.imageId
            if (id.isBlank()) return@forEach
            val existing = byId[id]
            if (existing == null) {
                byId[id] = item
                order += id
                added += id
            } else {
                if (existing != item) updated += id
                byId[id] = item
            }
        }
        return NsfwMergeResult(addedIds = added, updatedIds = updated, total = order.size)
    }

    fun snapshot(): List<NsfwFeedItem> = order.mapNotNull { byId[it] }

    fun find(imageId: String): NsfwFeedItem? = byId[imageId]

    fun indexOf(imageId: String): Int = order.indexOf(imageId)

    fun update(imageId: String, transform: (NsfwFeedItem) -> NsfwFeedItem) {
        val current = byId[imageId] ?: return
        byId[imageId] = transform(current)
    }

    fun size(): Int = order.size

    fun clear() {
        order.clear()
        byId.clear()
    }
}
