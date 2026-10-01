package com.mediareview.app.feature.v2.organize

import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.organize.data.DeleteQueueSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateSummary
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionSummary
import com.mediareview.app.feature.v2.organize.data.OrganizeFeatureUnavailableInDemoException
import com.mediareview.app.feature.v2.organize.data.ReviewProgressSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Stage 8C §54：整理首页 ViewModel —— 四张卡独立失败 / 单卡重试 / 文案与"仅服务器模式可用"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizeViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `一张卡失败不污染其他卡`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteSummaryResult = Result.failure(IllegalStateException("boom"))
            duplicateSummaryResult = Result.success(DuplicateSummary(3, 7, null, null, 100))
            reviewSummaryResult = Result.success(ReviewProgressSummary(37, 100, 63))
            librarySummaryResult = Result.success(LibrarySelectionSummary(2, 3))
        }
        val vm = OrganizeViewModel(repo)

        vm.load()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("失败卡必须是 Error（绝不变成 0）", state.deleteCard is OrganizeCard.Error)
        // 其他卡不受影响
        assertEquals(
            "完全重复 3 组 · 疑似重复 7 组",
            vm.duplicateText(state.duplicateCard.dataOrNull()!!),
        )
        assertEquals("37 / 100 · 剩余 63", vm.reviewText(state.reviewCard.dataOrNull()))
        assertEquals("已选 2 / 共 3 个媒体库", vm.libraryText(state.libraryCard.dataOrNull()!!))
    }

    @Test
    fun `单卡重试只重新请求该卡`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteSummaryResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = OrganizeViewModel(repo)
        vm.load()
        advanceUntilIdle()
        assertEquals(1, repo.deleteSummaryCalls)
        assertEquals(1, repo.duplicateSummaryCalls)

        repo.deleteSummaryResult = Result.success(DeleteQueueSummary(12, 1_800_000_000L))
        vm.retryDeleteCard()
        advanceUntilIdle()

        val card = vm.state.value.deleteCard
        assertTrue(card is OrganizeCard.Ready)
        assertEquals("12 项 · 预计释放 1.7 GB", vm.summaryText(12, 1_800_000_000L))
        assertEquals(2, repo.deleteSummaryCalls)
        // 其他卡没有被重复请求
        assertEquals(1, repo.duplicateSummaryCalls)
        assertEquals(1, repo.reviewSummaryCalls)
        assertEquals(1, repo.librarySummaryCalls)
    }

    @Test
    fun `批阅没有active会话是Ready null而不是失败`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            reviewSummaryResult = Result.success(null)
        }
        val vm = OrganizeViewModel(repo)
        vm.load()
        advanceUntilIdle()

        val card = vm.state.value.reviewCard
        assertTrue(card is OrganizeCard.Ready)
        assertEquals(null, card.dataOrNull())
        assertEquals("暂无进行中的批阅", vm.reviewText(null))
    }

    @Test
    fun `Demo 不支持的卡显示仅服务器模式可用`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            mode = com.mediareview.app.feature.v2.data.V2DataMode.DEMO
            duplicateSummaryResult =
                Result.failure(OrganizeFeatureUnavailableInDemoException("重复媒体"))
            librarySummaryResult =
                Result.failure(OrganizeFeatureUnavailableInDemoException("媒体库管理"))
        }
        val vm = OrganizeViewModel(repo)
        vm.load()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.duplicateCard is OrganizeCard.Unavailable)
        assertTrue(state.libraryCard is OrganizeCard.Unavailable)
        assertEquals(
            "仅服务器模式可用",
            (state.duplicateCard as OrganizeCard.Unavailable).message,
        )
    }

    @Test
    fun `扫描中的重复卡优先显示进度`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            duplicateSummaryResult =
                Result.success(DuplicateSummary(3, 7, "t1", "running", 62))
        }
        val vm = OrganizeViewModel(repo)
        vm.load()
        advanceUntilIdle()

        assertEquals("扫描中 62%", vm.duplicateText(vm.state.value.duplicateCard.dataOrNull()!!))
    }

    // ---------- Stage 8C.1 §24~§27 数据源切换刷新 + 迟到请求 ----------

    @Test
    fun `数据源切换到Demo后四张卡重新加载为Demo数据`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            deleteSummaryResult = Result.success(DeleteQueueSummary(1, 1_000L))
            duplicateSummaryResult = Result.success(DuplicateSummary(3, 7, null, null, 100))
            librarySummaryResult = Result.success(LibrarySelectionSummary(2, 3))
        }
        val vm = OrganizeViewModel(repo)
        vm.load()
        advanceUntilIdle()
        assertEquals(1, repo.deleteSummaryCalls)

        // 切换 Demo：必须**重新请求**（绝不复用旧 Server 卡片状态）
        repo.mode = V2DataMode.DEMO
        repo.deleteSummaryResult = Result.success(DeleteQueueSummary(3, 300L))
        repo.duplicateSummaryResult =
            Result.failure(OrganizeFeatureUnavailableInDemoException("重复媒体"))
        repo.librarySummaryResult =
            Result.failure(OrganizeFeatureUnavailableInDemoException("媒体库管理"))
        vm.load()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(V2DataMode.DEMO, state.dataMode)
        assertEquals(2, repo.deleteSummaryCalls)
        assertEquals(2, repo.duplicateSummaryCalls)
        assertEquals(2, repo.librarySummaryCalls)
        assertEquals(3, state.deleteCard.dataOrNull()!!.count)
        assertTrue(state.duplicateCard is OrganizeCard.Unavailable)
        assertTrue(state.libraryCard is OrganizeCard.Unavailable)
    }

    @Test
    fun `Demo切回Server四张卡重新请求Server`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            mode = V2DataMode.DEMO
            duplicateSummaryResult =
                Result.failure(OrganizeFeatureUnavailableInDemoException("重复媒体"))
        }
        val vm = OrganizeViewModel(repo)
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.duplicateCard is OrganizeCard.Unavailable)

        repo.mode = V2DataMode.SERVER
        repo.duplicateSummaryResult = Result.success(DuplicateSummary(5, 0, null, null, 100))
        vm.load()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(V2DataMode.SERVER, state.dataMode)
        assertEquals(2, repo.duplicateSummaryCalls)
        assertEquals("完全重复 5 组 · 疑似重复 0 组", vm.duplicateText(state.duplicateCard.dataOrNull()!!))
    }

    @Test
    fun `数据源切换后旧Server请求迟到不能覆盖Demo`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeOrganizeRepository().apply {
            holdFirstSummaryCall = gate
            deleteSummaryResult = Result.success(DeleteQueueSummary(1, 1_000L))
        }
        val vm = OrganizeViewModel(repo)
        vm.load() // 第 1 代：待删除卡挂在闸门（旧 Server 请求）
        runCurrent()

        repo.mode = V2DataMode.DEMO
        repo.deleteSummaryResult = Result.success(DeleteQueueSummary(3, 300L))
        vm.load() // 第 2 代：Demo 请求立即返回
        advanceUntilIdle()
        assertEquals(3, vm.state.value.deleteCard.dataOrNull()!!.count)

        gate.complete(Unit) // 旧 Server 响应迟到
        advanceUntilIdle()

        assertEquals(
            "旧请求迟到不得覆盖新数据源结果",
            3,
            vm.state.value.deleteCard.dataOrNull()!!.count,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> OrganizeCard<T>.dataOrNull(): T? =
        (this as? OrganizeCard.Ready<T>)?.data
}