package com.mediareview.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段 15:latest-wins 调度令牌——快速连续滑动时只有最新一次有效。 */
class LatestWinsSchedulerTest {

    @Test
    fun `连续领取令牌后只有最新令牌有效`() {
        val s = LatestWinsScheduler()
        val first = s.next()
        val second = s.next()
        val third = s.next()
        assertFalse(s.isValid(first))
        assertFalse(s.isValid(second))
        assertTrue(s.isValid(third))
    }

    @Test
    fun `未领取令牌时默认无有效令牌`() {
        val s = LatestWinsScheduler()
        assertFalse(s.isValid(0L))
        assertFalse(s.isValid(1L))
    }

    @Test
    fun `reset 后旧令牌全部失效,新令牌重新生效`() {
        val s = LatestWinsScheduler()
        val old = s.next()
        s.reset()
        assertFalse(s.isValid(old))
        val fresh = s.next()
        assertTrue(s.isValid(fresh))
    }

    @Test
    fun `reset 后令牌永不复用历史值`() {
        val s = LatestWinsScheduler()
        val issued = mutableSetOf<Long>()
        repeat(5) { issued += s.next() }
        s.reset()
        repeat(5) { issued += s.next() }
        // 全部令牌互不相同(单调递增,reset 不倒退)
        assertEquals(10, issued.size)
    }

    @Test
    fun `多次 reset 仍单调递增`() {
        val s = LatestWinsScheduler()
        var prev = -1L
        repeat(20) {
            if (it % 4 == 0) s.reset()
            val token = s.next()
            assertTrue(token > prev)
            prev = token
        }
    }
}
