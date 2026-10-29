package com.mediareview.app.feature.v2.nsfw.data

/** 一次 feed 合并的结果，用于"新图提示"与不打断浏览。 */
data class NsfwMergeResult(
    val addedIds: List<String>,
    val updatedIds: List<String>,
    val total: Int,
    val removedIds: List<String> = emptyList(),
    val ignoredStaleIds: List<String> = emptyList(),
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
        val removed = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        page.items.forEach { item ->
            val id = item.imageId
            if (id.isBlank()) return@forEach
            if (item.event == NsfwFeedEvents.REMOVE) {
                // REMOVE 是队列 tombstone：只从客户端队列移除，不删除任何文件。
                if (remove(id)) removed += id
                return@forEach
            }
            val existing = byId[id]
            if (existing == null) {
                byId[id] = item
                order += id
                added += id
            } else {
                when (revisionCompare(item.revision, existing.revision)) {
                    in 1..Int.MAX_VALUE -> {
                        byId[id] = item
                        updated += id
                    }
                    // 同版本重复回放幂等；旧版本绝不覆盖新状态
                    else -> ignored += id
                }
            }
        }
        return NsfwMergeResult(
            addedIds = added,
            updatedIds = updated,
            total = order.size,
            removedIds = removed,
            ignoredStaleIds = ignored,
        )
    }

    fun snapshot(): List<NsfwFeedItem> = order.mapNotNull { byId[it] }

    fun find(imageId: String): NsfwFeedItem? = byId[imageId]

    fun indexOf(imageId: String): Int = order.indexOf(imageId)

    fun update(imageId: String, transform: (NsfwFeedItem) -> NsfwFeedItem) {
        val current = byId[imageId] ?: return
        byId[imageId] = transform(current)
    }

    /**
     * 审核结果只允许按 revision 前进；旧响应/重复事件不得覆盖较新状态。
     * 返回 true 表示本次写入生效。
     */
    fun applyDecision(
        imageId: String,
        status: String,
        favorite: Boolean,
        updatedAt: String,
        revision: String,
    ): Boolean {
        val current = byId[imageId] ?: return false
        if (revisionCompare(revision, current.revision) <= 0) return false
        byId[imageId] = current.copy(
            status = status,
            isFavorite = favorite,
            updatedAt = updatedAt,
            revision = revision,
        )
        return true
    }

    fun remove(imageId: String): Boolean {
        val removed = byId.remove(imageId) != null
        if (removed) order.remove(imageId)
        return removed
    }

    fun size(): Int = order.size

    fun clear() {
        order.clear()
        byId.clear()
    }

    /** revision 是服务端单调递增的十进制字符串；无法解析时退化为字典序（仍保持确定性）。 */
    private fun revisionCompare(left: String, right: String): Int {
        val l = left.toLongOrNull()
        val r = right.toLongOrNull()
        return if (l != null && r != null) l.compareTo(r) else left.compareTo(right)
    }
}
