package com.mediareview.app.feature.v2.nsfw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwFeedStoreTest {

    private fun item(id: String, revision: String = "1", status: String = "NEW") = NsfwFeedItem(
        imageId = id,
        revision = revision,
        status = status,
        createdAt = "2026-10-28T12:00:00Z",
        updatedAt = "2026-10-28T12:00:01Z",
    )

    private fun page(vararg items: NsfwFeedItem, next: String? = null, more: Boolean = false) =
        NsfwFeedPage(items = items.toList(), nextCursor = next, hasMore = more)

    @Test
    fun `新图片只追加到队尾且不改变已有顺序`() {
        val store = NsfwFeedStore()
        val first = store.merge(page(item("a"), item("b"), next = "c1", more = true))
        assertEquals(listOf("a", "b"), first.addedIds)
        val second = store.merge(page(item("b", revision = "2", status = "KEEP"), item("c")))
        assertEquals(listOf("c"), second.addedIds)
        assertEquals(listOf("b"), second.updatedIds)
        assertEquals(listOf("a", "b", "c"), store.snapshot().map { it.imageId })
        assertEquals("KEEP", store.find("b")?.status)
        assertEquals("2", store.find("b")?.revision)
    }

    @Test
    fun `同一页内重复ID只保留一次且原位更新`() {
        val store = NsfwFeedStore()
        val merge = store.merge(page(item("a"), item("a", revision = "2", status = "REJECT"), item("b")))
        assertEquals(listOf("a", "b"), merge.addedIds)
        assertEquals(listOf("a"), merge.updatedIds)
        assertEquals(2, store.size())
        assertEquals("REJECT", store.find("a")?.status)
    }

    @Test
    fun `跨页同ID更新原位且不影响当前索引`() {
        val store = NsfwFeedStore()
        store.merge(page(item("a"), item("b"), item("c")))
        store.merge(page(item("a", revision = "9", status = "KEEP"), item("d")))
        assertEquals(listOf("a", "b", "c", "d"), store.snapshot().map { it.imageId })
        assertEquals(0, store.indexOf("a"))
        assertEquals("KEEP", store.find("a")?.status)
    }

    @Test
    fun `空增量不代表删除`() {
        val store = NsfwFeedStore()
        store.merge(page(item("a")))
        val empty = store.merge(page())
        assertTrue(empty.addedIds.isEmpty())
        assertTrue(empty.updatedIds.isEmpty())
        assertEquals(1, store.size())
    }

    @Test
    fun `更新时间戳不同仍是同一身份`() {
        val store = NsfwFeedStore()
        val old = item("a").copy(updatedAt = "2026-10-28T12:00:01Z")
        val new = item("a").copy(updatedAt = "2026-10-28T12:00:05Z", revision = "2")
        store.merge(page(old))
        val merge = store.merge(page(new))
        assertEquals(1, merge.total)
        assertEquals(1, store.size())
        assertEquals("2026-10-28T12:00:05Z", store.find("a")?.updatedAt)
    }

    @Test
    fun `空白ID被忽略`() {
        val store = NsfwFeedStore()
        store.merge(page(item(""), item("a")))
        assertEquals(1, store.size())
        assertFalse(store.snapshot().any { it.imageId.isBlank() })
    }

    @Test
    fun `旧revision的重复页不能覆盖较新状态`() {
        val store = NsfwFeedStore()
        store.merge(page(item("a", revision = "2", status = "KEEP")))
        val merge = store.merge(page(item("a", revision = "1", status = "NEW")))
        assertEquals("KEEP", store.find("a")?.status)
        assertEquals("2", store.find("a")?.revision)
        assertEquals(listOf("a"), merge.ignoredStaleIds)
    }

    @Test
    fun `审核结果之后的旧feed页必须被忽略`() {
        val store = NsfwFeedStore()
        store.merge(page(item("a", revision = "1")))
        val applied = store.applyDecision("a", "KEEP", false, "2026-10-28T12:06:00Z", "3")
        assertTrue(applied)
        assertEquals("KEEP", store.find("a")?.status)

        store.merge(page(item("a", revision = "2", status = "NEW")))
        assertEquals("KEEP", store.find("a")?.status)
        assertEquals("3", store.find("a")?.revision)

        store.merge(page(item("a", revision = "4", status = "REJECT")))
        assertEquals("REJECT", store.find("a")?.status)
        assertEquals("4", store.find("a")?.revision)
    }

    @Test
    fun `同revision重复响应幂等且不改变状态`() {
        val store = NsfwFeedStore()
        store.merge(page(item("a", revision = "2", status = "KEEP")))
        store.merge(page(item("a", revision = "2", status = "KEEP")))
        assertEquals("KEEP", store.find("a")?.status)
        assertEquals(1, store.size())
    }

    @Test
    fun `审核结果也必须按revision前进`() {
        val store = NsfwFeedStore()
        store.merge(page(item("a", revision = "3", status = "KEEP")))
        val stale = store.applyDecision("a", "NEW", false, "2026-10-28T12:00:00Z", "2")
        assertFalse(stale)
        assertEquals("KEEP", store.find("a")?.status)
        assertEquals("3", store.find("a")?.revision)
    }

    @Test
    fun `REMOVE移除对应图片且未知移除幂等`() {
        val store = NsfwFeedStore()
        store.merge(page(item("a"), item("b")))
        val remove = store.merge(
            page(item("a", revision = "2").copy(event = NsfwFeedEvents.REMOVE)),
        )
        assertEquals(listOf("a"), remove.removedIds)
        assertEquals(listOf("b"), store.snapshot().map { it.imageId })
        val repeated = store.merge(
            page(item("a", revision = "3").copy(event = NsfwFeedEvents.REMOVE)),
        )
        assertTrue(repeated.removedIds.isEmpty())
        assertEquals(1, store.size())
    }
}
