package com.mediareview.app.feature.v2.model

import org.junit.Assert.*
import org.junit.Test

class BrowseContextTest {
    private fun media(id: String, type: V2MediaType) = V2Media(
        id, id, id, "source-folder", "文件夹", type, 0, 1, 0, true, false,
        "", "", null, null, 16, 9,
    )
    private fun video(id: String) = media(id, V2MediaType.VIDEO)
    private fun image(id: String) = media(id, V2MediaType.IMAGE)
    private fun context(items: List<V2Media>, id: String): V2ContextQueue {
        val result = buildBrowseContext(items, id)
        assertNotNull("已加载目标必须构建独立上下文", result)
        return result!!
    }
    @Test fun `clicked B has A B C video queue despite mixed media`() {
        val q = context(listOf(video("A"), image("i1"), video("B"), video("C")), "B")
        assertEquals(listOf("A", "B", "C"), q.mediaIds); assertEquals(1, q.currentIndex)
        assertEquals(V2TypeFilter.VIDEO, q.typeFilter); assertNull(q.folderId)
    }
    @Test fun `image window uses only loaded images and keeps index`() {
        val q = context(listOf(image("A"), video("v"), image("B"), image("C")), "B")
        assertEquals(listOf("A", "B", "C"), q.mediaIds); assertEquals(1, q.currentIndex)
        assertEquals(V2TypeFilter.IMAGE, q.typeFilter)
    }
    @Test fun `duplicates removed before clicked index computed`() {
        val q = context(listOf(video("A"), video("A"), video("B"), video("B")), "B")
        assertEquals(listOf("A", "B"), q.mediaIds); assertEquals(1, q.currentIndex)
    }
    @Test fun `missing target and empty window reject rather than selecting zero`() {
        assertNull(buildBrowseContext(listOf(video("A")), "B"))
        assertNull(buildBrowseContext(emptyList(), "A"))
    }
    @Test fun `removing favorite from live collection does not mutate frozen queue`() {
        val favorites = mutableListOf(video("A"), video("B"), video("C"))
        val q = context(favorites, "B")
        favorites.removeAt(1)
        assertEquals(listOf("A", "B", "C"), q.mediaIds); assertEquals(1, q.currentIndex)
    }
    @Test fun `single loaded target creates single valid queue`() {
        val q = context(listOf(image("off-home")), "off-home")
        assertEquals(listOf("off-home"), q.mediaIds); assertEquals(0, q.currentIndex)
    }
}
