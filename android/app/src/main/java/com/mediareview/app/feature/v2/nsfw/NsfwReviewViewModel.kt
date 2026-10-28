package com.mediareview.app.feature.v2.nsfw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.nsfw.data.NsfwApiException
import com.mediareview.app.feature.v2.nsfw.data.NsfwDecisionResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwErrorCodes
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationRequest
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedStore
import com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairingGateway
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptImageItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptLink
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptSummary
import com.mediareview.app.feature.v2.nsfw.data.NsfwReviewDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class NsfwPhase { LOADING, UNPAIRED, READY }

/** 提示词评分面板（同一 prompt 版本共享；版本未知时必须显式选择整体评分）。 */
data class NsfwPromptPanel(
    val promptId: String,
    val versionId: String?,
    val linkState: String,
    val promptSnapshot: String? = null,
    val summary: NsfwPromptSummary? = null,
    val images: List<NsfwPromptImageItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val submitted: NsfwEvaluationResult? = null,
    val unconfirmed: Boolean = false,
    val message: String? = null,
    val requestId: String,
    val wholePromptScopeConfirmed: Boolean = false,
) {
    val scoringEnabled: Boolean
        get() = versionId != null || wholePromptScopeConfirmed
}

data class NsfwUiState(
    val phase: NsfwPhase = NsfwPhase.LOADING,
    val baseUrl: String = "",
    val items: List<NsfwFeedItem> = emptyList(),
    val currentId: String? = null,
    val detail: NsfwImageDetails? = null,
    val detailLoading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val missingCapabilities: List<String> = emptyList(),
    val conflictMessage: String? = null,
    val cursorExpired: Boolean = false,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val newImageCount: Int = 0,
    val panel: NsfwPromptPanel? = null,
    val pairingBusy: Boolean = false,
) {
    val currentIndex: Int
        get() = items.indexOfFirst { it.imageId == currentId }.coerceAtLeast(0)

    val currentItem: NsfwFeedItem?
        get() = items.firstOrNull { it.imageId == currentId }
}

/**
 * 生图审核 ViewModel（独立于视频批阅）：
 *
 * - feed 稳定游标 + 事件去重 + 原位更新 + 队尾追加；
 * - 审核 409 后只刷新权威状态，绝不自动重发；
 * - 评分按 prompt + 版本组织；版本未知必须用户显式选择整体评分；
 * - 401 只清 NSFW 凭据，不触碰 MediaReview Server。
 */
@HiltViewModel
class NsfwReviewViewModel @Inject constructor(
    private val dataSource: NsfwReviewDataSource,
    private val pairing: NsfwPairingGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(NsfwUiState())
    val state: StateFlow<NsfwUiState> = _state.asStateFlow()

    private val feed = NsfwFeedStore()
    private var lastCursor: String? = null

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val snapshot = runCatching { pairing.load() }.getOrNull()
            if (snapshot == null || !snapshot.paired) {
                _state.update {
                    it.copy(
                        phase = NsfwPhase.UNPAIRED,
                        baseUrl = snapshot?.baseUrl.orEmpty(),
                        message = snapshot?.message,
                        busy = false,
                    )
                }
                return@launch
            }
            _state.update { it.copy(phase = NsfwPhase.READY, baseUrl = snapshot.baseUrl) }
            syncFromCursor(reset = true)
        }
    }

    fun pair(baseUrl: String, code: String) {
        if (_state.value.pairingBusy) return
        _state.update { it.copy(pairingBusy = true, message = null, missingCapabilities = emptyList()) }
        viewModelScope.launch {
            when (val result = pairing.checkAndPair(baseUrl, code)) {
                is NsfwPairResult.Paired -> {
                    feed.clear()
                    lastCursor = null
                    _state.update {
                        it.copy(
                            phase = NsfwPhase.READY,
                            baseUrl = result.baseUrl,
                            pairingBusy = false,
                            message = "已连接 NSFW Studio",
                            items = emptyList(),
                            currentId = null,
                            detail = null,
                            newImageCount = 0,
                        )
                    }
                    syncFromCursor(reset = true)
                }
                is NsfwPairResult.Blocked -> _state.update {
                    it.copy(
                        phase = NsfwPhase.UNPAIRED,
                        pairingBusy = false,
                        message = result.message,
                        missingCapabilities = result.missingCapabilities,
                    )
                }
                is NsfwPairResult.Failed -> _state.update {
                    it.copy(
                        phase = if (result.unauthorized) NsfwPhase.UNPAIRED else it.phase,
                        pairingBusy = false,
                        message = result.message,
                    )
                }
            }
        }
    }

    fun retryPairing(baseUrl: String, code: String) = pair(baseUrl, code)

    fun clearPairing() {
        viewModelScope.launch {
            pairing.clear()
            feed.clear()
            lastCursor = null
            _state.value = NsfwUiState(phase = NsfwPhase.UNPAIRED)
        }
    }

    /** 手动/回焦刷新：追平增量；不改变当前图片位置。 */
    fun refresh() {
        if (_state.value.phase != NsfwPhase.READY || _state.value.busy) return
        viewModelScope.launch { syncFromCursor(reset = false, manual = true) }
    }

    fun loadMore() {
        val state = _state.value
        if (state.phase != NsfwPhase.READY || state.busy || !state.hasMore) return
        val cursor = lastCursor ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { dataSource.feed(cursor, PAGE_SIZE) }
                .onSuccess { page -> applyPage(page, manual = true) }
                .onFailure { error -> handleError(error) }
            _state.update { it.copy(busy = false) }
        }
    }

    fun select(imageId: String) {
        if (imageId.isBlank() || imageId == _state.value.currentId) return
        if (_state.value.items.none { it.imageId == imageId }) return
        _state.update {
            it.copy(
                currentId = imageId,
                detail = null,
                conflictMessage = null,
                panel = null,
            )
        }
        loadDetails(imageId)
    }

    fun next() {
        val state = _state.value
        val index = state.currentIndex
        if (index + 1 < state.items.size) select(state.items[index + 1].imageId)
    }

    fun previous() {
        val state = _state.value
        val index = state.currentIndex
        if (index > 0) select(state.items[index - 1].imageId)
    }

    fun decision(action: String, favorite: Boolean? = null) {
        val state = _state.value
        val detail = state.detail ?: return
        if (state.busy) return
        if (action !in setOf("KEEP", "REJECT", "RESET")) return
        _state.update { it.copy(busy = true, conflictMessage = null) }
        viewModelScope.launch {
            try {
                val result = dataSource.decide(
                    imageId = detail.imageId,
                    action = action,
                    favorite = favorite,
                    expectedUpdatedAt = detail.updatedAt,
                    clientRequestId = UUID.randomUUID().toString(),
                )
                applyDecision(result)
                _state.update { it.copy(busy = false, message = decisionMessage(action)) }
            } catch (error: Exception) {
                if (error is NsfwApiException && error.code == NsfwErrorCodes.REVIEW_CONFLICT) {
                    // 409：只刷新权威状态，绝不自动重复提交
                    _state.update {
                        it.copy(
                            busy = false,
                            conflictMessage = "图片状态已被他处修改，已刷新为最新状态（不会自动重提）",
                        )
                    }
                    loadDetails(detail.imageId)
                } else {
                    handleError(error)
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun toggleFavorite() {
        val state = _state.value
        val detail = state.detail ?: return
        val currentFavorite = state.items.firstOrNull { it.imageId == detail.imageId }?.isFavorite ?: false
        decision("KEEP", favorite = !currentFavorite)
    }

    fun openPromptPanel() {
        val detail = _state.value.detail ?: return
        val link = detail.promptLink
        if (link.isUnlinked || detail.promptId == null) {
            _state.update { it.copy(message = "该图片没有可靠的提示词关联，不能写回评分") }
            return
        }
        val explicitVersion = if (link.linkState == NsfwPromptLink.LINK_EXACT) detail.promptVersionId else null
        val panel = NsfwPromptPanel(
            promptId = detail.promptId,
            versionId = explicitVersion,
            linkState = detail.linkState,
            promptSnapshot = detail.positivePrompt,
            requestId = UUID.randomUUID().toString(),
            wholePromptScopeConfirmed = explicitVersion != null,
        )
        _state.update { it.copy(panel = panel) }
        refreshPromptPanel(panel)
    }

    /** 版本未知时，用户必须显式选择"对提示词整体评分"才允许提交。 */
    fun confirmWholePromptScope() {
        val panel = _state.value.panel ?: return
        if (panel.versionId != null) return
        val updated = panel.copy(versionId = null, wholePromptScopeConfirmed = true, message = null)
        _state.update { it.copy(panel = updated) }
        refreshPromptPanel(updated)
    }

    fun closePromptPanel() {
        _state.update { it.copy(panel = null) }
    }

    fun submitEvaluation(overallScore: Int, dimensions: Map<String, Int>, notes: String) {
        val panel = _state.value.panel ?: return
        if (panel.submitting || panel.submitted != null) return
        if (!panel.scoringEnabled) {
            _state.update {
                it.copy(panel = panel.copy(message = "请先选择评分范围：对提示词整体评分（生成版本未知）"))
            }
            return
        }
        if (overallScore !in 1..5) {
            _state.update { it.copy(panel = panel.copy(message = "总分必须是 1–5")) }
            return
        }
        val request = NsfwEvaluationRequest(
            promptVersionId = panel.versionId,
            overallScore = overallScore,
            dimensions = dimensions.ifEmpty { null },
            notes = notes,
            clientRequestId = panel.requestId,
        )
        _state.update { it.copy(panel = panel.copy(submitting = true, message = null, unconfirmed = false)) }
        viewModelScope.launch {
            try {
                val result = dataSource.createEvaluation(panel.promptId, request)
                _state.update {
                    it.copy(
                        panel = panel.copy(submitting = false, submitted = result, message = "评分已保存"),
                        message = "提示词评分已保存",
                    )
                }
            } catch (error: Exception) {
                // 超时/结果未知：先用同一 client_request_id 查回执，绝不盲目重复提交
                val receipt = runCatching { dataSource.evaluationReceipt(panel.requestId) }.getOrNull()
                val existing = receipt?.takeIf { it.found }?.evaluation
                if (existing != null) {
                    _state.update {
                        it.copy(
                            panel = panel.copy(submitting = false, submitted = existing, message = "评分已保存（回执核对）"),
                        )
                    }
                    return@launch
                }
                val apiError = error as? NsfwApiException
                val text = when (apiError?.code) {
                    NsfwErrorCodes.PROMPT_VERSION_UNKNOWN ->
                        "该提示词版本未知，请改为对提示词整体评分"
                    NsfwErrorCodes.VALIDATION_ERROR -> apiError.message
                    NsfwErrorCodes.IDEMPOTENCY_CONFLICT -> "同一请求号已提交过不同内容，请重新打开评分面板"
                    else -> "评分结果未确认，可安全重试（不会重复计分）"
                }
                _state.update {
                    it.copy(
                        panel = panel.copy(
                            submitting = false,
                            unconfirmed = apiError?.code !in setOf(
                                NsfwErrorCodes.PROMPT_VERSION_UNKNOWN,
                                NsfwErrorCodes.VALIDATION_ERROR,
                                NsfwErrorCodes.IDEMPOTENCY_CONFLICT,
                            ),
                            message = text,
                        ),
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun dismissPanelMessage() {
        val panel = _state.value.panel ?: return
        _state.update { it.copy(panel = panel.copy(message = null)) }
    }

    private fun refreshPromptPanel(panel: NsfwPromptPanel) {
        _state.update { it.copy(panel = panel.copy(loading = true)) }
        viewModelScope.launch {
            try {
                val summary = dataSource.promptSummary(panel.promptId, panel.versionId)
                val imagesPage = dataSource.promptImages(panel.promptId, panel.versionId)
                _state.update {
                    val current = it.panel ?: return@update it
                    it.copy(
                        panel = current.copy(
                            loading = false,
                            summary = summary,
                            images = imagesPage.items,
                            total = imagesPage.total,
                        ),
                    )
                }
            } catch (error: Exception) {
                val text = (error as? NsfwApiException)?.message ?: "提示词信息加载失败"
                _state.update {
                    val current = it.panel ?: return@update it
                    it.copy(panel = current.copy(loading = false, message = text))
                }
            }
        }
    }

    private suspend fun syncFromCursor(reset: Boolean, manual: Boolean = false) {
        if (reset) {
            lastCursor = null
            _state.update { it.copy(busy = true) }
        } else {
            _state.update { it.copy(busy = true) }
        }
        var addedTotal = 0
        var pages = 0
        var cursor = lastCursor
        while (pages < MAX_SYNC_PAGES) {
            val page = try {
                dataSource.feed(cursor, PAGE_SIZE)
            } catch (error: Exception) {
                if (error is NsfwApiException && error.code == NsfwErrorCodes.CURSOR_EXPIRED) {
                    lastCursor = null
                    _state.update {
                        it.copy(cursorExpired = true, message = "游标已过期，已从头重新同步")
                    }
                    cursor = null
                    continue
                }
                handleError(error)
                _state.update { it.copy(busy = false) }
                return
            }
            val merge = feed.merge(page)
            addedTotal += merge.addedIds.size
            if (page.nextCursor != null) lastCursor = page.nextCursor
            pages += 1
            if (merge.addedIds.isNotEmpty()) {
                // 新图只在队尾追加；当前图片位置不变
                _state.update { it.copy(items = feed.snapshot(), hasMore = page.hasMore, nextCursor = page.nextCursor) }
            }
            if (!page.hasMore) break
            cursor = page.nextCursor
            if (cursor == null) break
        }
        val state = _state.value
        val currentStillExists = state.currentId != null && feed.find(state.currentId!!) != null
        val targetId = if (currentStillExists) state.currentId else feed.snapshot().firstOrNull()?.imageId
        _state.update {
            it.copy(
                phase = NsfwPhase.READY,
                busy = false,
                items = feed.snapshot(),
                currentId = targetId,
                hasMore = it.hasMore,
                newImageCount = it.newImageCount + addedTotal,
                message = if (manual && addedTotal > 0) "已同步 $addedTotal 张新图" else it.message,
            )
        }
        if (targetId != null && targetId != state.detail?.imageId) loadDetails(targetId)
    }

    private fun applyPage(page: NsfwFeedPage, manual: Boolean) {
        val merge = feed.merge(page)
        if (page.nextCursor != null) lastCursor = page.nextCursor
        _state.update {
            it.copy(
                items = feed.snapshot(),
                hasMore = page.hasMore,
                nextCursor = page.nextCursor,
                newImageCount = it.newImageCount + merge.addedIds.size,
                message = if (manual && merge.addedIds.isNotEmpty()) {
                    "已同步 ${merge.addedIds.size} 张新图"
                } else {
                    it.message
                },
            )
        }
    }

    private fun loadDetails(imageId: String) {
        _state.update { it.copy(detailLoading = true) }
        viewModelScope.launch {
            try {
                val detail = dataSource.details(imageId)
                _state.update {
                    if (it.currentId != imageId) it
                    else it.copy(detail = detail, detailLoading = false)
                }
            } catch (error: Exception) {
                if (error is NsfwApiException && error.isAuthRejected) {
                    handleAuthFailure()
                    return@launch
                }
                _state.update {
                    it.copy(
                        detailLoading = false,
                        message = (error as? NsfwApiException)?.message ?: "图片详情加载失败",
                    )
                }
            }
        }
    }

    private fun applyDecision(result: NsfwDecisionResult) {
        feed.update(result.imageId) {
            it.copy(
                status = result.status,
                isFavorite = result.isFavorite,
                updatedAt = result.updatedAt,
                revision = result.revision,
            )
        }
        _state.update {
            it.copy(
                items = feed.snapshot(),
                detail = it.detail?.takeIf { d -> d.imageId == result.imageId }?.copy(
                    updatedAt = result.updatedAt,
                    revision = result.revision,
                ) ?: it.detail,
            )
        }
    }

    private fun handleError(error: Throwable) {
        if (error is NsfwApiException && error.isAuthRejected) {
            handleAuthFailure()
            return
        }
        val text = when (error) {
            is NsfwApiException -> error.message
            else -> error.message ?: "操作失败"
        }
        _state.update { it.copy(message = text) }
    }

    private fun handleAuthFailure() {
        viewModelScope.launch {
            pairing.handleAuthRejected()
            feed.clear()
            lastCursor = null
            _state.value = NsfwUiState(
                phase = NsfwPhase.UNPAIRED,
                message = "NSFW 配对已失效，请重新配对（MediaReview 服务器不受影响）",
            )
        }
    }

    private fun decisionMessage(action: String): String = when (action) {
        "KEEP" -> "已保留"
        "REJECT" -> "已淘汰（只改状态，不删除文件）"
        else -> "已重置为待审核"
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_SYNC_PAGES = 5
    }
}
