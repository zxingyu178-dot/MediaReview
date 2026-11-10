package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.NsfwBatchReviewItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwFrozenBatch

/** 当前可见页的选择草稿；不写服务器，不把未加载图判为淘汰。 */
data class NsfwBatchDraft private constructor(
    val items: List<NsfwFeedItem>,
    val loadedIds: Set<String> = emptySet(),
    val selectedIds: Set<String> = emptySet(),
    val displayedIds: Set<String> = emptySet(),
) {
    companion object {
        fun open(items: List<NsfwFeedItem>, pageSize: Int = 9): NsfwBatchDraft {
            require(pageSize in 1..10)
            return NsfwBatchDraft(items.filter { it.status == "NEW" }.distinctBy { it.imageId }.take(pageSize).toList())
        }
    }

    fun loaded(imageId: String, success: Boolean): NsfwBatchDraft {
        if (items.none { it.imageId == imageId }) return this
        return if (success) copy(loadedIds = loadedIds + imageId)
        else copy(loadedIds = loadedIds - imageId, selectedIds = selectedIds - imageId, displayedIds = displayedIds - imageId)
    }

    fun displayed(imageId: String): NsfwBatchDraft =
        if (imageId in loadedIds) copy(displayedIds = displayedIds + imageId) else this

    val eligibleIds: Set<String> get() = loadedIds intersect displayedIds

    /** 仅重新绑定本页已有身份；已审核/消失的图排除，仍NEW的选择保留，重新展示后才可提交。 */
    fun refreshed(latest: List<NsfwFeedItem>): NsfwBatchDraft {
        val byId = latest.associateBy { it.imageId }
        val refreshed = items.mapNotNull { byId[it.imageId]?.takeIf { row -> row.status == "NEW" } }
        return NsfwBatchDraft(refreshed, selectedIds = selectedIds intersect refreshed.map { it.imageId }.toSet())
    }

    fun toggle(imageId: String): NsfwBatchDraft {
        if (imageId !in eligibleIds) return this
        return copy(selectedIds = if (imageId in selectedIds) selectedIds - imageId else selectedIds + imageId)
    }

    fun selectAll(): NsfwBatchDraft = copy(selectedIds = eligibleIds.toSet())
    fun clearSelection(): NsfwBatchDraft = copy(selectedIds = emptySet())

    val keptCount: Int get() = selectedIds.count { it in eligibleIds }
    val rejectedCount: Int get() = eligibleIds.size - keptCount
    val unloadedCount: Int get() = items.size - eligibleIds.size

    fun freeze(requestId: String, allowRejectAll: Boolean = false): NsfwFrozenBatch {
        require(requestId.isNotBlank()) { "缺少批次请求号" }
        val visible = items.filter { it.imageId in eligibleIds }
        require(visible.isNotEmpty()) { "没有加载成功的待审核图片" }
        require(keptCount > 0 || allowRejectAll) { "请再次确认全部淘汰" }
        return NsfwFrozenBatch(requestId, visible.map {
            NsfwBatchReviewItem(it.imageId, if (it.imageId in selectedIds) "KEEP" else "REJECT", it.revision)
        })
    }
}
