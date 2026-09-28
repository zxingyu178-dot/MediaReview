package com.mediareview.app.feature.v2.review.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批阅队列分页纯逻辑测试（Stage 8B §20 / §21 / §22 / §24 / §47）。
 *
 * 覆盖：绝对索引 → 分页计划（含 637/1000 深位置）、页边界、结束判断（缺项不压缩索引）、
 * 前置/后置加载触发条件。
 */
class ReviewQueuePagingTest {

    // ---------- §21 深位置定位 ----------

    @Test
    fun `637 落在第 13 页且本地定位 37`() {
        val plan = ReviewQueuePaging.resumePlan(637, pageSize = 50)

        assertEquals(13, plan.page)
        assertEquals(600, plan.baseIndex)
        assertEquals(37, plan.localStart)
    }

    @Test
    fun `第 0 项落在第 1 页`() {
        val plan = ReviewQueuePaging.resumePlan(0, pageSize = 50)

        assertEquals(1, plan.page)
        assertEquals(0, plan.baseIndex)
        assertEquals(0, plan.localStart)
    }

    @Test
    fun `页边界与末项定位正确`() {
        // 49 = 第 1 页最后一项；50 = 第 2 页第一项
        assertEquals(1, ReviewQueuePaging.resumePlan(49, 50).page)
        assertEquals(49, ReviewQueuePaging.resumePlan(49, 50).localStart)
        assertEquals(2, ReviewQueuePaging.resumePlan(50, 50).page)
        assertEquals(0, ReviewQueuePaging.resumePlan(50, 50).localStart)
    }

    @Test
    fun `负索引被收敛到第 1 项`() {
        val plan = ReviewQueuePaging.resumePlan(-5, pageSize = 50)
        assertEquals(1, plan.page)
        assertEquals(0, plan.localStart)
    }

    // ---------- §24 结束判断（缺项不压缩索引） ----------

    @Test
    fun `末项索引加一达到总数即结束`() {
        assertTrue(ReviewQueuePaging.isAtEnd(lastLoadedAbsoluteIndex = 99, totalCount = 100))
        assertFalse(ReviewQueuePaging.isAtEnd(lastLoadedAbsoluteIndex = 98, totalCount = 100))
    }

    @Test
    fun `缺项时按真实绝对索引判断而不是按条数`() {
        // 服务端可能跳过不可用媒体：一页 50 条里只剩 48 条，但最后一项的绝对索引仍是 97
        assertTrue(
            "绝对索引 97 +1 = 98 < 100：仍应继续加载",
            !ReviewQueuePaging.isAtEnd(lastLoadedAbsoluteIndex = 97, totalCount = 100),
        )
        assertTrue(ReviewQueuePaging.isAtEnd(lastLoadedAbsoluteIndex = 99, totalCount = 100))
    }

    @Test
    fun `没有任何数据时视为结束不再请求`() {
        assertTrue(ReviewQueuePaging.isAtEnd(lastLoadedAbsoluteIndex = null, totalCount = 100))
    }

    // ---------- 加载触发条件 ----------

    @Test
    fun `接近底部触发下一页`() {
        // 阈值 5：已加载 50 条时，可见第 45 项起触发
        assertFalse(ReviewQueuePaging.shouldLoadNext(44, loadedCount = 50, atEnd = false, loading = false))
        assertTrue(ReviewQueuePaging.shouldLoadNext(45, loadedCount = 50, atEnd = false, loading = false))
    }

    @Test
    fun `已到末尾或正在加载时不重复请求`() {
        assertFalse(ReviewQueuePaging.shouldLoadNext(49, loadedCount = 50, atEnd = true, loading = false))
        assertFalse(ReviewQueuePaging.shouldLoadNext(49, loadedCount = 50, atEnd = false, loading = true))
        assertFalse("空列表不得误触发", ReviewQueuePaging.shouldLoadNext(0, loadedCount = 0, atEnd = false, loading = false))
    }

    @Test
    fun `接近顶部触发上一页`() {
        assertTrue(ReviewQueuePaging.shouldLoadPrev(firstVisibleLocalIndex = 1, canLoadPrev = true, loading = false))
        assertFalse(ReviewQueuePaging.shouldLoadPrev(firstVisibleLocalIndex = 5, canLoadPrev = true, loading = false))
        assertFalse(
            "已经在第一页时不得向前加载",
            ReviewQueuePaging.shouldLoadPrev(firstVisibleLocalIndex = 0, canLoadPrev = false, loading = false),
        )
    }
}