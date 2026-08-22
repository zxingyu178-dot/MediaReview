package com.mediareview.app.core.media

import com.mediareview.app.core.model.ReviewQueueItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段 15:批阅分页窗口纯逻辑(恢复定位 / 向后 / 向前分页 / 结束判断)。 */
class ReviewQueueWindowTest {

    private fun item(index: Int): ReviewQueueItemDto = ReviewQueueItemDto(index = index, media = null)

    @Test
    fun `恢复分页定位到第 637 条(50每页的 13 页)`() {
        val window = ReviewQueueWindow(pageSize = 50)
        val plan = window.resumePage(637)
        assertEquals(13, plan.page) // 637/50+1 = 13
        assertEquals(600, plan.baseIndex) // (13-1)*50
        assertEquals(37, plan.localStart) // 637-600
    }

    @Test
    fun `恢复分页首页绝对索引 0`() {
        val window = ReviewQueueWindow(pageSize = 50)
        val plan = window.resumePage(0)
        assertEquals(1, plan.page)
        assertEquals(0, plan.baseIndex)
        assertEquals(0, plan.localStart)
    }

    @Test
    fun `结束判断为 baseIndex 加 items size 达到 total(而非仅 items size)`() {
        val window = ReviewQueueWindow(pageSize = 40)
        // 恢复在第 900 条:baseIndex=880,只加载了 40 条
        window.applyInitial(
            items = (880 until 920).map(::item),
            total = 1000,
            page = 23,
        )
        // items.size(40) < total(1000),但 baseIndex+items.size = 920 < 1000,未结束
        assertFalse(window.atEnd())
        // 模拟继续加载到第 24 页(960..1000),此时窗口末端 880+120=1000 >= total
        window.append(items = (920 until 1000).map(::item), total = 1000, page = 24)
        assertTrue(window.atEnd())
    }

    @Test
    fun `恢复模式 baseIndex 非零时 items size 小于 total 但窗口已到底应结束`() {
        val window = ReviewQueueWindow(pageSize = 40)
        // 恢复在 960 条,共 1000 条:加载到 960..1000(40 条)
        window.applyInitial(items = (960 until 1000).map(::item), total = 1000, page = 25)
        // baseIndex=960, items.size=40, baseIndex+items.size = 1000 >= 1000 → 结束
        assertTrue(window.atEnd())
        // 旧逻辑 items.size(40) < total(1000) 会误判未结束而继续请求不存在的下一页
    }

    @Test
    fun `向前分页前置一页并回退 baseIndex`() {
        val window = ReviewQueueWindow(pageSize = 40)
        window.applyInitial(items = (40 until 80).map(::item), total = 200, page = 2)
        assertEquals(40, window.baseIndex)
        assertTrue(window.canLoadPrev())

        val added = window.prepend(items = (0 until 40).map(::item), total = 200, page = 1)
        assertEquals(40, added)
        assertEquals(0, window.baseIndex)
        assertEquals(80, window.items.size)
        assertEquals(0, window.items.first().index)
        assertFalse(window.canLoadPrev())
    }

    @Test
    fun `首页时不能向前分页`() {
        val window = ReviewQueueWindow(pageSize = 40)
        window.applyInitial(items = (0 until 40).map(::item), total = 120, page = 1)
        assertEquals(0, window.baseIndex)
        assertFalse(window.canLoadPrev())
    }

    @Test
    fun `向后分页追加并推进 page`() {
        val window = ReviewQueueWindow(pageSize = 40)
        window.applyInitial(items = (0 until 40).map(::item), total = 100, page = 1)
        assertEquals(2, window.nextPage())
        window.append(items = (40 until 80).map(::item), total = 100, page = 2)
        assertEquals(80, window.items.size)
        assertEquals(2, window.page)
        assertFalse(window.atEnd())
    }
}
