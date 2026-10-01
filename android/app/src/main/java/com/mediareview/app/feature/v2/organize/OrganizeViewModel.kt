package com.mediareview.app.feature.v2.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.organize.data.DeleteQueueSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateSummary
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionSummary
import com.mediareview.app.feature.v2.organize.data.OrganizeFeatureUnavailableInDemoException
import com.mediareview.app.feature.v2.organize.data.ReviewProgressSummary
import com.mediareview.app.feature.v2.organize.data.V2OrganizeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 整理首页 ViewModel（Stage 8C §9/§44；Stage 8C.1 §24~§27）。
 *
 * - 四张卡（待删除 / 重复媒体 / 批阅进度 / 媒体库管理）**各自独立请求**，
 *   一张失败不污染其他卡，且可单卡重试；
 * - 任何失败都进入 `OrganizeCard.Error`，绝不变成 0（§45）；
 * - Demo 模式下重复媒体 / 媒体库明确显示「仅服务器模式可用」（§43）；
 * - **数据源切换必刷新 + 旧请求迟到不覆盖**：每次 `load()` 递增代次，单卡结果写回前
 *   同时校验「代次 + 当前数据源 + 本卡最新请求」；单卡重试同样绑定 requestedMode。
 */
@HiltViewModel
class OrganizeViewModel @Inject constructor(
    private val repository: V2OrganizeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OrganizeUiState(dataMode = repository.mode))
    val state: StateFlow<OrganizeUiState> = _state.asStateFlow()

    /** 整页加载代次（§26）：load() 时 +1，旧代次请求迟到一律丢弃。 */
    private var loadGeneration = 0

    /** 单卡代次：同一张卡连续重试只应用最后一次请求（防旧结果覆盖新结果）。 */
    private var deleteSeq = 0
    private var duplicateSeq = 0
    private var reviewSeq = 0
    private var librarySeq = 0

    /** 每次进入整理页 / 数据源变化时刷新四张卡（四张卡并行、互不等待，绝不整页 Loading）。 */
    fun load() {
        val generation = ++loadGeneration
        _state.update { it.copy(dataMode = repository.mode) }
        loadDeleteCard(generation)
        loadDuplicateCard(generation)
        loadReviewCard(generation)
        loadLibraryCard(generation)
    }

    fun retryDeleteCard() = loadDeleteCard(loadGeneration)

    fun retryDuplicateCard() = loadDuplicateCard(loadGeneration)

    fun retryReviewCard() = loadReviewCard(loadGeneration)

    fun retryLibraryCard() = loadLibraryCard(loadGeneration)

    // ---------- 单卡加载 ----------

    private fun loadDeleteCard(generation: Int) {
        val seq = ++deleteSeq
        val requestedMode = repository.mode
        viewModelScope.launch {
            _state.update { it.copy(deleteCard = OrganizeCard.Loading) }
            val card = loadCard { repository.deleteSummary() }
            if (isCurrent(generation, seq, deleteSeq, requestedMode)) {
                _state.update { it.copy(deleteCard = card) }
            }
        }
    }

    private fun loadDuplicateCard(generation: Int) {
        val seq = ++duplicateSeq
        val requestedMode = repository.mode
        viewModelScope.launch {
            _state.update { it.copy(duplicateCard = OrganizeCard.Loading) }
            val card = loadCard { repository.duplicateSummary() }
            if (isCurrent(generation, seq, duplicateSeq, requestedMode)) {
                _state.update { it.copy(duplicateCard = card) }
            }
        }
    }

    private fun loadReviewCard(generation: Int) {
        val seq = ++reviewSeq
        val requestedMode = repository.mode
        viewModelScope.launch {
            _state.update { it.copy(reviewCard = OrganizeCard.Loading) }
            // Ready(null) = 服务端明确"没有进行中的批阅"，与网络失败区分
            val card = loadCard { repository.reviewSummary() }
            if (isCurrent(generation, seq, reviewSeq, requestedMode)) {
                _state.update { it.copy(reviewCard = card) }
            }
        }
    }

    private fun loadLibraryCard(generation: Int) {
        val seq = ++librarySeq
        val requestedMode = repository.mode
        viewModelScope.launch {
            _state.update { it.copy(libraryCard = OrganizeCard.Loading) }
            val card = loadCard { repository.librarySummary() }
            if (isCurrent(generation, seq, librarySeq, requestedMode)) {
                _state.update { it.copy(libraryCard = card) }
            }
        }
    }

    /**
     * 结果写回资格（Stage 8C.1 §26/§27）：
     * 代次仍当前 + 本卡仍是最新请求 + 数据源仍是发起时的模式，三者缺一不可。
     */
    private fun isCurrent(
        generation: Int,
        seq: Int,
        latestSeq: Int,
        requestedMode: V2DataMode,
    ): Boolean =
        generation == loadGeneration && seq == latestSeq && repository.mode == requestedMode

    /** 统一错误语义：Demo 无能力 → Unavailable；其余失败 → Error（绝不返回空数据）。 */
    private suspend fun <T> loadCard(block: suspend () -> T): OrganizeCard<T> = try {
        OrganizeCard.Ready(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: OrganizeFeatureUnavailableInDemoException) {
        OrganizeCard.Unavailable("仅服务器模式可用")
    } catch (error: Throwable) {
        OrganizeCard.Error(error.message ?: "加载失败")
    }

    /** 供测试与卡片文案使用（避免外部重复构造）。 */
    fun summaryText(count: Int, totalBytes: Long): String = when {
        count <= 0 -> "暂无待删除内容"
        else -> "$count 项 · 预计释放 ${formatBytes(totalBytes)}"
    }

    /** 重复媒体卡片文案（扫描中优先显示进度）。 */
    fun duplicateText(summary: DuplicateSummary): String = when {
        summary.scanStatus == "pending" || summary.scanStatus == "running" ->
            "扫描中 ${summary.scanProgress}%"
        summary.exactGroups <= 0 && summary.similarGroups <= 0 -> "未发现重复媒体"
        else -> "完全重复 ${summary.exactGroups} 组 · 疑似重复 ${summary.similarGroups} 组"
    }

    /** 批阅进度卡片文案（active 会话 = 进行中；无 active = 不伪造历史累计）。 */
    fun reviewText(summary: ReviewProgressSummary?): String = summary
        ?.let { "${it.seen} / ${it.total} · 剩余 ${it.remaining}" }
        ?: "暂无进行中的批阅"

    /** 媒体库卡片文案。 */
    fun libraryText(summary: LibrarySelectionSummary): String =
        "已选 ${summary.selected} / 共 ${summary.total} 个媒体库"
}