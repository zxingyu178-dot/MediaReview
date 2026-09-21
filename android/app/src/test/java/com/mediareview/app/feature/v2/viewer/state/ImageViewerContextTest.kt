package com.mediareview.app.feature.v2.viewer.state

import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ImageViewerContext：混合队列过滤、索引重算、边界、缺失 fallback。 */
class ImageViewerContextTest {

    // video: v, image: i
    private val ids = listOf("v1", "i1", "v2", "i2", "i3")
    private fun isImage(id: String) = id.startsWith("i")

    @Test
    fun `混合队列过滤后按 initialMediaId 重算索引`() {
        val ctx = ImageViewerContext.build(
            allIds = ids,
            isImage = ::isImage,
            initialMediaId = "i2",
            sourceFolderId = "f1",
            sortField = V2SortField.RECENT,
            sortOrder = V2SortOrder.DESC,
        )
        assertEquals(listOf("i1", "i2", "i3"), ctx.mediaIds)
        assertEquals(1, ctx.initialIndex)
        assertEquals(3, ctx.size)
        assertEquals("f1", ctx.sourceFolderId)
    }

    @Test
    fun `首图 initialIndex 为 0`() {
        val ctx = ImageViewerContext.build(ids, ::isImage, "i1", null, V2SortField.RECENT, V2SortOrder.DESC)
        assertEquals(0, ctx.initialIndex)
    }

    @Test
    fun `末图索引正确`() {
        val ctx = ImageViewerContext.build(ids, ::isImage, "i3", null, V2SortField.RECENT, V2SortOrder.DESC)
        assertEquals(2, ctx.initialIndex)
        assertEquals("i3", ctx.idAt(2))
    }

    @Test
    fun `只有一张图正常工作`() {
        val ctx = ImageViewerContext.build(listOf("v1", "only"), { it == "only" }, "only", null, V2SortField.RECENT, V2SortOrder.DESC)
        assertEquals(listOf("only"), ctx.mediaIds)
        assertEquals(0, ctx.initialIndex)
        assertEquals(1, ctx.size)
    }

    @Test
    fun `initialMediaId 不存在时安全 fallback 到 0`() {
        val ctx = ImageViewerContext.build(ids, ::isImage, "nope", null, V2SortField.RECENT, V2SortOrder.DESC)
        assertEquals(0, ctx.initialIndex)
        assertEquals("i1", ctx.idAt(0))
    }

    @Test
    fun `没有图片时为空且安全`() {
        val ctx = ImageViewerContext.build(listOf("v1", "v2"), { false }, "v1", null, V2SortField.RECENT, V2SortOrder.DESC)
        assertEquals(0, ctx.size)
        assertTrue(ctx.mediaIds.isEmpty())
        assertNull(ctx.idAt(0))
    }

    @Test
    fun `idAt 越界返回 null`() {
        val ctx = ImageViewerContext.build(ids, ::isImage, "i1", null, V2SortField.RECENT, V2SortOrder.DESC)
        assertNull(ctx.idAt(99))
    }
}
