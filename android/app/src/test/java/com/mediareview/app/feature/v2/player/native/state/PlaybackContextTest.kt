package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlaybackContext：上一条/下一条边界、视频切换、索引夹取（Stage 8A：队列只保存 id / 标题）。 */
class PlaybackContextTest {

    private val reqs = listOf(
        PlaybackQueueItem("a", "A"),
        PlaybackQueueItem("b", "B"),
        PlaybackQueueItem("c", "C"),
    )

    @Test
    fun `当前条目与上下一条存在性`() {
        val ctx = PlaybackContext(reqs, currentIndex = 1)
        assertEquals("B", ctx.current?.title)
        assertTrue(ctx.hasPrevious)
        assertTrue(ctx.hasNext)
    }

    @Test
    fun `上一条边界：首位保持不变`() {
        val ctx = PlaybackContext(reqs, currentIndex = 0)
        assertFalse(ctx.hasPrevious)
        assertEquals(0, ctx.previous().currentIndex)
    }

    @Test
    fun `下一条边界：末位保持不变`() {
        val ctx = PlaybackContext(reqs, currentIndex = 2)
        assertFalse(ctx.hasNext)
        assertEquals(2, ctx.next().currentIndex)
    }

    @Test
    fun `连续切换 017-018-019 顺序推进`() {
        val ctx = PlaybackContext(reqs, currentIndex = 0)
        val b = ctx.next()
        assertEquals(1, b.currentIndex)
        assertEquals("B", b.current?.title)
        val c = b.next()
        assertEquals(2, c.currentIndex)
        assertEquals("C", c.current?.title)
        val back = c.previous()
        assertEquals(1, back.currentIndex)
        assertEquals("B", back.current?.title)
    }

    @Test
    fun `moveTo 越界时夹取`() {
        val ctx = PlaybackContext(reqs, currentIndex = 0)
        assertEquals(0, ctx.moveTo(-5).currentIndex)
        assertEquals(2, ctx.moveTo(99).currentIndex)
        assertEquals(1, ctx.moveTo(1).currentIndex)
    }

    @Test
    fun `空列表安全`() {
        val ctx = PlaybackContext(emptyList())
        assertNull(ctx.current)
        assertFalse(ctx.hasNext)
        assertFalse(ctx.hasPrevious)
        assertEquals(0, ctx.next().currentIndex)
    }

    @Test
    fun `队列项只表达标识与标题`() {
        // Stage 8A 结构性约束：PlaybackContext 不再携带 URL / headers，
        // 播放地址必须由 ViewModel 按需异步解析（禁止一次解析整个队列）。
        val item = PlaybackQueueItem(mediaId = "a", title = "A")
        assertEquals("a", item.mediaId)
        assertEquals("A", item.title)
        assertEquals(item, item.copy())
    }
}