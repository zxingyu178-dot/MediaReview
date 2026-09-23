package com.mediareview.app.feature.v2.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 480ms 稳定停留判定的 JVM 逻辑测试。
 * 规格要求的三组场景：
 * - 停留 >480ms → reviewed；
 * - 停留 200ms 后开始滑动 → NOT reviewed；
 * - 快速连续滑过多页 → 中间页 NOT reviewed。
 * 以及 reset/重复事件边界。
 */
class ReviewStableGateTest {

    private val stableMs = 480L

    @Test
    fun `停留超过480ms返回可批阅页`() {
        val gate = ReviewStableGate(stableMs)
        assertNull(gate.onEvent(2, false, 1000))
        assertEquals(2, gate.onEvent(2, false, 1000 + 500))
    }

    @Test
    fun `停留200ms后开始滑动取消等待`() {
        val gate = ReviewStableGate(stableMs)
        assertNull(gate.onEvent(2, false, 0L))
        // 200ms 后开始滑动：立即取消等待
        assertNull(gate.onEvent(2, true, 200L))
        // 之后静止需重新开始计时（不能沿用旧 baseline）
        assertNull(gate.onEvent(2, false, 500L))
        assertNull(gate.onEvent(2, false, 900L)) // 400ms 仍未达标
        assertEquals(2, gate.onEvent(2, false, 1000L)) // 500ms 达标
    }

    @Test
    fun `快速连续滑过多页中间页不标记`() {
        val gate = ReviewStableGate(stableMs)
        assertNull(gate.onEvent(0, false, 0L))
        assertNull(gate.onEvent(1, false, 50L))
        assertNull(gate.onEvent(2, false, 100L))
        assertNull(gate.onEvent(3, false, 150L))
        // 中间页均未在任何一页停留够 480ms → 都不应返回
        assertNull(gate.onEvent(3, false, 200L))
        // 最终停在最后一页并停留足够久才触发
        assertEquals(3, gate.onEvent(3, false, 700L))
    }

    @Test
    fun `reset清除等待后需重新计时`() {
        val gate = ReviewStableGate(stableMs)
        gate.onEvent(1, false, 0L)
        gate.reset()
        assertNull(gate.onEvent(1, false, 600L)) // 不沿用 reset 前的计时
        assertEquals(1, gate.onEvent(1, false, 600L + 480L))
    }

    @Test
    fun `已触发返回后同页事件需重新建立基线`() {
        val gate = ReviewStableGate(stableMs)
        gate.onEvent(1, false, 0L)
        assertEquals(1, gate.onEvent(1, false, 1000L)) // 触发标记
        // 触发后 baseline 已清：再收到同页事件不因历史时长直接达标
        assertNull(gate.onEvent(1, false, 2000L))
    }

    @Test
    fun `空页或滚动开启立即取消`() {
        val gate = ReviewStableGate(stableMs)
        gate.onEvent(1, false, 0L)
        assertNull(gate.onEvent(-1, false, 100L))
        assertNull(gate.onEvent(1, true, 100L))
    }
}