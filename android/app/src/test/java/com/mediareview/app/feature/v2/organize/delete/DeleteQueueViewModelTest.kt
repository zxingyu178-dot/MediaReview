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
import org.junit.Assert.assertNull
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
    fun `真实Receipt不借用prepare估算且刷新失败保留队列`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteQueueResult = Result.success(listOf(entry("aaa"), entry("ccc")))
            prepareResult = Result.success(prepare(bytes = 999999999L))
            commitResult = Result.success(mapOf("aaa" to DeleteOutcomeStatus.Success, "ccc" to DeleteOutcomeStatus.Failed))
            diskReceipt = com.mediareview.app.feature.v2.organize.data.DiskDeletionReceipt(1, 0, 0, 1)
        }
        val vm = DeleteQueueViewModel(repo)
        vm.load(); advanceUntilIdle()
        vm.requestFinalDelete(); advanceUntilIdle()
        repo.deleteQueueResult = Result.failure(IllegalStateException("刷新离线"))
        vm.confirmFinalDelete(); advanceUntilIdle()
        val result = (vm.ui.value.phase as DeleteCommitPhase.Result).result
        assertEquals(0L, result.diskReceipt!!.deletedBytes)
        assertEquals(listOf("媒体 ccc"), result.failedNames)
        assertEquals(2, vm.ui.value.entries.size)
        assertTrue(vm.ui.value.error != null)
        repo.deleteQueueResult = Result.success(listOf(entry("ccc", status = "failed")))
        vm.load(); advanceUntilIdle()
        assertEquals(listOf("ccc"), vm.ui.value.entries.map { it.mediaId })
    }

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
            deleteQueueResult = Result.success(listOf(entry("aaa"), entry("bbb"), entry("ccc"), entry("ddd")))
            prepareResult = Result.success(prepare())
            commitResult = Result.success(
                mapOf(
                    "aaa" to DeleteOutcomeStatus.Success,
                    "bbb" to DeleteOutcomeStatus.Missing,
                    "ccc" to DeleteOutcomeStatus.Failed,
                    "ddd" to DeleteOutcomeStatus.Pending,
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
        assertEquals(1, result.pendingCount)
        assertEquals("删除请求已提交，等待系统完成", result.pendingMessage)
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
        assertTrue(events.any { it is DeleteQueueEvent.Info && it.text == "删除结果未确认，请刷新队列核对，勿重复提交" })
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

    // ---------- Stage 8C.1 §21~§23 failed 语义 ----------

    @Test
    fun `失败原因映射为简短中文而不是原始异常`() {
        assertEquals("文件已发生变化", deleteFailureReason("file_size_changed"))
        assertEquals("文件已发生变化", deleteFailureReason("file_modified_changed"))
        assertEquals(
            "文件已不在允许的媒体库，无法确认身份",
            deleteFailureReason("library_not_allowed"),
        )
        assertEquals("文件访问失败", deleteFailureReason("stat_failed:[Errno 13] Permission denied"))
        assertEquals("删除失败", deleteFailureReason("some_unexpected_python_traceback"))
        assertNull(deleteFailureReason(null))
        assertNull(deleteFailureReason(""))
    }

    /** Stage 9：本地源两阶段安全删除的新失败码 → 中文，且绝不回显服务端绝对路径。 */
    @Test
    fun `本地源删除失败码映射为中文且不泄漏绝对路径`() {
        assertEquals("本地媒体源已停用，无法删除", deleteFailureReason("local_root_disabled"))
        assertEquals(
            "本地媒体源目录不存在或不可访问",
            deleteFailureReason("local_root_missing"),
        )
        assertEquals(
            "文件不在允许的媒体库内，已拒绝删除",
            deleteFailureReason("path_rejected:D:\\Media\\secret\\a.mp4"),
        )
        assertEquals(
            "文件身份已变化，请刷新队列后重新标记",
            deleteFailureReason("file_identity_changed"),
        )
        assertEquals(
            "文件正在被占用，请稍后重试",
            deleteFailureReason("file_in_use:[WinError 32] D:\\Media\\secret\\b.mp4"),
        )
        assertEquals(
            "没有权限删除该文件",
            deleteFailureReason("delete_access_denied:D:\\Media\\secret\\b.mp4"),
        )
        assertEquals(
            "删除结果未通过校验，请刷新队列核对",
            deleteFailureReason("delete_not_verified"),
        )
        // 未知码（可能带路径）不得原样展示
        assertEquals("删除失败", deleteFailureReason("some_new_code:D:\\Media\\secret\\c.mp4"))
        listOf(
            "local_root_disabled", "local_root_missing", "path_rejected:D:\\Media\\secret\\a.mp4",
            "file_identity_changed", "file_in_use:D:\\Media\\secret\\b.mp4",
            "delete_access_denied:D:\\Media\\secret\\b.mp4", "delete_not_verified",
            "some_new_code:D:\\Media\\secret\\c.mp4",
        ).forEach { raw ->
            val shown = deleteFailureReason(raw).orEmpty()
            assertFalse("不得展示绝对路径: $shown", shown.contains(":\\"))
        }
    }

    @Test
    fun `failed 项进入列表时携带服务端原因`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteQueueResult = Result.success(
                listOf(
                    DeleteQueueEntry(
                        mediaId = "bad",
                        status = "failed",
                        sizeBytes = 100,
                        addedAt = null,
                        media = fakeMedia("bad"),
                        coverUri = null,
                        error = "file_size_changed",
                    ),
                ),
            )
        }
        val vm = DeleteQueueViewModel(repo)
        vm.load()
        advanceUntilIdle()

        val entry = vm.ui.value.entries.single { it.status == "failed" }
        assertEquals("file_size_changed", entry.error)
        assertEquals(0, vm.ui.value.pendingCount) // failed 项不会进入下一次 prepare
    }
}
