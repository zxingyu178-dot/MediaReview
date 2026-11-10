package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.*

/** 同版本比较范围；评分绑定此范围，不依赖主图库当前图片。 */
data class NsfwPromptComparison private constructor(val query: NsfwGalleryQuery, val pageSize: Int) {
    companion object {
        fun open(link: NsfwPromptLink, base: NsfwGalleryFilter, pageSize: Int = 6): NsfwPromptComparison {
            require(link.isExact) { "需要可靠的提示词版本关联才能对比" }
            require(pageSize == 6 || pageSize == 10)
            return NsfwPromptComparison(base.copy(promptId = link.promptId, versionId = link.promptVersionId,
                status = null, category = null, favorite = null, search = null, tag = null).resolve(), pageSize)
        }
    }
    fun withPageSize(size: Int): NsfwPromptComparison {
        require(size == 6 || size == 10)
        return copy(pageSize = size)
    }
    fun validatePage(items: List<NsfwFeedItem>): List<NsfwFeedItem> {
        require(items.all { it.prompt?.isExact == true && it.prompt.promptId == query.filter.promptId &&
            it.prompt.promptVersionId == query.filter.versionId }) { "对比页包含其他提示词版本，已停止显示" }
        return items
    }
}
