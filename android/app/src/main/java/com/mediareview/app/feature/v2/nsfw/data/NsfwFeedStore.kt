package com.mediareview.app.feature.v2.nsfw.data

/** 一次 feed 合并的结果，用于"新图提示"与不打断浏览。 */
data class NsfwMergeResult(
    val addedIds: List<String>,
    val updatedIds: List<String>,
    val total: Int,
    val removedIds: List<String> = emptyList(),
    val ignoredStaleIds: List<String> = emptyList(),
    val rejectedIds: List<String> = emptyList(),
)

/**
 * 客户端 feed 队列（纯逻辑，JVM 可测）：
 *
 * - `image_id` 是唯一身份；同 ID 的元数据更新**原位替换**，绝不清空、绝不重排；
 * - 新 ID 只追加到队尾；不在当前浏览位置之前插入；
 * - 空增量（has_more=false、items 空）只表示"本轮无变化"，删除只通过 REMOVE 事件表达；
 * - REMOVE 是队列 tombstone：从客户端队列移除，不删除文件；其 revision 参与顺序校验，
 *   旧 UPSERT 不得复活已移除图片，旧 REMOVE 不得删除较新图片；
 * - revision 必须是十进制非负整数（可超过 Long 上限），非法 revision 与未知 event 一律拒绝。
 */
class NsfwFeedStore {
    private val order = mutableListOf<String>()
    private val byId = mutableMapOf<String, NsfwFeedItem>()
    private val tombstones = mutableMapOf<String, String>()

    fun merge(page: NsfwFeedPage): NsfwMergeResult {
        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        page.items.forEach { item ->
            val id = item.imageId
            if (id.isBlank()) return@forEach
            val revision = normalizedRevision(item.revision)
            if (revision == null) {
                rejected += id
                return@forEach
            }
            when (item.event) {
                NsfwFeedEvents.UPSERT -> {
                    val tombstone = tombstones[id]
                    if (tombstone != null && compareRevision(revision, tombstone) <= 0) {
                        // 已移除的图片只能由更新的 revision 复活
                        ignored += id
                        return@forEach
                    }
                    if (tombstone != null) tombstones.remove(id)
                    val existing = byId[id]
                    if (existing == null) {
                        byId[id] = item.withCanonicalRevision(revision)
                        order += id
                        added += id
                    } else if (compareRevision(revision, normalizedRevision(existing.revision) ?: "0") > 0) {
                        byId[id] = item.withCanonicalRevision(revision)
                        updated += id
                    } else {
                        // 同版本重复回放幂等；旧版本绝不覆盖新状态
                        ignored += id
                    }
                }
                NsfwFeedEvents.REMOVE -> {
                    val existing = byId[id]
                    val existingRevision = existing?.let { normalizedRevision(it.revision) ?: "0" }
                    if (existing != null && existingRevision != null &&
                        compareRevision(revision, existingRevision) < 0
                    ) {
                        // 旧 REMOVE 不得删除后来更新的图片
                        ignored += id
                        return@forEach
                    }
                    val tombstone = tombstones[id]
                    if (tombstone == null || compareRevision(revision, tombstone) > 0) {
                        tombstones[id] = revision
                    }
                    if (remove(id)) removed += id
                }
                else -> rejected += id
            }
        }
        return NsfwMergeResult(
            addedIds = added,
            updatedIds = updated,
            total = order.size,
            removedIds = removed,
            ignoredStaleIds = ignored,
            rejectedIds = rejected,
        )
    }

    fun snapshot(): List<NsfwFeedItem> = order.mapNotNull { byId[it] }

    fun find(imageId: String): NsfwFeedItem? = byId[imageId]

    fun indexOf(imageId: String): Int = order.indexOf(imageId)

    fun tombstoneRevision(imageId: String): String? = tombstones[imageId]

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
        val canonical = normalizedRevision(revision) ?: return false
        val current = byId[imageId] ?: return false
        val currentRevision = normalizedRevision(current.revision) ?: return false
        if (compareRevision(canonical, currentRevision) <= 0) return false
        byId[imageId] = current.copy(
            status = status,
            isFavorite = favorite,
            updatedAt = updatedAt,
            revision = canonical,
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
        tombstones.clear()
    }

    private fun NsfwFeedItem.withCanonicalRevision(revision: String): NsfwFeedItem =
        if (this.revision == revision) this else copy(revision = revision)

    /** 只接受十进制非负整数；去前导零后作为规范形式。 */
    internal fun normalizedRevision(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        val trimmed = value.trimStart('0')
        return trimmed.ifEmpty { "0" }
    }

    /** 任意长度十进制比较：先比长度再比字典序，避免 Long 溢出。 */
    internal fun compareRevision(left: String, right: String): Int {
        val a = normalizedRevision(left) ?: return -1
        val b = normalizedRevision(right) ?: return 1
        if (a.length != b.length) return a.length.compareTo(b.length)
        return a.compareTo(b)
    }
}
