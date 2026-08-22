package com.mediareview.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段 12:批阅双播放器槽位状态机(纯逻辑)。 */
class ReviewPlayerSlotsTest {

    @Test
    fun `首次切换未预加载任何项时目标为 A 且未就绪`() {
        val slots = ReviewPlayerSlots()
        val (targetIsB, alreadyReady) = slots.decideTarget(0)
        assertFalse(targetIsB)
        assertFalse(alreadyReady)
    }

    @Test
    fun `B 槽预加载下一项后切换过去应已就绪`() {
        val slots = ReviewPlayerSlots()
        slots.markReady(slotB = true, index = 1) // 预加载下一项(索引 1)
        val (targetIsB, alreadyReady) = slots.decideTarget(1)
        assertTrue(targetIsB)
        assertTrue(alreadyReady)
    }

    @Test
    fun `停在当前项时 A 槽已就绪`() {
        val slots = ReviewPlayerSlots()
        slots.markReady(slotB = false, index = 0)
        val (targetIsB, alreadyReady) = slots.decideTarget(0)
        assertFalse(targetIsB)
        assertTrue(alreadyReady)
    }

    @Test
    fun `停止非活动槽后其 ready 被清空(防 stale-ready)`() {
        val slots = ReviewPlayerSlots()
        slots.markActive(slotB = true) // 当前播放的是 B(已预加载的下一项)
        slots.markReady(slotB = true, index = 1) // B 就绪 1
        slots.markReady(slotB = false, index = 0) // A 就绪 0

        // 当前(B)缓冲 → 停止非活动槽 A 并清空其 ready
        slots.stopInactive()
        assertEquals(-1, slots.readyA)

        // 快速滑回索引 0:A 的 ready 已被清空,目标应为 A 且未就绪(需要重新 prepare)
        val (targetIsB, alreadyReady) = slots.decideTarget(0)
        assertFalse(targetIsB)
        assertFalse(alreadyReady)
        // 而 B 的 ready 仍指向 1,不受影响
        assertEquals(1, slots.readyB)
    }

    @Test
    fun `stopSlot 清空对应槽位 ready`() {
        val slots = ReviewPlayerSlots()
        slots.markReady(slotB = true, index = 3)
        slots.stopSlot(slotB = true)
        assertEquals(-1, slots.readyB)
        val (targetIsB, alreadyReady) = slots.decideTarget(3)
        assertFalse(targetIsB) // 不再选中已停止的 B
        assertFalse(alreadyReady)
    }

    @Test
    fun `reset 清空全部状态`() {
        val slots = ReviewPlayerSlots()
        slots.markReady(slotB = true, index = 2)
        slots.markReady(slotB = false, index = 1)
        slots.markActive(slotB = true)
        slots.reset()
        assertEquals(-1, slots.readyA)
        assertEquals(-1, slots.readyB)
        assertFalse(slots.activeIsB)
    }

    // ---- 阶段 15:settle → prepareNext 交替槽位流程(播放器状态相关) ----

    @Test
    fun `settle 到未就绪项后 prepareNext 预加载下一槽`() {
        val slots = ReviewPlayerSlots()
        // 停到索引 0:A 槽现场 prepare 并激活
        slots.markActive(slotB = false)
        slots.markReady(slotB = false, index = 0)
        // P1 后台:下一条(索引 1)预加载到 B 槽
        slots.markReady(slotB = true, index = 1)
        // 快速滑到下一条:目标应为 B 且已就绪
        val (targetIsB, alreadyReady) = slots.decideTarget(1)
        assertTrue(targetIsB)
        assertTrue(alreadyReady)
    }

    @Test
    fun `连续 settle 交替使用 A B 槽且不串位`() {
        val slots = ReviewPlayerSlots()
        // 索引 0:A 就绪并激活
        slots.markActive(false)
        slots.markReady(false, 0)
        // P1 预加载索引 1 到 B
        slots.markReady(true, 1)
        // settle(1): 切到 B(就绪)
        slots.markActive(true)
        // P1 预加载索引 2 到 A(非活动槽)
        slots.markReady(false, 2)
        val (t2, r2) = slots.decideTarget(2)
        assertFalse(t2)
        assertTrue(r2)
        // settle(2): 切回 A(就绪)
        slots.markActive(false)
        // 快速连滑到 3:此时 B 未预加载 3 → 目标 A/B?decideTarget(3) 都不 ready → 需现场 prepare
        val (t3, r3) = slots.decideTarget(3)
        assertFalse(r3)
        // 3 未被任何槽就绪,不应误选 B
        assertFalse(t3)
    }

    @Test
    fun `切换后旧槽 ready 保留但不再被选中新索引`() {
        val slots = ReviewPlayerSlots()
        slots.markActive(false)
        slots.markReady(false, 0)
        slots.markReady(true, 1)
        // settle(1) 切到 B
        slots.markActive(true)
        // 旧 A 槽 ready 仍为 0,但不影响目标 1/2 的选择
        assertEquals(0, slots.readyA)
        assertEquals(1, slots.readyB)
        val (target, _) = slots.decideTarget(1)
        assertTrue(target)
        val (target2, ready2) = slots.decideTarget(2)
        assertFalse(target2)
        assertFalse(ready2)
    }
}
