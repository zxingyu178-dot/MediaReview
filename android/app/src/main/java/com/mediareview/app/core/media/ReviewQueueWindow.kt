package com.mediareview.app.core.media

import com.mediareview.app.core.model.ReviewQueueItemDto

/** 恢复定位结果:需要加载的分页、对应 baseIndex、本地起始索引。 */
data class ResumePlan(
    val page: Int,
    val baseIndex: Int,
    val localStart: Int,
)

/**
 * 批阅分页窗口纯逻辑(可 JVM 单测)。
 *
 * 维护 [baseIndex](已加载第一项对应的绝对索引)/ [items] / [total] / [page],
 * 支持:
 * - [resumePage]: 恢复模式只加载包含目标绝对索引的分页并正确定位;
 * - [append]/[nextPage]: 向后分页,结束判断为 `baseIndex + items.size >= total`;
 * - [prepend]/[prevPage]: 向前分页,并返回前置条数供 UI 补偿 pager 偏移。
 */
class ReviewQueueWindow(private val pageSize: Int) {

    var baseIndex: Int = 0
        private set
    var items: List<ReviewQueueItemDto> = emptyList()
        private set
    var total: Int = 0
        private set
    var page: Int = 1
        private set

    /** 恢复:计算包含 absoluteIndex 的分页计划。 */
    fun resumePage(absoluteIndex: Int): ResumePlan {
        val p = absoluteIndex / pageSize + 1
        val base = (p - 1) * pageSize
        return ResumePlan(
            page = p,
            baseIndex = base,
            localStart = (absoluteIndex - base).coerceAtLeast(0),
        )
    }

    /** 应用首屏(恢复或新建)加载结果。 */
    fun applyInitial(items: List<ReviewQueueItemDto>, total: Int, page: Int) {
        this.items = items
        this.total = total
        this.page = page
        this.baseIndex = (page - 1) * pageSize
    }

    /** 结束判断:已加载窗口末端(baseIndex + items.size)是否已达到 total。 */
    fun atEnd(): Boolean = baseIndex + items.size >= total

    /** 下一分页页码。 */
    fun nextPage(): Int = (baseIndex + items.size) / pageSize + 1

    /** 追加一页(向后分页)。 */
    fun append(items: List<ReviewQueueItemDto>, total: Int, page: Int) {
        this.items = this.items + items
        this.total = total
        this.page = page
    }

    /** 是否还能向前分页(尚未到达第一页)。 */
    fun canLoadPrev(): Boolean = baseIndex > 0

    /** 上一分页页码(1-based)。 */
    fun prevPage(): Int = baseIndex / pageSize

    /** 前置一页(向前分页),返回前置条数(供 UI 补偿滚动偏移)。 */
    fun prepend(items: List<ReviewQueueItemDto>, total: Int, page: Int): Int {
        val added = items.size
        this.items = items + this.items
        this.total = total
        this.page = page
        baseIndex -= added
        return added
    }
}
