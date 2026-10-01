package com.mediareview.app.feature.v2.organize.duplicates

import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.feature.v2.organize.FakeOrganizeRepository
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupDetail
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateScanState
import com.mediareview.app.feature.v2.organize.fakeMember
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Stage 8C §54：重复媒体 ViewModel —— 扫描恢复/轮询/成功重载/暂停继续取消/防重，对比页 keep 确认。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicatesViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun group(id: String, type: String = "exact") =
        DuplicateGroupSummary(id, type, 2, 1_000, "detail-$id")

    @Test
    fun `进入页面恢复扫描状态并加载分组`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            scanStatusResult = Result.success(DuplicateScanState("t1", "succeeded", 100, null))
            exactGroups = listOf(group("exact:1"))
            similarGroups = listOf(group("similar:1", "similar"))
        }
        val vm = DuplicatesViewModel(repo)

        vm.enterScreen()
        advanceUntilIdle()

        val ui = vm.ui.value
        assertEquals("succeeded", ui.scan.status)
        assertEquals(1, ui.exact.size)
        assertEquals(1, ui.similar.size)
        assertFalse(ui.loading)
        vm.leaveScreen()
    }

    @Test
    fun `进入时扫描进行中会轮询到成功并自动重载分组`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            scanStatusSequence += DuplicateScanState("t1", "running", 62, null)
            scanStatusSequence += DuplicateScanState("t1", "succeeded", 100, null)
            exactGroups = listOf(group("exact:1"))
        }
        val vm = DuplicatesViewModel(repo)

        vm.enterScreen()
        advanceUntilIdle()

        assertEquals("succeeded", vm.ui.value.scan.status)
        assertEquals(1, vm.ui.value.exact.size)
        // 初始加载 2 次（exact+similar）+ 成功后自动重载 2 次 = 4
        assertEquals(4, repo.loadGroupsCalls)
        vm.leaveScreen()
    }

    @Test
    fun `暂停继续取消走服务端任务接口`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            scanStatusResult = Result.success(DuplicateScanState("t1", "running", 50, null))
            pauseResult = Result.success(DuplicateScanState("t1", "paused", 50, null))
            resumeResult = Result.success(DuplicateScanState("t1", "pending", 50, null))
            cancelResult = Result.success(DuplicateScanState("t1", "cancelled", 50, null))
        }
        val vm = DuplicatesViewModel(repo)
        vm.enterScreen()
        runCurrent() // 只运行即时任务，不推进轮询时间

        vm.pauseScan()
        runCurrent()
        assertEquals(1, repo.pauseCalls)
        assertEquals("paused", vm.ui.value.scan.status)

        vm.resumeScan()
        runCurrent()
        assertEquals(1, repo.resumeCalls)
        assertEquals("pending", vm.ui.value.scan.status)

        vm.cancelScan()
        runCurrent()
        assertEquals(1, repo.cancelCalls)
        assertEquals("cancelled", vm.ui.value.scan.status)
        vm.leaveScreen()
    }

    @Test
    fun `连续点击同一控制按钮只发一次请求`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            scanStatusResult = Result.success(DuplicateScanState("t1", "running", 50, null))
        }
        val vm = DuplicatesViewModel(repo)
        vm.enterScreen()
        runCurrent()

        // 同一帧内连点两次：同步置忙必须挡住第二次
        vm.pauseScan()
        vm.pauseScan()
        runCurrent()
        assertEquals(1, repo.pauseCalls)
        vm.leaveScreen()
    }

    @Test
    fun `触发扫描后进入轮询`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            scanStatusResult = Result.success(DuplicateScanState(null, null, 0, null))
            startScanResult = Result.success(DuplicateScanState("t1", "pending", 0, null))
        }
        val vm = DuplicatesViewModel(repo)
        vm.enterScreen()
        runCurrent() // 无任务：不轮询

        vm.startScan()
        runCurrent() // 已发出扫描请求，进入 pending 轮询（delay 待触发）
        repo.scanStatusSequence += DuplicateScanState("t1", "running", 10, null)
        repo.scanStatusSequence += DuplicateScanState("t1", "succeeded", 100, null)
        advanceUntilIdle()

        assertEquals(1, repo.startScanCalls)
        assertEquals("succeeded", vm.ui.value.scan.status)
        vm.leaveScreen()
    }

    @Test
    fun `扫描失败时提示但不伪装成功`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            scanStatusResult = Result.success(DuplicateScanState(null, null, 0, null))
            startScanResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = DuplicatesViewModel(repo)
        vm.enterScreen()
        advanceUntilIdle()

        vm.startScan()
        advanceUntilIdle()

        assertEquals(1, repo.startScanCalls)
        assertEquals(null, vm.ui.value.scan.status)
        assertFalse(vm.ui.value.scanBusy)
        vm.leaveScreen()
    }

    @Test
    fun `Demo 模式显示仅服务器模式可用`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            scanStatusResult =
                Result.failure(
                    com.mediareview.app.feature.v2.organize.data
                        .OrganizeFeatureUnavailableInDemoException("重复媒体"),
                )
        }
        val vm = DuplicatesViewModel(repo)
        vm.enterScreen()
        advanceUntilIdle()

        assertTrue(vm.ui.value.demoUnavailable)
        vm.leaveScreen()
    }

    // ---------- 对比页 ----------

    @Test
    fun `对比页一次加载并服务端确认保留`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            detailResult = Result.success(
                DuplicateGroupDetail(
                    groupId = "g1",
                    type = "exact",
                    detail = "byte-identical",
                    count = 2,
                    sizeBytes = 1_000,
                    members = listOf(fakeMember("aaa"), fakeMember("bbb")),
                ),
            )
        }
        val vm = DuplicateCompareViewModel(repo)
        vm.load("g1")
        advanceUntilIdle()
        assertEquals(2, vm.ui.value.detail?.members?.size)

        vm.setKeep("g1", "aaa", true)
        advanceUntilIdle()

        assertEquals(listOf("g1" to "aaa"), repo.keepCalls)
        assertTrue(vm.ui.value.detail!!.members.first { it.media.id == "aaa" }.keep)
        assertFalse(vm.ui.value.detail!!.members.first { it.media.id == "bbb" }.keep)
    }

    @Test
    fun `保留失败不改变UI状态`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            detailResult = Result.success(
                DuplicateGroupDetail("g1", "exact", "", 2, 1_000, listOf(fakeMember("aaa"))),
            )
            keepResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = DuplicateCompareViewModel(repo)
        vm.load("g1")
        advanceUntilIdle()

        vm.setKeep("g1", "aaa", true)
        advanceUntilIdle()

        assertFalse(vm.ui.value.detail!!.members.first().keep)
        assertFalse(vm.ui.value.keepBusy)
    }
}