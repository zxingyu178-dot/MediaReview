package com.mediareview.app.feature.v2.organize.delete

import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.feature.v2.organize.FakeOrganizeRepository
import com.mediareview.app.feature.v2.organize.data.DeleteCommitPrepare
import com.mediareview.app.feature.v2.organize.data.DeleteQueueEntry
import com.mediareview.app.feature.v2.organize.fakeMedia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Stage 8C §54：待删除 ViewModel —— 列表/恢复服务端确认、prepare 快照、commit single-flight、
 * 结果逐类汇总、失败项留队列、最终删除完成事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteQueueViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun entry(id: String, status: String = "pending", size: Long = 1_000) =
        DeleteQueueEntry(
            mediaId = id,
            status = status,
            sizeBytes = size,
            addedAt = "2026-09-30T10:00:00",
            media = fakeMedia(id),
            coverUri = "http://server/cover/$id",
        )

    private fun prepare(count: Int = 2, bytes: Long = 2_000) =
        DeleteCommitPrepare("nonce-1", "2026-10-01T12:10:00", count, bytes, listOf("aaa", "bbb"))

    @Test
    fun `列表加载与统计`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteQueueResult = Result.success(
                listOf(entry("aaa"), entry("bbb"), entry("ccc", status = "failed")),
            )
        }
        val vm = DeleteQueueViewModel(repo)
        vm.load()
        advanceUntilIdle()

        val ui = vm.ui.value
        assertFalse(ui.loading)
        assertEquals(3, ui.entries.size)
        assertEquals(2, ui.pendingCount)
        assertEquals(2_000L, ui.pendingBytes)
    }

    @Test
    fun `恢复失败保持列表并提示`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteQueueResult = Result.success(listOf(entry("aaa")))
            restoreResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = DeleteQueueViewModel(repo)
        val events = mutableListOf<DeleteQueueEvent>()
        val collector = launch { vm.events.collect { events += it } }
        vm.load()
        advanceUntilIdle()
        val loadsBefore = repo.loadDeleteQueueCalls

        vm.restore("aaa")
        advanceUntilIdle()

        assertEquals(listOf("aaa"), repo.restoreCalls)
        assertEquals("恢复失败后不得触发列表刷新", loadsBefore, repo.loadDeleteQueueCalls)
        assertEquals(1, vm.ui.value.entries.size)
        assertTrue(events.any { it is DeleteQueueEvent.Info && it.text == "恢复失败，请重试" })
        collector.cancel()
    }

    @Test
    fun `恢复成功刷新列表`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteQueueResult = Result.success(listOf(entry("aaa")))
        }
        val vm = DeleteQueueViewModel(repo)
        vm.load()
        advanceUntilIdle()
        val loadsBefore = repo.loadDeleteQueueCalls

        vm.restore("aaa")
        advanceUntilIdle()

        assertEquals(loadsBefore + 1, repo.loadDeleteQueueCalls)
    }

    @Test
    fun `prepare 之后才进入待确认且数字来自响应`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            prepareResult = Result.success(prepare(count = 12, bytes = 1_800_000_000L))
        }
        val vm = DeleteQueueViewModel(repo)

        vm.requestFinalDelete()
        advanceUntilIdle()

        val phase = vm.ui.value.phase
        assertTrue(phase is DeleteCommitPhase.ReadyToConfirm)
        assertEquals(12, (phase as DeleteCommitPhase.ReadyToConfirm).prepare.count)
        assertEquals(1_800_000_000L, phase.prepare.totalBytes)
    }

    @Test
    fun `prepare 失败回到 Idle 并提示`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            prepareResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = DeleteQueueViewModel(repo)
        val events = mutableListOf<DeleteQueueEvent>()
        val collector = launch { vm.events.collect { events += it } }

        vm.requestFinalDelete()
        advanceUntilIdle()

        assertEquals(DeleteCommitPhase.Idle, vm.ui.value.phase)
        assertTrue(events.any { it is DeleteQueueEvent.Info && it.text == "无法发起删除确认，请重试" })
        collector.cancel()
    }

    @Test
    fun `同一 nonce 只提交一次且结果逐类汇总`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteQueueResult = Result.success(listOf(entry("aaa"), entry("bbb"), entry("ccc")))
            prepareResult = Result.success(prepare())
            commitResult = Result.success(
                mapOf(
                    "aaa" to DeleteOutcomeStatus.Success,
                    "bbb" to DeleteOutcomeStatus.Missing,
                    "ccc" to DeleteOutcomeStatus.Failed,
                ),
            )
        }
        val vm = DeleteQueueViewModel(repo)
        val events = mutableListOf<DeleteQueueEvent>()
        val collector = launch { vm.events.collect { events += it } }
        vm.load()
        advanceUntilIdle()

        vm.requestFinalDelete()
        advanceUntilIdle()
        vm.confirmFinalDelete()
        // 状态机进入 Committing 前重复点击：绝不产生第二次 commit
        vm.confirmFinalDelete()
        advanceUntilIdle()

        assertEquals(1, repo.commitCalls)
        assertEquals(listOf("nonce-1"), repo.committedNonces)
        val phase = vm.ui.value.phase
        assertTrue(phase is DeleteCommitPhase.Result)
        val result = (phase as DeleteCommitPhase.Result).result
        assertEquals(1, result.successCount)
        assertEquals(1, result.missingCount)
        assertEquals(1, result.failedCount)
        assertEquals(listOf("媒体 ccc"), result.failedNames)
        assertEquals(listOf("aaa", "bbb"), result.changedMediaIds)
        // 完成事件携带 success+missing 的媒体（供 V2 刷新与缓存丢弃）
        val completed = events.filterIsInstance<DeleteQueueEvent.FinalDeleteCompleted>()
        assertEquals(1, completed.size)
        assertEquals(listOf("aaa", "bbb"), completed.first().changedMediaIds)
        // 提交后刷新列表（失败项仍留在队列,用户停留在本页可见）
        assertTrue(repo.loadDeleteQueueCalls >= 2)
        collector.cancel()
    }

    @Test
    fun `提交期间取消被忽略`() = runTest(main.dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = FakeOrganizeRepository().apply {
            prepareResult = Result.success(prepare())
            commitResult = Result.success(mapOf("aaa" to DeleteOutcomeStatus.Success))
            commitGate = gate
        }
        val vm = DeleteQueueViewModel(repo)
        vm.requestFinalDelete()
        advanceUntilIdle()
        vm.confirmFinalDelete()
        advanceUntilIdle()
        assertTrue("提交已发出,状态必须是 Committing", vm.ui.value.phase is DeleteCommitPhase.Committing)

        // Committing 期间的"取消"必须被忽略（不能取消已发出的提交）
        vm.cancelFinalDelete()
        assertTrue(vm.ui.value.phase is DeleteCommitPhase.Committing)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.ui.value.phase is DeleteCommitPhase.Result)
    }

    @Test
    fun `提交失败回到Idle并提示`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            prepareResult = Result.success(prepare())
            commitResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = DeleteQueueViewModel(repo)
        val events = mutableListOf<DeleteQueueEvent>()
        val collector = launch { vm.events.collect { events += it } }
        vm.requestFinalDelete()
        advanceUntilIdle()
        vm.confirmFinalDelete()
        advanceUntilIdle()

        assertEquals(DeleteCommitPhase.Idle, vm.ui.value.phase)
        assertTrue(events.any { it is DeleteQueueEvent.Info && it.text == "删除提交失败，请重试" })
        collector.cancel()
    }

    @Test
    fun `列表加载失败不显示为空队列`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteQueueResult = Result.failure(IllegalStateException("网络失败"))
        }
        val vm = DeleteQueueViewModel(repo)
        vm.load()
        advanceUntilIdle()

        val ui = vm.ui.value
        assertFalse(ui.loading)
        assertEquals("网络失败", ui.error)
        assertTrue(ui.entries.isEmpty())
    }
}