package com.mediareview.app.feature.v2.organize

import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.feature.v2.organize.data.DeleteQueueSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateSummary
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionSummary
import com.mediareview.app.feature.v2.organize.data.OrganizeFeatureUnavailableInDemoException
import com.mediareview.app.feature.v2.organize.data.ReviewProgressSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> OrganizeCard<T>.dataOrNull(): T? =
        (this as? OrganizeCard.Ready<T>)?.data
}