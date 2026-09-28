package com.mediareview.app.feature.v2.review.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批阅队列分页纯逻辑测试（Stage 8B §20 / §21 / §24 / §47 + Stage 8B.1 §5 / §6 / §8 / §9）。
 *
 * 覆盖：绝对索引 → 分页计划（含 637/1000 深位置）、页边界、
 * **按页边界的 atEnd（缺项/尾项失效不影响结束判断）**、
 * **合并去重**、**缺项 localIndex 映射**、**失效锚点的前后回退定位**、加载触发条件。
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

    @Test
    fun `总页数按每页条数向上取整`() {
        assertEquals(1, ReviewQueuePaging.totalPages(0, 50))
        assertEquals(1, ReviewQueuePaging.totalPages(50, 50))
        assertEquals(2, ReviewQueuePaging.totalPages(51, 50))
        assertEquals(20, ReviewQueuePaging.totalPages(1000, 50))
    }

    // ---------- §8 结束判断（按页边界，不依赖最后一个可用媒体） ----------

    @Test
    fun `末页已加载完即到达末尾`() {
        assertTrue(ReviewQueuePaging.isAtEnd(lastLoadedPage = 2, pageSize = 50, totalCount = 100))
        assertFalse(ReviewQueuePaging.isAtEnd(lastLoadedPage = 2, pageSize = 50, totalCount = 101))
    }

    @Test
    fun `尾部媒体失效不影响结束判断`() {
        // total=100、97 可用 98/99 已删除：加载完第 2 页（覆盖 50..99）就必须认为到末尾，
        // 否则会永远"还有下一页"（评审 §8）
        assertTrue(
            "页边界 2 * 50 >= 100，即便末尾两条不可用也必须结束",
            ReviewQueuePaging.isAtEnd(lastLoadedPage = 2, pageSize = 50, totalCount = 100),
        )
    }

    @Test
    fun `没有任何数据时视为结束不再请求`() {
        assertTrue(ReviewQueuePaging.isAtEnd(lastLoadedPage = 0, pageSize = 50, totalCount = 100))
    }

    // ---------- §5 合并去重 ----------

    @Test
    fun `合并分页结果按绝对索引去重并排序`() {
        val original = listOf(item(48), item(49), item(50), item(51))
        val incoming = listOf(item(50), item(51), item(52))

        val merged = ReviewQueuePaging.mergeItems(original, incoming)

        assertEquals(
            "重复请求同一页不得产生重复条目",
            listOf(48, 49, 50, 51, 52),
            merged.map { it.absoluteIndex },
        )
    }

    @Test
    fun `向前分页前置到已有窗口`() {
        val original = listOf(item(600), item(602))
        val incoming = listOf(item(598), item(599))

        val merged = ReviewQueuePaging.mergeItems(incoming, original)

        assertEquals(listOf(598, 599, 600, 602), merged.map { it.absoluteIndex })
    }

    // ---------- §6 缺项 localIndex ----------

    @Test
    fun `缺项时 localIndex 必须按真实位置而不是绝对差值`() {
        // 绝对 600 / 602 / 603（601 的媒体已失效）
        val window = windowOf(600, 602, 603)

        assertEquals("602 的本地索引是 1，不能算成 602-600=2", 1, window.localIndexOf(602))
        assertEquals(2, window.localIndexOf(603))
        assertNull("不在窗口内的绝对索引必须返回 null", window.localIndexOf(601))
    }

    @Test
    fun `缺项时窗口外索引返回 null`() {
        val window = windowOf(600, 602)
        assertNull(window.localIndexOf(1000))
        assertNull(window.localIndexOf(-1))
    }

    // ---------- §7 失效锚点定位 ----------

    @Test
    fun `优先取第一个大于等于锚点的可用项`() {
        val page = listOf(item(640), item(641), item(645))

        assertEquals(640, ReviewQueuePaging.firstAtOrAfter(page, 637)?.absoluteIndex)
    }

    @Test
    fun `向后找不到时回退到锚点之前最近的可用项`() {
        val page = listOf(item(600), item(630), item(636))

        assertNull(ReviewQueuePaging.firstAtOrAfter(page, 637))
        assertEquals(636, ReviewQueuePaging.nearestBefore(page, 637)?.absoluteIndex)
    }

    // ---------- 窗口边界推导 ----------

    @Test
    fun `窗口按页边界推导 atEnd 与 canLoadPrev`() {
        val middle = ReviewQueueWindow(
            items = listOf(item(600)),
            firstLoadedPage = 13,
            lastLoadedPage = 13,
            totalCount = 1000,
        )
        assertEquals(600, middle.baseIndex)
        assertFalse(middle.atEnd)
        assertTrue(middle.canLoadPrev)

        val firstOnly = ReviewQueueWindow(
            items = listOf(item(0)),
            firstLoadedPage = 1,
            lastLoadedPage = 1,
            totalCount = 1000,
        )
        assertFalse(firstOnly.canLoadPrev)
        assertFalse("第 1 页起加载但队列还有后续：不得认为已到末尾", firstOnly.atEnd)

        val lastPage = ReviewQueueWindow(
            items = listOf(item(950)),
            firstLoadedPage = 20,
            lastLoadedPage = 20,
            totalCount = 1000,
        )
        assertTrue(lastPage.atEnd)
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

    // ---------- helpers ----------

    private fun item(absoluteIndex: Int): ReviewQueueItemUi = ReviewQueueItemUi(
        absoluteIndex = absoluteIndex,
        mediaId = "v$absoluteIndex",
        title = "视频 $absoluteIndex",
        code = "",
        folderName = "",
        durationMs = 1_000L,
        naturalWidth = 1920,
        naturalHeight = 1080,
        coverUrl = "",
        favorite = false,
    )

    private fun windowOf(vararg absoluteIndexes: Int): ReviewQueueWindow = ReviewQueueWindow(
        items = absoluteIndexes.map { item(it) },
        firstLoadedPage = 13,
        lastLoadedPage = 13,
        totalCount = 1000,
    )
}