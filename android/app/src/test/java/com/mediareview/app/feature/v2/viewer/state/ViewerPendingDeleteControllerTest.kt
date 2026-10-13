package com.mediareview.app.feature.v2.viewer.state

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

/** 通过生产控制器验证真实状态、确认写入与取消竞态。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerPendingDeleteControllerTest {
    @Test fun `failure event belongs to current session and has no undo action`() = runTest {
        val events = mutableListOf<PendingDeleteMessage>()
        val c = PendingDeleteController(backgroundScope, { emptySet() }, { _, _ -> false }, {}, events::add)
        val session = c.open(); runCurrent(); c.toggle(session, "video"); runCurrent()
        assertEquals(listOf(PendingDeleteMessage("加入待删除失败", session)), events)
        assertEquals(emptySet<String>(), ids(c))
    }
    @Test fun `undo action uses original media and cannot cross session`() = runTest {
        val events = mutableListOf<PendingDeleteMessage>()
        val writes = mutableListOf<Pair<String, Boolean>>()
        val gate = CompletableDeferred<Boolean>()
        val c = PendingDeleteController(backgroundScope, { emptySet() }, { id, target ->
            writes += id to target
            if (target) gate.await() else true
        }, {}, events::add)
        val session = c.open(); runCurrent()
        c.toggle(session, "original"); runCurrent()
        assertTrue(events.isEmpty()); assertEquals(emptySet<String>(), ids(c))
        gate.complete(true); runCurrent()
        val action = events.single()
        assertEquals(PendingDeleteMessage("已加入待删除", session, "original"), action)
        c.undo(action.session, action.undoMediaId!!); runCurrent()
        assertEquals(listOf("original" to true, "original" to false), writes)
        assertEquals(emptySet<String>(), ids(c))
        c.open(); runCurrent(); c.undo(action.session, "original"); runCurrent()
        assertEquals(2, writes.size)
    }
    private fun controller(scope: CoroutineScope, read: suspend () -> Set<String>, write: suspend (String, Boolean) -> Boolean, message: (String) -> Unit = {}) = ViewerPendingDeleteController(scope, read, write, message)
    private fun state(c: ViewerPendingDeleteController): ViewerPendingDeleteState = c.state.value
    private fun ids(c: ViewerPendingDeleteController): Set<String> = (state(c) as ViewerPendingDeleteState.Ready).ids
    private fun busy(c: ViewerPendingDeleteController): Boolean = c.busy.value
    private fun open(c: ViewerPendingDeleteController): Long = c.open()
    @Test fun `successful add exposes confirmed feedback`() = runTest {
        val messages = mutableListOf<String>()
        val c = controller(backgroundScope, { emptySet() }, { _, _ -> true }, messages::add)
        val session = open(c); runCurrent(); c.toggle(session, "video"); runCurrent()
        assertEquals(listOf("已加入待删除"), messages)
    }
    @Test fun `existing queue is loaded and reopen sees Organize undo`() = runTest {
        var queue = setOf("img"); var reads = 0
        val c = controller(backgroundScope, { reads++; queue }, { _, _ -> true })
        val first = open(c); assertEquals("Loading", state(c).javaClass.simpleName)
        c.toggle(first, "img"); runCurrent(); assertEquals(setOf("img"), ids(c))
        c.close(first); queue = emptySet(); open(c); runCurrent()
        assertEquals(emptySet<String>(), ids(c)); assertEquals(2, reads)
    }
    @Test fun `add and undo publish only confirmed queue with duplicate suppressed`() = runTest {
        var queue = emptySet<String>(); var writes = 0; var gate = CompletableDeferred<Boolean>()
        val c = controller(backgroundScope, { queue }, { id, target ->
            writes++; val ok = gate.await(); if (ok) queue = if (target) queue + id else queue - id; ok
        })
        val session = open(c); runCurrent()
        c.toggle(session, "img"); c.toggle(session, "img"); runCurrent()
        assertTrue(busy(c)); assertEquals(emptySet<String>(), ids(c)); assertEquals(1, writes)
        gate.complete(true); runCurrent(); assertEquals(queue, ids(c)); assertFalse(busy(c))
        gate = CompletableDeferred(); c.toggle(session, "img"); runCurrent()
        assertEquals(setOf("img"), ids(c)); gate.complete(true); runCurrent()
        assertEquals(emptySet<String>(), ids(c)); assertEquals(queue, ids(c))
    }
    @Test fun `add and undo failures retain icons with Chinese feedback`() = runTest {
        for (initial in listOf(emptySet<String>(), setOf("img"))) {
            val messages = mutableListOf<String>()
            val c = controller(backgroundScope, { initial }, { _, _ -> false }, messages::add)
            val session = open(c); runCurrent(); c.toggle(session, "img"); runCurrent()
            assertEquals(initial, ids(c)); assertFalse(busy(c))
            assertEquals(listOf(if (initial.isEmpty()) "加入待删除失败" else "撤销待删除失败"), messages)
        }
    }
    @Test fun `read failure stays unknown and retry reloads queue`() = runTest {
        var fail = true; var writes = 0
        val c = controller(backgroundScope, { if (fail) error("offline") else setOf("img") }, { _, _ -> writes++; true })
        val session = open(c); runCurrent(); assertEquals("Error", state(c).javaClass.simpleName)
        c.toggle(session, "img"); runCurrent(); assertEquals(0, writes)
        fail = false; c.retry(session); runCurrent(); assertEquals(setOf("img"), ids(c))
    }
    @Test fun `late read cannot overwrite new session`() = runTest {
        val gate = CompletableDeferred<Unit>(); var reads = 0
        val c = controller(backgroundScope, {
            if (++reads == 1) { withContext(NonCancellable) { gate.await() }; setOf("old") } else setOf("new")
        }, { _, _ -> true })
        open(c); runCurrent(); open(c); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(setOf("new"), ids(c))
    }
    @Test fun `source invalidation ignores late write and stale disposal`() = runTest {
        val gate = CompletableDeferred<Unit>(); var queue = emptySet<String>(); val messages = mutableListOf<String>()
        val c = controller(backgroundScope, { queue }, { _, _ -> withContext(NonCancellable) { gate.await() }; false }, messages::add)
        val old = open(c); runCurrent(); c.toggle(old, "img"); runCurrent()
        c.invalidate(); queue = setOf("new"); open(c); runCurrent(); c.close(old)
        gate.complete(Unit); runCurrent(); assertEquals(setOf("new"), ids(c)); assertTrue(messages.isEmpty()); assertFalse(busy(c))
    }
    @Test fun `cancellation is propagated without fake failure`() = runTest {
        val messages = mutableListOf<String>()
        val c = controller(backgroundScope, { emptySet() }, { _, _ -> throw CancellationException("cancel") }, messages::add)
        val session = open(c); runCurrent(); c.toggle(session, "img"); runCurrent()
        assertFalse(busy(c)); assertEquals(emptySet<String>(), ids(c)); assertTrue(messages.isEmpty())
    }
}
