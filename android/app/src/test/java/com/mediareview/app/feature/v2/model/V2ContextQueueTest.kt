package com.mediareview.app.feature.v2.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 阶段 8B §11：上下文队列索引收敛（空队列必须拒绝写入，而不是算出非法区间）。 */
class V2ContextQueueTest {

    private fun queue(ids: List<String>) = V2ContextQueue(
        folderId = null,
        sortField = V2SortField.RECENT,
        sortOrder = V2SortOrder.DESC,
        typeFilter = V2TypeFilter.VIDEO,
        mediaIds = ids,
        currentIndex = 0,
    )

    @Test
    fun `空队列拒绝写入索引`() {
        assertNull("空队列不得写入索引（旧实现会得到 coerceIn(0, -1)）", queue(emptyList()).clampIndex(3))
        assertNull(queue(emptyList()).clampIndex(0))
        assertNull(queue(emptyList()).clampIndex(-1))
    }

    @Test
    fun `单元素队列收敛到 0`() {
        assertEquals(0, queue(listOf("m1")).clampIndex(5))
        assertEquals(0, queue(listOf("m1")).clampIndex(-5))
    }

    @Test
    fun `多元素队列收敛到合法区间`() {
        val q = queue(listOf("m1", "m2", "m3"))
        assertEquals(0, q.clampIndex(-2))
        assertEquals(2, q.clampIndex(9))
        assertEquals(1, q.clampIndex(1))
    }
}