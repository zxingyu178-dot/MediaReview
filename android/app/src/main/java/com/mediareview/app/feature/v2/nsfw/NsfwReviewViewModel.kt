package com.mediareview.app.feature.v2.nsfw

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    /** 首次发送后冻结的请求载荷；重试必须复用，改名/改分必须换新请求号。 */
    val pendingRequest: NsfwEvaluationRequest? = null,
    /** 用户已显式新建请求号：忽略旧 pending，但仍保留其回执记录。 */
    val freshDraft: Boolean = false,
) {
    val scoringEnabled: Boolean
        get() = versionId != null || wholePromptScopeConfirmed
}

@Serializable
data class NsfwPendingEvaluation(
    val serverOrigin: String = "",
    val promptId: String,
    val versionId: String? = null,
    val request: NsfwEvaluationRequest,
)

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
    /** 进程重启后发现上次评分未确认时的提示（不自动重发）。 */
    val evaluationRecoveryHint: String? = null,
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
    private val savedState: SavedStateHandle,
    private val json: Json,
) : ViewModel() {

    private val _state = MutableStateFlow(NsfwUiState())
    val state: StateFlow<NsfwUiState> = _state.asStateFlow()

    private val feed = NsfwFeedStore()
    private var lastCursor: String? = null
    private var pollJob: Job? = null
    private var screenVisible = false
    private var detailSeq = 0
    private var panelSeq = 0
    private var connectionEpoch = 0
    private val pendingEvaluations = readPendingEvaluations().toMutableList()

    /** 仅测试可调；生产为 15 秒。 */
    internal var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

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
            if (snapshot.compatible == null) {
                // 兼容性未知：保留 token，但不启动 feed/轮询，也不把 UNKNOWN 当成 compatible。
                _state.update {
                    it.copy(
                        phase = NsfwPhase.UNPAIRED,
                        baseUrl = snapshot.baseUrl,
                        message = snapshot.message ?: "无法确认 NSFW Bridge 版本，请检查连接后重试",
                        missingCapabilities = emptyList(),
                        busy = false,
                    )
                }
                return@launch
            }
            if (snapshot.compatible == false) {
                // 保留已保存 token，但旧 Bridge 不能进入审核页：必须重新升级/配对。
                _state.update {
                    it.copy(
                        phase = NsfwPhase.UNPAIRED,
                        baseUrl = snapshot.baseUrl,
                        message = snapshot.message,
                        missingCapabilities = snapshot.missingCapabilities,
                        busy = false,
                    )
                }
                return@launch
            }
            updateRecoveryHint(snapshot.baseUrl)
            _state.update { it.copy(phase = NsfwPhase.READY, baseUrl = snapshot.baseUrl) }
            syncFromCursor(reset = true)
            maybeStartPolling()
        }
    }

    fun pair(baseUrl: String, code: String) {
        if (_state.value.pairingBusy) return
        _state.update { it.copy(pairingBusy = true, message = null, missingCapabilities = emptyList()) }
        viewModelScope.launch {
            when (val result = pairing.checkAndPair(baseUrl, code)) {
                is NsfwPairResult.Paired -> {
                    connectionEpoch += 1
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
                    maybeStartPolling()
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
            connectionEpoch += 1
            stopPolling()
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

    /** 页面可见性：只有生图审核页前台可见且 READY 时才做有界增量轮询。 */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (!visible) {
            stopPolling()
            return
        }
        if (_state.value.phase != NsfwPhase.READY) return
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                val snapshot = _state.value
                if (snapshot.phase == NsfwPhase.READY && !snapshot.busy) {
                    syncFromCursor(reset = false)
                }
            }
        }
    }

    private fun maybeStartPolling() {
        if (screenVisible && _state.value.phase == NsfwPhase.READY) setScreenVisible(true)
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun loadMore() {
        val state = _state.value
        if (state.phase != NsfwPhase.READY || state.busy || !state.hasMore) return
        val cursor = lastCursor
        if (cursor == null) {
            _state.update {
                it.copy(hasMore = false, nextCursor = null, message = "缺少 next_cursor，无法继续加载；已停止本轮")
            }
            return
        }
        val epoch = connectionEpoch
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = fetchPageWithRecovery(cursor)
                if (epoch != connectionEpoch) return@launch
                applyPage(result, manual = true)
                _state.update { it.copy(busy = false) }
            } catch (error: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw error
            } catch (error: Exception) {
                if (epoch != connectionEpoch) return@launch
                _state.update { it.copy(busy = false) }
                handleFetchFailure(error)
            }
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

    fun decision(action: String?, favorite: Boolean? = null) {
        val state = _state.value
        val detail = state.detail ?: return
        if (state.busy) return
        if (action != null && action !in setOf("KEEP", "REJECT", "RESET")) return
        if (action == null && favorite == null) return
        _state.update { it.copy(busy = true, conflictMessage = null) }
        val epoch = connectionEpoch
        viewModelScope.launch {
            try {
                val result = dataSource.decide(
                    imageId = detail.imageId,
                    action = action,
                    favorite = favorite,
                    expectedUpdatedAt = detail.updatedAt,
                    clientRequestId = UUID.randomUUID().toString(),
                )
                if (epoch != connectionEpoch) return@launch
                val applied = applyDecision(result)
                if (!applied) {
                    // 较旧的审核结果被 revision 拒绝：不显示旧动作成功，刷新权威状态。
                    _state.update {
                        it.copy(
                            busy = false,
                            conflictMessage = "该审核结果已过期，状态已被更新；已刷新权威状态（不会重复提交）",
                        )
                    }
                    loadDetails(detail.imageId)
                    return@launch
                }
                _state.update { it.copy(busy = false, message = decisionMessage(action)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch != connectionEpoch) return@launch
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
        // favorite-only：绝不把收藏动作混成 KEEP，避免改变 NEW/REJECT/ARCHIVED 的审核状态。
        decision(action = null, favorite = !currentFavorite)
    }

    fun openPromptPanel() {
        val detail = _state.value.detail ?: return
        val link = detail.promptLink
        // 严格失败关闭：未知关联状态不开放评分；EXACT 必须有版本，UNRESOLVED 必须有 prompt_id。
        val invalidReason = when (detail.linkState) {
            NsfwPromptLink.LINK_EXACT ->
                if (detail.promptId == null || detail.promptVersionId == null) {
                    "关联标记为精确但缺少提示词或版本，已关闭评分"
                } else {
                    null
                }
            NsfwPromptLink.LINK_UNRESOLVED ->
                if (detail.promptId == null) {
                    "关联标记为版本未知但缺少提示词 ID，已关闭评分"
                } else {
                    null
                }
            NsfwPromptLink.LINK_UNLINKED -> "该图片没有可靠的提示词关联，不能写回评分"
            else -> "未知的提示词关联状态（${detail.linkState}），已关闭评分"
        }
        if (invalidReason != null || link.isUnlinked) {
            _state.update { it.copy(message = invalidReason ?: "该图片没有可靠的提示词关联，不能写回评分") }
            return
        }
        val promptId = detail.promptId ?: return
        val explicitVersion = if (link.linkState == NsfwPromptLink.LINK_EXACT) detail.promptVersionId else null
        val matching = pendingEvaluations.filter {
            it.promptId == promptId &&
                it.versionId == explicitVersion &&
                it.serverOrigin == originOf(_state.value.baseUrl)
        }
        val existingPending = matching.firstOrNull()
        val panel = NsfwPromptPanel(
            promptId = promptId,
            versionId = explicitVersion,
            linkState = detail.linkState,
            promptSnapshot = detail.positivePrompt,
            requestId = existingPending?.request?.clientRequestId ?: UUID.randomUUID().toString(),
            wholePromptScopeConfirmed = explicitVersion != null ||
                (existingPending != null && existingPending.request.promptVersionId == null),
            pendingRequest = existingPending?.request,
            unconfirmed = existingPending != null,
            message = existingPending?.let {
                "上次提交结果未确认，已冻结原评分（请求号 ${it.request.clientRequestId}）；可直接重试或查询回执。" +
                    if (matching.size > 1) "该提示词还有 ${matching.size - 1} 条未确认记录。" else ""
            },
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
        panelSeq += 1
        _state.update { it.copy(panel = null) }
    }

    /** 用户明确放弃当前未确认请求并创建新请求号；旧请求记录保留，客户端换 ID 不等于服务端取消。 */
    fun resetEvaluationDraft() {
        val panel = _state.value.panel ?: return
        panelSeq += 1
        _state.update {
            it.copy(
                panel = panel.copy(
                    requestId = UUID.randomUUID().toString(),
                    pendingRequest = null,
                    submitted = null,
                    unconfirmed = false,
                    freshDraft = true,
                    message = "已创建新的评分请求号；旧请求仍可能已写入服务端，其回执记录会继续保留，不能视为已取消。",
                ),
            )
        }
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
        val normalizedDimensions = dimensions.filterValues { it in 1..5 }
        val frozen = panel.pendingRequest
            ?: if (panel.freshDraft) null else pendingForPanel(panel.promptId, panel.versionId)?.request
        if (frozen != null) {
            val payloadChanged = frozen.overallScore != overallScore ||
                frozen.dimensions.orEmpty() != normalizedDimensions ||
                frozen.notes != notes
            if (payloadChanged) {
                _state.update {
                    it.copy(
                        panel = panel.copy(
                            message = "评分内容已修改。请先点“新建评分请求”，再用新请求号提交，不能复用旧幂等键。",
                        ),
                    )
                }
                return
            }
        } else if (pendingEvaluations.size >= MAX_PENDING_EVALUATIONS) {
            _state.update {
                it.copy(
                    panel = panel.copy(
                        message = "还有 ${pendingEvaluations.size} 条评分结果未确认；请先到对应提示词面板核对回执或重试，" +
                            "不能继续堆积未确认请求。",
                    ),
                )
            }
            return
        }
        val request = frozen ?: NsfwEvaluationRequest(
            promptVersionId = panel.versionId,
            overallScore = overallScore,
            dimensions = normalizedDimensions.ifEmpty { null },
            notes = notes,
            clientRequestId = panel.requestId,
        )
        val pending = NsfwPendingEvaluation(
            serverOrigin = originOf(_state.value.baseUrl),
            promptId = panel.promptId,
            versionId = panel.versionId,
            request = request,
        )
        upsertPending(pending)
        val epoch = connectionEpoch
        _state.update {
            it.copy(
                panel = panel.copy(pendingRequest = request, submitting = true, message = null, unconfirmed = false),
            )
        }
        viewModelScope.launch {
            try {
                val result = dataSource.createEvaluation(panel.promptId, request)
                if (_state.value.panel?.requestId != panel.requestId || epoch != connectionEpoch) return@launch
                removePending(request.clientRequestId)
                _state.update {
                    val current = it.panel ?: return@update it
                    it.copy(
                        panel = current.copy(
                            submitting = false,
                            submitted = result,
                            pendingRequest = request,
                            message = "评分已保存",
                        ),
                        message = "提示词评分已保存",
                        evaluationRecoveryHint = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 超时/结果未知：先用同一 client_request_id 查回执，绝不盲目重复提交
                val receipt = runCatching { dataSource.evaluationReceipt(request.clientRequestId) }.getOrNull()
                val existing = receipt?.takeIf { it.found }?.evaluation
                if (_state.value.panel?.requestId != panel.requestId || epoch != connectionEpoch) return@launch
                if (existing != null) {
                    removePending(request.clientRequestId)
                    _state.update {
                        val current = it.panel ?: return@update it
                        it.copy(
                            panel = current.copy(
                                submitting = false,
                                submitted = existing,
                                pendingRequest = request,
                                message = "评分已保存（回执核对）",
                            ),
                            evaluationRecoveryHint = null,
                        )
                    }
                    return@launch
                }
                val apiError = error as? NsfwApiException
                val text = when (apiError?.code) {
                    NsfwErrorCodes.PROMPT_VERSION_UNKNOWN ->
                        "该提示词版本未知，请改为对提示词整体评分"
                    NsfwErrorCodes.VALIDATION_ERROR -> apiError.message
                    NsfwErrorCodes.IDEMPOTENCY_CONFLICT -> "同一请求号已提交过不同内容，请点“新建评分请求”"
                    else -> "评分结果未确认，可安全重试（同一请求号，不会重复计分）"
                }
                _state.update {
                    val current = it.panel ?: return@update it
                    it.copy(
                        panel = current.copy(
                            submitting = false,
                            pendingRequest = request,
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
        val seq = ++panelSeq
        val epoch = connectionEpoch
        _state.update {
            if (it.panel?.requestId == panel.requestId) it.copy(panel = it.panel.copy(loading = true)) else it
        }
        viewModelScope.launch {
            try {
                val summary = dataSource.promptSummary(panel.promptId, panel.versionId)
                val imagesPage = dataSource.promptImages(panel.promptId, panel.versionId)
                if (seq != panelSeq || epoch != connectionEpoch) return@launch
                _state.update {
                    val current = it.panel ?: return@update it
                    if (current.requestId != panel.requestId) return@update it
                    it.copy(
                        panel = current.copy(
                            loading = false,
                            summary = summary,
                            images = imagesPage.items,
                            total = imagesPage.total,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (seq != panelSeq || epoch != connectionEpoch) return@launch
                val text = (error as? NsfwApiException)?.message ?: "提示词信息加载失败"
                _state.update {
                    val current = it.panel ?: return@update it
                    if (current.requestId != panel.requestId) return@update it
                    it.copy(panel = current.copy(loading = false, message = text))
                }
            }
        }
    }

    private data class PageFetchResult(val page: NsfwFeedPage, val resetApplied: Boolean)

    /**
     * 分页与游标恢复的唯一入口（syncFromCursor 与 loadMore 共用）：
     * - 410 仅允许重置一次；再次 410 抛错；
     * - has_more=true 但 next_cursor 缺失/不推进 → 抛 CURSOR_MISSING / CURSOR_STALLED。
     */
    private suspend fun fetchPageWithRecovery(initialCursor: String?): PageFetchResult {
        var cursor = initialCursor
        var resetApplied = false
        while (true) {
            try {
                val page = dataSource.feed(cursor, PAGE_SIZE)
                if (page.hasMore && page.nextCursor == null) {
                    throw NsfwApiException("CURSOR_MISSING", "服务端未返回 next_cursor", 0)
                }
                if (page.hasMore && page.nextCursor == cursor) {
                    throw NsfwApiException("CURSOR_STALLED", "服务端游标未推进", 0)
                }
                return PageFetchResult(page, resetApplied)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NsfwApiException) {
                if (error.code == NsfwErrorCodes.CURSOR_EXPIRED && !resetApplied) {
                    resetApplied = true
                    cursor = null
                    continue
                }
                throw error
            }
        }
    }

    private suspend fun syncFromCursor(reset: Boolean, manual: Boolean = false) {
        val epoch = connectionEpoch
        if (reset) lastCursor = null
        _state.update { it.copy(busy = true) }
        val indexBefore = _state.value.currentIndex
        var countedNew = 0
        var pages = 0
        var cursor = lastCursor
        var recovered = false
        var terminalMissingCursor = false
        var finalHasMore = _state.value.hasMore
        var finalNextCursor: String? = _state.value.nextCursor
        while (pages < MAX_SYNC_PAGES) {
            val result = try {
                fetchPageWithRecovery(cursor)
            } catch (error: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw error
            } catch (error: Exception) {
                if (epoch != connectionEpoch) return
                _state.update { it.copy(busy = false) }
                handleFetchFailure(error)
                return
            }
            if (epoch != connectionEpoch) return
            val page = result.page
            if (result.resetApplied) recovered = true
            val merge = feed.merge(page)
            // 首次历史同步与 410 重建历史都不算"新到"；只有真正增量才计数。
            if (!reset && !result.resetApplied) countedNew += merge.addedIds.size
            pages += 1
            finalHasMore = page.hasMore
            finalNextCursor = page.nextCursor
            if (!page.hasMore && page.nextCursor == null) {
                terminalMissingCursor = true
                lastCursor = null
            } else {
                lastCursor = page.nextCursor
            }
            _state.update {
                it.copy(items = feed.snapshot(), hasMore = page.hasMore, nextCursor = page.nextCursor)
            }
            if (!page.hasMore) break
            cursor = page.nextCursor
        }
        if (epoch != connectionEpoch) return
        val state = _state.value
        val snapshotNow = feed.snapshot()
        val currentStillExists = state.currentId != null && feed.find(state.currentId!!) != null
        val targetId = if (currentStillExists) {
            state.currentId
        } else if (snapshotNow.isEmpty()) {
            null
        } else {
            snapshotNow.getOrNull(indexBefore.coerceIn(0, snapshotNow.lastIndex))?.imageId
                ?: snapshotNow.first().imageId
        }
        val message = when {
            terminalMissingCursor -> "服务端未返回 next_cursor，下次将从头同步"
            recovered -> "游标已过期，已从头重新同步"
            manual && countedNew > 0 -> "已同步 $countedNew 张新图"
            else -> _state.value.message
        }
        _state.update {
            it.copy(
                phase = NsfwPhase.READY,
                busy = false,
                items = snapshotNow,
                currentId = targetId,
                hasMore = finalHasMore,
                nextCursor = finalNextCursor,
                cursorExpired = false,
                detail = it.detail?.takeIf { d -> d.imageId == targetId },
                detailLoading = it.detailLoading && it.detail?.imageId == targetId,
                newImageCount = it.newImageCount + countedNew,
                message = message,
            )
        }
        if (targetId != null && targetId != _state.value.detail?.imageId) loadDetails(targetId)
        maybeStartPolling()
    }

    private fun applyPage(fetch: PageFetchResult, manual: Boolean) {
        val page = fetch.page
        val indexBefore = _state.value.currentIndex
        val merge = feed.merge(page)
        val missingTerminalCursor = !page.hasMore && page.nextCursor == null
        lastCursor = if (missingTerminalCursor) null else page.nextCursor
        val currentId = _state.value.currentId
        val currentAlive = currentId != null && feed.find(currentId) != null
        val snapshot = feed.snapshot()
        val targetId = if (currentAlive) {
            currentId
        } else if (snapshot.isEmpty()) {
            null
        } else {
            snapshot.getOrNull(indexBefore.coerceIn(0, snapshot.lastIndex))?.imageId
                ?: snapshot.first().imageId
        }
        _state.update {
            it.copy(
                items = feed.snapshot(),
                currentId = targetId,
                hasMore = page.hasMore,
                nextCursor = page.nextCursor,
                cursorExpired = false,
                detail = it.detail?.takeIf { d -> d.imageId == targetId },
                detailLoading = it.detailLoading && it.detail?.imageId == targetId,
                message = when {
                    missingTerminalCursor -> "服务端未返回 next_cursor，下次将从头同步"
                    manual && merge.addedIds.isNotEmpty() -> "已加载 ${merge.addedIds.size} 张图片"
                    else -> it.message
                },
            )
        }
        if (targetId != null && targetId != _state.value.detail?.imageId) loadDetails(targetId)
    }

    private fun handleFetchFailure(error: Exception) {
        if (error is NsfwApiException && error.code == NsfwErrorCodes.CURSOR_EXPIRED) {
            _state.update {
                it.copy(
                    busy = false,
                    cursorExpired = true,
                    message = "游标已过期且从头同步失败，请稍后手动刷新",
                )
            }
            return
        }
        if (error is NsfwApiException && error.code in setOf("CURSOR_MISSING", "CURSOR_STALLED")) {
            _state.update {
                it.copy(
                    busy = false,
                    hasMore = false,
                    nextCursor = null,
                    cursorExpired = false,
                    message = error.message,
                )
            }
            return
        }
        handleError(error)
    }

    private fun loadDetails(imageId: String) {
        val seq = ++detailSeq
        val epoch = connectionEpoch
        _state.update { it.copy(detailLoading = true) }
        viewModelScope.launch {
            try {
                val detail = dataSource.details(imageId)
                if (seq != detailSeq || epoch != connectionEpoch) return@launch
                _state.update {
                    if (it.currentId != imageId) it
                    else it.copy(detail = detail, detailLoading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (seq != detailSeq || epoch != connectionEpoch) return@launch
                if (error is NsfwApiException && error.isAuthRejected) {
                    handleAuthFailure()
                    return@launch
                }
                _state.update {
                    if (it.currentId != imageId) it
                    else it.copy(
                        detailLoading = false,
                        message = (error as? NsfwApiException)?.message ?: "图片详情加载失败",
                    )
                }
            }
        }
    }

    private fun applyDecision(result: NsfwDecisionResult): Boolean {
        // 只接受 revision 前进的权威结果；旧响应/重复回包不会覆盖较新状态。
        val applied = feed.applyDecision(
            imageId = result.imageId,
            status = result.status.ifBlank {
                feed.find(result.imageId)?.status ?: "NEW"
            },
            favorite = result.isFavorite,
            updatedAt = result.updatedAt,
            revision = result.revision,
        )
        if (!applied) return false
        _state.update {
            it.copy(
                items = feed.snapshot(),
                detail = it.detail?.takeIf { d -> d.imageId == result.imageId }?.copy(
                    updatedAt = result.updatedAt,
                    revision = result.revision,
                ) ?: it.detail,
            )
        }
        return true
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
            connectionEpoch += 1
            stopPolling()
            pairing.handleAuthRejected()
            feed.clear()
            lastCursor = null
            _state.value = NsfwUiState(
                phase = NsfwPhase.UNPAIRED,
                message = "NSFW 配对已失效，请重新配对（MediaReview 服务器不受影响）",
            )
        }
    }

    private fun decisionMessage(action: String?): String = when (action) {
        "KEEP" -> "已保留"
        "REJECT" -> "已淘汰（只改状态，不删除文件）"
        "RESET" -> "已重置为待审核"
        else -> "收藏状态已更新"
    }

    private fun pendingForPanel(promptId: String, versionId: String?): NsfwPendingEvaluation? {
        val origin = originOf(_state.value.baseUrl)
        return pendingEvaluations.firstOrNull {
            it.promptId == promptId && it.versionId == versionId && it.serverOrigin == origin
        }
    }

    private fun originOf(url: String): String = url.toHttpUrlOrNull()
        ?.let { "${it.scheme}://${it.host}:${it.port}" }
        .orEmpty()

    private fun upsertPending(pending: NsfwPendingEvaluation) {
        pendingEvaluations.removeAll { it.request.clientRequestId == pending.request.clientRequestId }
        pendingEvaluations += pending
        savePendingEvaluations()
        updateRecoveryHint(_state.value.baseUrl)
    }

    private fun removePending(clientRequestId: String) {
        pendingEvaluations.removeAll { it.request.clientRequestId == clientRequestId }
        savePendingEvaluations()
        updateRecoveryHint(_state.value.baseUrl)
    }

    private fun updateRecoveryHint(baseUrl: String) {
        val origin = originOf(baseUrl)
        val count = if (origin.isBlank()) 0 else pendingEvaluations.count { it.serverOrigin == origin }
        _state.update {
            it.copy(
                evaluationRecoveryHint = if (count > 0) {
                    "有 $count 条评分结果未确认（不自动重发）；重新打开对应提示词评分面板可查询回执或重试。"
                } else {
                    null
                },
            )
        }
    }

    /** 测试用：当前仍保留的未确认请求号（按日志顺序）。 */
    internal fun pendingRequestIds(): List<String> =
        pendingEvaluations.map { it.request.clientRequestId }

    private fun readPendingEvaluations(): List<NsfwPendingEvaluation> {
        savedState.get<String>(KEY_PENDING_EVALUATIONS)?.let { raw ->
            runCatching {
                json.decodeFromString(ListSerializer(NsfwPendingEvaluation.serializer()), raw)
            }.getOrNull()?.let { return it }
        }
        // 兼容 V2A.0 单条 pending 的旧格式
        return savedState.get<String>(KEY_LEGACY_PENDING_EVALUATION)?.let { raw ->
            runCatching {
                json.decodeFromString(NsfwPendingEvaluation.serializer(), raw)
            }.getOrNull()?.let { listOf(it) } ?: emptyList()
        } ?: emptyList()
    }

    private fun savePendingEvaluations() {
        savedState[KEY_PENDING_EVALUATIONS] =
            json.encodeToString(ListSerializer(NsfwPendingEvaluation.serializer()), pendingEvaluations)
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_SYNC_PAGES = 10
        const val DEFAULT_POLL_INTERVAL_MS = 15_000L
        const val MAX_PENDING_EVALUATIONS = 5
        const val KEY_PENDING_EVALUATIONS = "nsfw_pending_evaluations"
        const val KEY_LEGACY_PENDING_EVALUATION = "nsfw_pending_evaluation"
    }
}
