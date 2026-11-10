package com.mediareview.app.feature.v2.nsfw

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.nsfw.data.NsfwApiException
import com.mediareview.app.feature.v2.nsfw.data.NsfwConnectionSnapshot
import com.mediareview.app.feature.v2.nsfw.data.NsfwDecisionResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwErrorCodes
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationJournal
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationRequest
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedStore
import com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairingGateway
import com.mediareview.app.feature.v2.nsfw.data.NsfwPendingEvaluation
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptImageItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptLink
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptSummary
import com.mediareview.app.feature.v2.nsfw.data.NsfwReviewDataSource
import com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryFilter
import com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryQuery
import com.mediareview.app.feature.v2.nsfw.data.NsfwGallerySort
import com.mediareview.app.feature.v2.nsfw.data.NsfwSavedGalleryReview
import com.mediareview.app.feature.v2.nsfw.data.NSFW_GALLERY_CAPABILITY
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 同一提示词版本下的全部未确认记录；多条时必须能逐条切换核对。 */
    val pendingRequests: List<NsfwPendingEvaluation> = emptyList(),
    val pendingIndex: Int = 0,
) {
    val scoringEnabled: Boolean
        get() = versionId != null || wholePromptScopeConfirmed
}

data class NsfwUndoDecision(val imageId: String, val restoreAction: String, val expectedUpdatedAt: String)

data class NsfwUiState(
    val phase: NsfwPhase = NsfwPhase.LOADING,
    val baseUrl: String = "",
    val items: List<NsfwFeedItem> = emptyList(),
    val currentId: String? = null,
    val restoringBrowsePosition: Boolean = false,
    val hasBrowsePosition: Boolean = false,
    val galleryAnchor: com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor? = null,
    val browseRecoveryMessage: String? = null,
    val detail: NsfwImageDetails? = null,
    val detailLoading: Boolean = false,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val message: String? = null,
    val missingCapabilities: List<String> = emptyList(),
    val conflictMessage: String? = null,
    val cursorExpired: Boolean = false,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val newImageCount: Int = 0,
    val undoDecision: NsfwUndoDecision? = null,
    val panel: NsfwPromptPanel? = null,
    val pairingBusy: Boolean = false,
    /** 本地仍有已保存配对：兼容性未知时可重新检查连接，不必重新生成配对码。 */
    val hasSavedPairing: Boolean = false,
    /** 进程重启后发现上次评分未确认时的提示（不自动重发）。 */
    val evaluationRecoveryHint: String? = null,
    val galleryEnabled: Boolean = false,
    val bidirectionalGallery: Boolean = false,
    val previousPageCursor: String? = null,
    val galleryStartIndex: Int = 0,
    val galleryTotal: Int = 0,
    val galleryPositionValid: Boolean = false,
    val galleryQuery: NsfwGalleryQuery = NsfwGalleryQuery(),
    val galleryViewEpoch: Int = 0,
) {
    val currentIndex: Int
        get() = items.indexOfFirst { it.imageId == currentId }.coerceAtLeast(0)

    val currentItem: NsfwFeedItem?
        get() = items.firstOrNull { it.imageId == currentId }

    val reviewPositionLabel: String get() = when {
        items.isEmpty() -> "0 / 0"
        bidirectionalGallery && galleryPositionValid -> "${galleryStartIndex + currentIndex + 1} / $galleryTotal"
        bidirectionalGallery -> "位置待校验 · 已加载 ${items.size} 张"
        else -> "${currentIndex + 1} / ${items.size} · 已加载"
    }
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
    private val journal: NsfwEvaluationJournal,
    private val positions: com.mediareview.app.feature.v2.nsfw.data.NsfwBrowsePositionStore = com.mediareview.app.feature.v2.nsfw.data.EmptyNsfwBrowsePositionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(NsfwUiState())
    val state: StateFlow<NsfwUiState> = _state.asStateFlow()

    private val feed = NsfwFeedStore()
    // 提示数跟随仍在队列中的新增身份，不累计 UPSERT/REMOVE 事件次数。
    private val pendingNewImageIds = mutableSetOf<String>()
    private var lastCursor: String? = null
    private var historyCatchup = true
    private var pollJob: Job? = null
    private val pollBackoff = NsfwPollBackoff()
    private var screenVisible = false
    private var detailSeq = 0
    private var panelSeq = 0
    private var browseOrigin = ""
    private var pendingBrowseId: String? = null
    private var browseReadFailed = false
    private var galleryAnchorReadFailed = false
    private val browseWriteGate = Mutex()
    private var connectionEpoch = 0
    private var feedReadGate = Mutex()
    private var manualFeedJob: Job? = null
    private var galleryGeneration = 0
    private var galleryScope: String? = null
    private var galleryPageCursor: String? = null
    private var galleryWatermark: String? = null
    private var galleryResumeAnchor: String? = null
    private var savedGalleryReview: NsfwSavedGalleryReview? = null
    private val pendingEvaluations = readPendingEvaluations().toMutableList()

    /** 仅测试可调；生产为 15 秒。 */
    internal var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            hydratePending()
            applyConnectionSnapshot(runCatching { pairing.load() }.getOrNull())
        }
    }

    /** 已保存配对遇到临时断网时，重新执行健康检查；不需要用户重新输入配对码。 */
    fun retryConnectionCheck() {
        val state = _state.value
        if (state.phase == NsfwPhase.READY || state.pairingBusy) return
        if (state.baseUrl.isBlank() && !state.hasSavedPairing) return
        _state.update { it.copy(pairingBusy = true, message = "正在重新检查已保存的 NSFW 连接…") }
        viewModelScope.launch {
            applyConnectionSnapshot(runCatching { pairing.load() }.getOrNull())
        }
    }

    private suspend fun applyConnectionSnapshot(snapshot: NsfwConnectionSnapshot?) {
        if (snapshot == null || !snapshot.paired) {
            _state.update {
                it.copy(
                    phase = NsfwPhase.UNPAIRED,
                    baseUrl = snapshot?.baseUrl.orEmpty(),
                    message = snapshot?.message,
                    busy = false,
                    pairingBusy = false,
                    hasSavedPairing = false,
                )
            }
            return
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
                    pairingBusy = false,
                    hasSavedPairing = true,
                )
            }
            return
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
                    pairingBusy = false,
                    hasSavedPairing = true,
                )
            }
            return
        }
        val browseEpoch = connectionEpoch
        val origin = originOf(snapshot.baseUrl)
        if (browseOrigin != origin) {
            var scopeReadFailed = false
            val savedGallery = try {
                positions.loadGalleryReview(origin)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { scopeReadFailed = true; null }
            var galleryAnchor: com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor? = null
            var readFailed = false
            var galleryReadFailed = false
            // 日期图库首次进入创建新快照，不能在首屏加载前暴露旧快照的滚动锚点。
            // 旧图片及范围仍由 savedGalleryReview 保留，用户点“继续上次”才恢复。
            if (NSFW_GALLERY_CAPABILITY !in snapshot.capabilities) {
                try { galleryAnchor = positions.loadGalleryAnchor(origin) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { galleryReadFailed = true }
            }
            val saved = try {
                positions.loadPosition(origin)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readFailed = true; null }
            if (browseEpoch != connectionEpoch) return
            browseOrigin = origin
            savedGalleryReview = savedGallery
            browseReadFailed = readFailed
            galleryAnchorReadFailed = galleryReadFailed
            pendingBrowseId = saved?.takeIf { it.isNotBlank() && !scopeReadFailed }
            _state.update { it.copy(restoringBrowsePosition = pendingBrowseId != null, hasBrowsePosition = pendingBrowseId != null || savedGallery != null, galleryAnchor = galleryAnchor,
                browseRecoveryMessage = when {
                    scopeReadFailed -> "上次审核范围读取失败，请从图库选择图片重新开始"
                    readFailed -> "上次浏览位置读取失败，可手动选择图片继续"
                    galleryReadFailed -> "图库位置读取失败，滑动图库后将记录新位置"
                    else -> null
                }) }
        }
        updateRecoveryHint(snapshot.baseUrl)
        _state.update {
            it.copy(
                phase = NsfwPhase.READY,
                baseUrl = snapshot.baseUrl,
                pairingBusy = false,
                hasSavedPairing = true,
                missingCapabilities = emptyList(),
                galleryEnabled = NSFW_GALLERY_CAPABILITY in snapshot.capabilities,
                bidirectionalGallery = com.mediareview.app.feature.v2.nsfw.data.NSFW_BIDIRECTIONAL_CAPABILITY in snapshot.capabilities,
            )
        }
        syncFromCursor(reset = true)
        maybeStartPolling()
    }

    fun pair(baseUrl: String, code: String) {
        if (_state.value.pairingBusy) return
        _state.update { it.copy(pairingBusy = true, message = null, missingCapabilities = emptyList()) }
        viewModelScope.launch {
            when (val result = pairing.checkAndPair(baseUrl, code)) {
                is NsfwPairResult.Paired -> {
                    advanceConnectionEpoch()
                    feed.clear()
                    lastCursor = null
                    historyCatchup = true
                    _state.update {
                        it.copy(
                            phase = NsfwPhase.LOADING,
                            baseUrl = result.baseUrl,
                            pairingBusy = false,
                            hasSavedPairing = true,
                            message = "已连接 NSFW Studio",
                            items = emptyList(),
                            currentId = null,
                            detail = null,
                            newImageCount = 0,
                            undoDecision = null,
                        )
                    }
                    applyConnectionSnapshot(NsfwConnectionSnapshot(
                        baseUrl = result.baseUrl, paired = true, compatible = true,
                        capabilities = result.capabilities,
                    ))
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
        if (_state.value.busy || _state.value.pairingBusy || _state.value.panel?.submitting == true) return
        viewModelScope.launch {
            advanceConnectionEpoch()
            stopPolling()
            pairing.clear()
            feed.clear()
            lastCursor = null
            historyCatchup = true
            _state.value = NsfwUiState(phase = NsfwPhase.UNPAIRED)
        }
    }

    /** 手动/回焦刷新：追平增量；不改变当前图片位置。 */
    fun refresh() {
        if (_state.value.phase != NsfwPhase.READY || _state.value.busy || _state.value.syncing || manualFeedJob?.isActive == true) return
        pollBackoff.reset()
        manualFeedJob = viewModelScope.launch(start = CoroutineStart.LAZY) { syncFromCursor(reset = false, manual = true) }
        manualFeedJob?.start()
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
        pollBackoff.reset()
        // 先保存LAZY任务再启动，避免测试dispatcher下同步回包重入启动轮询。
        pollJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            syncFromCursor(reset = false)
            while (isActive) {
                delay(pollBackoff.interval(pollIntervalMs))
                val snapshot = _state.value
                if (snapshot.phase == NsfwPhase.READY && !snapshot.busy) {
                    syncFromCursor(reset = false)
                }
            }
        }
        pollJob?.start()
    }

    private fun maybeStartPolling() {
        if (screenVisible && _state.value.phase == NsfwPhase.READY) setScreenVisible(true)
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        manualFeedJob?.cancel()
        manualFeedJob = null
    }

    private fun advanceConnectionEpoch() {
        connectionEpoch += 1
        galleryGeneration += 1
        galleryScope = null
        galleryPageCursor = null
        galleryWatermark = null
        galleryResumeAnchor = null
        savedGalleryReview = null
        _state.update { it.copy(galleryEnabled = false, bidirectionalGallery = false, previousPageCursor = null,
            galleryPositionValid = false, galleryStartIndex = 0, galleryTotal = 0,
            galleryQuery = NsfwGalleryQuery(), galleryViewEpoch = galleryGeneration) }
        browseOrigin = ""
        pendingBrowseId = null
        browseReadFailed = false
        galleryAnchorReadFailed = false
        _state.update { it.copy(restoringBrowsePosition = false, browseRecoveryMessage = null) }
        pendingNewImageIds.clear()
        feedReadGate = Mutex()
        _state.update { it.copy(syncing = false) }
    }

    private suspend fun withFeedRead(block: suspend (Int) -> Unit) {
        val gate = feedReadGate
        if (!gate.tryLock()) return
        val epoch = connectionEpoch
        val queryGeneration = galleryGeneration
        _state.update { it.copy(syncing = true) }
        try { block(epoch) }
        finally {
            gate.unlock()
            if (epoch == connectionEpoch && queryGeneration == galleryGeneration) _state.update { it.copy(syncing = false) }
        }
    }

    fun loadMore() {
        val state = _state.value
        if (state.phase != NsfwPhase.READY || state.busy || state.syncing || manualFeedJob?.isActive == true || !state.hasMore) return
        if (state.galleryEnabled) {
            manualFeedJob = viewModelScope.launch(start = CoroutineStart.LAZY) { withFeedRead { epoch ->
                loadGalleryPage(epoch, append = true)
            } }
            manualFeedJob?.start()
            return
        }
        val cursor = lastCursor?.takeIf { it.isNotBlank() }
        if (cursor == null) {
            _state.update {
                it.copy(hasMore = false, nextCursor = null, message = "缺少 next_cursor，无法继续加载；已停止本轮")
            }
            return
        }
        manualFeedJob = viewModelScope.launch(start = CoroutineStart.LAZY) { withFeedRead { epoch ->
            val messageBefore = _state.value.message
            try {
                val result = fetchPageWithRecovery(cursor)
                if (epoch != connectionEpoch) return@withFeedRead
                applyPage(result, manual = _state.value.message == messageBefore)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch != connectionEpoch) return@withFeedRead
                handleFetchFailure(error)
            }
        } }
        manualFeedJob?.start()
    }

    fun select(imageId: String) {
        if (imageId.isBlank() || _state.value.items.none { it.imageId == imageId }) return
        pendingBrowseId = null
        browseReadFailed = false
        _state.update { it.copy(restoringBrowsePosition = false, hasBrowsePosition = true,
            browseRecoveryMessage = if (galleryAnchorReadFailed) "图库位置读取失败，滑动图库后将记录新位置" else null) }
        persistBrowsePosition(imageId)
        if (imageId == _state.value.currentId) {
            if (_state.value.galleryEnabled && _state.value.detail == null && !_state.value.detailLoading) loadDetails(imageId)
            return
        }
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

    fun updateGalleryAnchor(
        anchor: com.mediareview.app.feature.v2.nsfw.data.NsfwGalleryAnchor,
        userInitiated: Boolean = false,
    ) {
        if (galleryAnchorReadFailed && !userInitiated) return
        if (_state.value.items.none { it.imageId == anchor.imageId }) return
        val origin = originOf(_state.value.baseUrl)
        if (origin.isBlank()) return
        _state.update { it.copy(galleryAnchor = anchor) }
        val epoch = connectionEpoch
        viewModelScope.launch {
            try {
                browseWriteGate.withLock { positions.saveGalleryAnchor(origin, anchor) }
                if (epoch == connectionEpoch && galleryAnchorReadFailed) {
                    galleryAnchorReadFailed = false
                    if (!browseReadFailed) _state.update { it.copy(browseRecoveryMessage = null) }
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == connectionEpoch) _state.update { it.copy(browseRecoveryMessage = "图库位置保存失败") }
            }
        }
    }

    private fun persistBrowsePosition(imageId: String) {
        val origin = originOf(_state.value.baseUrl)
        if (origin.isBlank()) return
        val epoch = connectionEpoch
        val review = galleryScope?.takeIf { _state.value.galleryEnabled }?.let {
            NsfwSavedGalleryReview(_state.value.galleryQuery, it, imageId)
        }
        if (review != null) savedGalleryReview = review
        viewModelScope.launch {
            try { browseWriteGate.withLock {
                positions.savePosition(origin, imageId)
                if (review != null) positions.saveGalleryReview(origin, review)
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == connectionEpoch) _state.update {
                    it.copy(browseRecoveryMessage = "浏览位置保存失败，重开后可能无法恢复")
                }
            }
        }
    }

    private fun browseTarget(items: List<NsfwFeedItem>, current: String?, index: Int, historyComplete: Boolean): String? {
        val pending = pendingBrowseId
        if (pending != null) {
            if (items.any { it.imageId == pending }) {
                pendingBrowseId = null
                _state.update { it.copy(restoringBrowsePosition = false,
                    browseRecoveryMessage = if (galleryAnchorReadFailed) "图库位置读取失败，滑动图库后将记录新位置" else null) }
                return pending
            }
            if (!historyComplete) return null
            pendingBrowseId = null
            _state.update { it.copy(restoringBrowsePosition = false,
                hasBrowsePosition = false, browseRecoveryMessage = if (items.isEmpty()) "上次图片已不在当前图库" else "上次图片已不在当前图库，已定位到可用图片") }
        }
        if (browseReadFailed) return null
        return current?.takeIf { id -> items.any { it.imageId == id } }
            ?: items.getOrNull(index.coerceIn(0, items.lastIndex.coerceAtLeast(0)))?.imageId
    }

    fun next() {
        val state = _state.value
        val index = state.currentIndex
        if (index + 1 < state.items.size) select(state.items[index + 1].imageId)
        else if (state.bidirectionalGallery && state.hasMore) loadGalleryBoundary(previous = false, navigate = true)
    }

    fun previous() {
        val state = _state.value
        val index = state.currentIndex
        if (index > 0) select(state.items[index - 1].imageId)
        else if (state.bidirectionalGallery && state.previousPageCursor != null) loadGalleryBoundary(previous = true, navigate = true)
    }

    fun loadPrevious() = loadGalleryBoundary(previous = true, navigate = false)

    private fun loadGalleryBoundary(previous: Boolean, navigate: Boolean) {
        val state = _state.value
        if (!state.bidirectionalGallery || state.phase != NsfwPhase.READY || state.busy || state.syncing || manualFeedJob?.isActive == true) return
        if (previous && state.previousPageCursor == null || !previous && !state.hasMore) return
        val original = state.currentId
        val generation = galleryGeneration
        manualFeedJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            withFeedRead { epoch ->
                val success = loadGalleryPage(epoch, append = !previous, prepend = previous)
                if (success && navigate && generation == galleryGeneration && _state.value.currentId == original) {
                    val updated = _state.value
                    val index = updated.items.indexOfFirst { it.imageId == original }
                    if (index >= 0) updated.items.getOrNull(index + if (previous) -1 else 1)?.let { select(it.imageId) }
                }
            }
        }
        manualFeedJob?.start()
    }

    fun decision(action: String?, favorite: Boolean? = null) = submitDecision(action, favorite)

    private fun submitDecision(action: String?, favorite: Boolean?, undo: NsfwUndoDecision? = null) {
        val state = _state.value
        val detail = if (undo != null) NsfwImageDetails(imageId = undo.imageId, updatedAt = undo.expectedUpdatedAt)
            else state.detail?.takeIf { it.imageId == state.currentId } ?: return
        val previousItem = state.items.firstOrNull { it.imageId == detail.imageId }
        val restoreAction = previousItem?.takeIf { it.updatedAt == detail.updatedAt && it.revision == detail.revision }?.let {
            when (it.status) { "NEW" -> "RESET"; "KEEP" -> "KEEP"; "REJECT" -> "REJECT"; else -> null }
        }
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
                    if (_state.value.currentId == detail.imageId) loadDetails(detail.imageId)
                    if (undo != null) _state.update { it.copy(undoDecision = null) }
                    return@launch
                }
                _state.update {
                    it.copy(busy = false, message = if (undo != null) "已撤销上一次审核" else decisionMessage(action),
                        undoDecision = when {
                            undo != null -> null
                            action in setOf("KEEP", "REJECT") -> restoreAction?.let { restore -> NsfwUndoDecision(detail.imageId, restore, result.updatedAt) }
                            it.undoDecision?.imageId == detail.imageId -> null
                            else -> it.undoDecision
                        })
                }
                if (undo != null && _state.value.currentId == state.currentId) select(undo.imageId)
                // 只有回执已接受且用户仍停留在提交图片时才前进。
                if (undo == null && action in setOf("KEEP", "REJECT") && _state.value.currentId == detail.imageId) {
                    next()
                }
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
                    if (_state.value.currentId == detail.imageId) loadDetails(detail.imageId)
                    if (undo != null) _state.update { it.copy(undoDecision = null) }
                } else {
                    handleError(error)
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun undoLastDecision() {
        val undo = _state.value.undoDecision ?: return
        submitDecision(undo.restoreAction, null, undo)
    }

    fun viewLatestImages() {
        if (_state.value.galleryEnabled) {
            changeGalleryFilter(_state.value.galleryQuery.filter.copy(sort = NsfwGallerySort.NEWEST))
            return
        }
        val latest = _state.value.items.lastOrNull() ?: return
        pendingNewImageIds.clear()
        select(latest.imageId)
        _state.update { it.copy(newImageCount = 0) }
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
        val matching = pendingListFor(promptId, explicitVersion, originOf(_state.value.baseUrl))
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
            pendingRequests = matching,
            pendingIndex = 0,
            message = existingPending?.let {
                if (matching.size > 1) {
                    "有 ${matching.size} 条评分结果未确认；当前第 1 条（请求号 ${it.request.clientRequestId}），" +
                        "可切换核对其他记录，或直接重试当前记录。"
                } else {
                    "上次提交结果未确认，已冻结原评分（请求号 ${it.request.clientRequestId}）；可直接重试或查询回执。"
                }
            },
        )
        _state.update { it.copy(panel = panel) }
        refreshPromptPanel(panel)
    }

    /** 同一提示词版本存在多条未确认记录时，逐条切换核对，避免旧记录永久无法访问。 */
    fun selectPendingEvaluation(index: Int) {
        val panel = _state.value.panel ?: return
        val pending = panel.pendingRequests.getOrNull(index) ?: return
        panelSeq += 1
        _state.update {
            it.copy(
                panel = panel.copy(
                    requestId = pending.request.clientRequestId,
                    pendingRequest = pending.request,
                    pendingIndex = index,
                    submitted = null,
                    unconfirmed = true,
                    freshDraft = false,
                    wholePromptScopeConfirmed = pending.versionId != null ||
                        pending.request.promptVersionId == null,
                    message = "正在核对第 ${index + 1}/${panel.pendingRequests.size} 条未确认评分" +
                        "（请求号 ${pending.request.clientRequestId}）",
                ),
            )
        }
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
        val origin = originOf(_state.value.baseUrl)
        val pending = NsfwPendingEvaluation(
            serverOrigin = origin,
            promptId = panel.promptId,
            versionId = panel.versionId,
            request = request,
        )
        val epoch = connectionEpoch
        _state.update {
            it.copy(
                panel = panel.copy(pendingRequest = request, submitting = true, message = null, unconfirmed = true),
            )
        }
        viewModelScope.launch {
            // 必须先持久化冻结载荷，再发网络请求：强制停止后仍能按原请求号核对回执。
            if (!persistPending(pending)) {
                _state.update {
                    val current = it.panel ?: return@update it
                    if (current.requestId != panel.requestId) it
                    else it.copy(
                        panel = current.copy(
                            submitting = false,
                            unconfirmed = false,
                            message = "无法保存未确认评分记录，本次未发送（不会产生重复评分）",
                        ),
                    )
                }
                return@launch
            }
            try {
                // 重启/重试时先查回执，绝不盲目重发；确认未写入后才提交。
                if (frozen != null) {
                    val receipt = runCatching {
                        dataSource.evaluationReceipt(request.clientRequestId)
                    }.getOrNull()
                    if (receipt == null) {
                        if (epoch == connectionEpoch) {
                            _state.update {
                                val current = it.panel ?: return@update it
                                if (current.requestId != panel.requestId) it
                                else it.copy(
                                    panel = current.copy(
                                        submitting = false,
                                        message = "无法确认上次提交结果，本次未重发；请稍后再试或继续保留未确认记录",
                                    ),
                                )
                            }
                        }
                        return@launch
                    }
                    val existing = receipt.takeIf { it.found }?.evaluation
                    if (existing != null) {
                        completePendingSuccess(origin, epoch, panel, request, existing, "评分已保存（回执核对）")
                        return@launch
                    }
                }
                val result = dataSource.createEvaluation(panel.promptId, request)
                completePendingSuccess(origin, epoch, panel, request, result, "评分已保存")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val apiError = error as? NsfwApiException
                // 响应身份不一致时不得用回执自动"修正"，保留未确认记录并明确报错。
                val existing = if (apiError?.code == NsfwErrorCodes.RESPONSE_MISMATCH) {
                    null
                } else {
                    // 超时/结果未知：先用同一 client_request_id 查回执，绝不盲目重复提交
                    runCatching { dataSource.evaluationReceipt(request.clientRequestId) }
                        .getOrNull()
                        ?.takeIf { it.found }
                        ?.evaluation
                }
                if (existing != null) {
                    completePendingSuccess(origin, epoch, panel, request, existing, "评分已保存（回执核对）")
                    return@launch
                }
                val text = when (apiError?.code) {
                    NsfwErrorCodes.PROMPT_VERSION_UNKNOWN ->
                        "该提示词版本未知，请改为对提示词整体评分"
                    NsfwErrorCodes.VALIDATION_ERROR -> apiError.message
                    NsfwErrorCodes.IDEMPOTENCY_CONFLICT -> "同一请求号已提交过不同内容，请点“新建评分请求”"
                    NsfwErrorCodes.RESPONSE_MISMATCH ->
                        "服务端返回的评分结果与请求身份不一致，已拒绝；未确认记录已保留，请稍后查询回执或重试"
                    else -> "评分结果未确认，可安全重试（同一请求号，不会重复计分）"
                }
                if (epoch != connectionEpoch) return@launch
                _state.update {
                    val current = it.panel ?: return@update it
                    if (current.requestId != panel.requestId) return@update it
                    it.copy(
                        panel = current.copy(
                            submitting = false,
                            pendingRequest = request,
                            pendingRequests = pendingListFor(panel.promptId, panel.versionId, origin),
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

    private suspend fun completePendingSuccess(
        origin: String,
        epoch: Int,
        panel: NsfwPromptPanel,
        request: NsfwEvaluationRequest,
        result: NsfwEvaluationResult,
        note: String,
    ) {
        removePending(origin, request.clientRequestId)
        if (epoch != connectionEpoch) return
        _state.update {
            val current = it.panel ?: return@update it
            if (current.requestId != panel.requestId) return@update it
            val remaining = pendingListFor(panel.promptId, panel.versionId, origin)
            it.copy(
                panel = current.copy(
                    submitting = false,
                    submitted = result,
                    pendingRequest = request,
                    pendingRequests = remaining,
                    pendingIndex = 0,
                    freshDraft = false,
                    unconfirmed = remaining.isNotEmpty(),
                    message = if (remaining.isEmpty()) {
                        note
                    } else {
                        "$note；该提示词还有 ${remaining.size} 条未确认记录，可切换核对"
                    },
                ),
                message = "提示词评分已保存",
            )
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
        // 空字符串游标等同"从头开始"，绝不能发给服务端，否则可能反复读取历史第一页。
        var cursor = initialCursor?.takeIf { it.isNotBlank() }
        var resetApplied = false
        while (true) {
            try {
                val page = dataSource.feed(cursor, PAGE_SIZE)
                if (page.hasMore && page.nextCursor.isNullOrBlank()) {
                    throw NsfwApiException("CURSOR_MISSING", "服务端未返回有效 next_cursor", 0)
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
        withFeedRead { epoch ->
            if (_state.value.galleryEnabled) {
                if (lastCursor == null || reset) loadGalleryPage(epoch, scope = galleryScope,
                    anchor = galleryResumeAnchor ?: _state.value.currentId?.takeIf { galleryScope != null })
                else syncGalleryChanges(epoch)
            } else syncFeed(epoch, reset, manual)
        }
    }

    fun changeGalleryFilter(filter: NsfwGalleryFilter) {
        val query = try { filter.resolve() }
        catch (error: Exception) { _state.update { it.copy(message = error.message ?: "日期范围无效") }; return }
        requestGallery(query)
    }

    fun resumeGalleryReview() {
        if (!_state.value.galleryEnabled) { _state.value.currentId?.let(::select); return }
        val saved = savedGalleryReview
        val anchor = saved?.imageId ?: pendingBrowseId
        if (anchor == null) { _state.value.currentId?.let(::select); return }
        requestGallery(saved?.query ?: _state.value.galleryQuery, saved?.scopeCursor, anchor)
    }

    private fun requestGallery(query: NsfwGalleryQuery, scope: String? = null, anchor: String? = null) {
        val state = _state.value
        if (!state.galleryEnabled || state.phase != NsfwPhase.READY || state.busy || state.panel?.submitting == true) return
        stopPolling()
        galleryGeneration += 1
        detailSeq += 1
        galleryScope = scope
        galleryResumeAnchor = anchor
        galleryPageCursor = null
        galleryWatermark = null
        lastCursor = null
        feedReadGate = Mutex()
        feed.clear()
        pendingNewImageIds.clear()
        _state.update { it.copy(galleryQuery = query, galleryViewEpoch = galleryGeneration,
            previousPageCursor = null, galleryStartIndex = 0, galleryTotal = 0, galleryPositionValid = false,
            items = emptyList(), currentId = null, detail = null, detailLoading = false,
            galleryAnchor = null, restoringBrowsePosition = anchor != null,
            newImageCount = 0, hasMore = false, nextCursor = null, message = null,
            cursorExpired = false, panel = null, syncing = false) }
        manualFeedJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            withFeedRead { loadGalleryPage(it, scope = scope, anchor = anchor) }
            maybeStartPolling()
        }
        manualFeedJob?.start()
    }

    private suspend fun loadGalleryPage(epoch: Int, append: Boolean = false, scope: String? = null, anchor: String? = null, prepend: Boolean = false): Boolean {
        val generation = galleryGeneration
        val query = _state.value.galleryQuery
        try {
            val extending = append || prepend
            val cursor = if (prepend) _state.value.previousPageCursor else galleryPageCursor.takeIf { append }
            if (extending && cursor.isNullOrBlank()) throw NsfwApiException("CURSOR_MISSING", "缺少图库分页游标，请查看最新或重新选择范围")
            val page = dataSource.gallery(query, pageCursor = cursor, scopeCursor = scope, anchorId = anchor, limit = PAGE_SIZE)
            if (epoch != connectionEpoch || generation != galleryGeneration) return false
            if (page.scopeCursor.isBlank() || page.syncCursor.isBlank() ||
                (scope != null && page.scopeCursor != scope) ||
                (anchor != null && page.items.firstOrNull()?.imageId != anchor) ||
                (page.hasMore && (page.nextPageCursor.isNullOrBlank() || page.nextPageCursor == cursor)) ||
                (extending && (page.scopeCursor != galleryScope || page.syncCursor != galleryWatermark)) ||
                (_state.value.bidirectionalGallery && (page.startIndex == null || page.startIndex < 0 || page.total < page.startIndex + page.items.size))) {
                throw NsfwApiException("GALLERY_SCOPE_MISMATCH", "图库范围或分页信息不一致，请重新选择范围")
            }
            if (!extending) {
                feed.clear()
                pendingNewImageIds.clear()
                galleryScope = page.scopeCursor
                galleryWatermark = page.syncCursor
                lastCursor = page.syncCursor
            }
            val beforeCount = feed.size()
            if (prepend) feed.prepend(NsfwFeedPage(items = page.items)) else feed.merge(NsfwFeedPage(items = page.items))
            if (!prepend) galleryPageCursor = page.nextPageCursor
            val items = feed.snapshot()
            val target = if (extending) _state.value.currentId?.takeIf { feed.find(it) != null } ?: items.firstOrNull()?.imageId
                else anchor?.takeIf { feed.find(it) != null } ?: items.firstOrNull()?.imageId
            _state.update { it.copy(items = items, currentId = target, hasMore = if (prepend) it.hasMore else page.hasMore,
                nextCursor = galleryPageCursor, restoringBrowsePosition = false,
                previousPageCursor = if (append) it.previousPageCursor else page.previousPageCursor,
                galleryStartIndex = if (append) ((page.startIndex ?: 0) - beforeCount).coerceAtLeast(0) else page.startIndex ?: 0,
                galleryTotal = page.total, galleryPositionValid = page.startIndex != null,
                galleryAnchor = if (extending) it.galleryAnchor else null, newImageCount = pendingNewImageIds.size,
                detail = it.detail?.takeIf { detail -> detail.imageId == target },
                message = null, cursorExpired = false) }
            galleryResumeAnchor = null
            if (anchor != null && target == anchor) { pendingBrowseId = null; persistBrowsePosition(anchor); loadDetails(anchor) }
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (epoch == connectionEpoch && generation == galleryGeneration) galleryFailure(error)
            return false
        }
    }

    private suspend fun syncGalleryChanges(epoch: Int) {
        val generation = galleryGeneration
        var cursor = lastCursor ?: return
        try {
            repeat(MAX_SYNC_PAGES) {
                val page = dataSource.feed(cursor, PAGE_SIZE)
                if (epoch != connectionEpoch || generation != galleryGeneration) return
                val next = page.nextCursor?.takeIf { it.isNotBlank() }
                    ?: throw NsfwApiException("CURSOR_MISSING", "缺少增量游标，请查看最新重新加载")
                if (page.hasMore && next == cursor) throw NsfwApiException("CURSOR_STALLED", "增量游标未推进，请查看最新重新加载")
                val known = feed.snapshot().mapTo(mutableSetOf()) { it.imageId }
                for (item in page.items) {
                    if (item.event == "REMOVE") pendingNewImageIds.remove(item.imageId)
                    else if (item.imageId !in known && item.eventType in setOf("IMAGE_CREATED", "IMAGE_IMPORTED") &&
                        item.revision.matches(Regex("[0-9]+")) && _state.value.galleryQuery.contains(item.createdAt)) pendingNewImageIds.add(item.imageId)
                }
                val merge = feed.merge(page.copy(items = page.items.filter { it.imageId in known }))
                pendingNewImageIds.removeAll(merge.removedIds.toSet())
                val items = feed.snapshot()
                val previous = _state.value
                val target = previous.currentId?.takeIf { feed.find(it) != null }
                    ?: items.getOrNull(previous.currentIndex.coerceAtMost(items.lastIndex.coerceAtLeast(0)))?.imageId
                _state.update { state -> state.copy(items = items, currentId = target, newImageCount = pendingNewImageIds.size,
                    galleryPositionValid = state.galleryPositionValid && page.items.none { it.event == "REMOVE" },
                    detail = state.detail?.takeIf { it.imageId == target }) }
                if (target != null && (target != previous.currentId || (target in merge.updatedIds && previous.detail != null))) loadDetails(target)
                lastCursor = next
                cursor = next
                pollBackoff.result(page.items.isNotEmpty())
                if (!page.hasMore) return
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (epoch == connectionEpoch && generation == galleryGeneration) galleryFailure(error) }
    }

    private fun galleryFailure(error: Exception) {
        pollBackoff.result(false)
        if (error is NsfwApiException && error.isAuthRejected) { handleAuthFailure(); return }
        _state.update { it.copy(restoringBrowsePosition = false,
            cursorExpired = error is NsfwApiException && error.code == NsfwErrorCodes.CURSOR_EXPIRED,
            message = if (error is NsfwApiException && error.code == NsfwErrorCodes.CURSOR_EXPIRED)
                "原范围或增量已过期，请返回图库查看最新或重新选择日期" else error.message ?: "图库加载失败，请重试") }
    }

    private suspend fun syncFeed(epoch: Int, reset: Boolean, manual: Boolean) {
        val messageBefore = _state.value.message
        if (reset) {
            lastCursor = null
            historyCatchup = true
        }
        val indexBefore = _state.value.currentIndex
        val receivedNewIds = mutableSetOf<String>()
        var receivedChanges = false
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
                throw error
            } catch (error: Exception) {
                if (epoch != connectionEpoch) return
                pollBackoff.result(false)
                handleFetchFailure(error)
                return
            }
            if (epoch != connectionEpoch) return
            val page = result.page
            receivedChanges = receivedChanges || page.items.isNotEmpty()
            if (result.resetApplied) {
                recovered = true
                historyCatchup = true
            }
            val merge = feed.merge(page)
            pendingNewImageIds.removeAll(merge.removedIds.toSet())
            receivedNewIds.removeAll(merge.removedIds.toSet())
            // 首次历史同步与 410 后整段历史重建都不算"新到"；只有真正增量才计数。
            if (!historyCatchup) {
                val aliveAddedIds = merge.addedIds.filter { feed.find(it) != null }
                pendingNewImageIds.addAll(aliveAddedIds)
                receivedNewIds.addAll(aliveAddedIds)
            }
            pages += 1
            finalHasMore = page.hasMore
            finalNextCursor = page.nextCursor?.takeIf { it.isNotBlank() }
            if (!page.hasMore && page.nextCursor.isNullOrBlank()) {
                terminalMissingCursor = true
                lastCursor = null
                historyCatchup = true
            } else {
                lastCursor = page.nextCursor
                if (!page.hasMore) historyCatchup = false
            }
            // 每页均恢复有效当前位置；后续页断网不能留下已移除图片的详情/操作。
            val pageSnapshot = feed.snapshot()
            val currentBeforePage = _state.value.currentId
            val pageTarget = browseTarget(pageSnapshot, currentBeforePage, indexBefore, !historyCatchup)
            _state.update {
                it.copy(
                    items = pageSnapshot,
                    currentId = pageTarget,
                    detail = it.detail?.takeIf { detail -> detail.imageId == pageTarget },
                    detailLoading = it.detailLoading && it.currentId == pageTarget,
                    newImageCount = pendingNewImageIds.size,
                    hasMore = page.hasMore,
                    nextCursor = page.nextCursor?.takeIf { it.isNotBlank() },
                )
            }
            if (pageTarget != null && pageTarget != currentBeforePage) loadDetails(pageTarget)
            if (!page.hasMore) break
            cursor = page.nextCursor
        }
        if (epoch != connectionEpoch) return
        pollBackoff.result(receivedChanges)
        val state = _state.value
        val snapshotNow = feed.snapshot()
        val targetId = browseTarget(snapshotNow, state.currentId, indexBefore, !historyCatchup)
        val message = when {
            state.message != messageBefore -> state.message
            recovered -> "游标已过期，已从头重新同步"
            terminalMissingCursor -> "服务端未返回 next_cursor，下次将从头同步"
            manual && receivedNewIds.isNotEmpty() -> "已同步 ${receivedNewIds.size} 张新图"
            else -> _state.value.message
        }
        _state.update {
            it.copy(
                phase = NsfwPhase.READY,
                items = snapshotNow,
                currentId = targetId,
                hasMore = finalHasMore,
                nextCursor = finalNextCursor,
                cursorExpired = false,
                detail = it.detail?.takeIf { d -> d.imageId == targetId },
                detailLoading = it.detailLoading && it.currentId == targetId,
                newImageCount = pendingNewImageIds.size,
                message = message,
            )
        }
        if (targetId != null && targetId != _state.value.detail?.imageId && !_state.value.detailLoading) loadDetails(targetId)
        maybeStartPolling()
    }

    private fun applyPage(fetch: PageFetchResult, manual: Boolean) {
        val page = fetch.page
        val indexBefore = _state.value.currentIndex
        val merge = feed.merge(page)
        pendingNewImageIds.removeAll(merge.removedIds.toSet())
        if (fetch.resetApplied) historyCatchup = true
        val missingTerminalCursor = !page.hasMore && page.nextCursor.isNullOrBlank()
        if (missingTerminalCursor) historyCatchup = true
        else if (!page.hasMore) historyCatchup = false
        lastCursor = if (missingTerminalCursor) null else page.nextCursor
        val currentId = _state.value.currentId
        val snapshot = feed.snapshot()
        val targetId = browseTarget(snapshot, currentId, indexBefore, !historyCatchup)
        _state.update {
            it.copy(
                items = feed.snapshot(),
                newImageCount = pendingNewImageIds.size,
                currentId = targetId,
                hasMore = page.hasMore,
                nextCursor = page.nextCursor?.takeIf { it.isNotBlank() },
                cursorExpired = false,
                detail = it.detail?.takeIf { d -> d.imageId == targetId },
                detailLoading = it.detailLoading && it.detail?.imageId == targetId,
                message = when {
                    fetch.resetApplied -> "游标已过期，已从头重新同步"
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
            // 失效游标不能继续留在内部水位，否则下次手动刷新会再次命中同一 410。
            lastCursor = null
            historyCatchup = true
            _state.update {
                it.copy(
                    cursorExpired = true,
                    hasMore = false,
                    nextCursor = null,
                    message = "游标已过期且从头同步失败，请稍后手动刷新",
                )
            }
            return
        }
        if (error is NsfwApiException && error.code in setOf("CURSOR_MISSING", "CURSOR_STALLED")) {
            // 游标缺失/停滞同样必须清理内部水位，让用户可通过刷新从头恢复。
            lastCursor = null
            historyCatchup = true
            _state.update {
                it.copy(
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
            advanceConnectionEpoch()
            stopPolling()
            pairing.handleAuthRejected()
            feed.clear()
            lastCursor = null
            historyCatchup = true
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

    private fun pendingForPanel(promptId: String, versionId: String?): NsfwPendingEvaluation? =
        pendingListFor(promptId, versionId, originOf(_state.value.baseUrl)).firstOrNull()

    private fun pendingListFor(
        promptId: String,
        versionId: String?,
        origin: String,
    ): List<NsfwPendingEvaluation> = pendingEvaluations.filter {
        it.promptId == promptId && it.versionId == versionId && it.serverOrigin == origin
    }

    private fun originOf(url: String): String = url.toHttpUrlOrNull()
        ?.let { "${it.scheme}://${it.host}:${it.port}" }
        .orEmpty()

    /**
     * 先写 DataStore 再入内存：失败时不得发送网络请求，否则强制停止后会留下无法核对的新请求。
     */
    private suspend fun persistPending(pending: NsfwPendingEvaluation): Boolean {
        val next = pendingEvaluations.filterNot {
            it.serverOrigin == pending.serverOrigin &&
                it.request.clientRequestId == pending.request.clientRequestId
        } + pending
        val saved = runCatching { journal.savePendingEvaluations(next) }.isSuccess
        if (!saved) return false
        pendingEvaluations.clear()
        pendingEvaluations += next
        savePendingEvaluations()
        updateRecoveryHint(_state.value.baseUrl)
        return true
    }

    private suspend fun removePending(origin: String, clientRequestId: String) {
        pendingEvaluations.removeAll {
            it.serverOrigin == origin && it.request.clientRequestId == clientRequestId
        }
        runCatching { journal.savePendingEvaluations(pendingEvaluations) }
        savePendingEvaluations()
        updateRecoveryHint(_state.value.baseUrl)
    }

    /** 启动时以 DataStore 为准恢复；旧版本 SavedState 记录只作为一次性迁移来源。 */
    private suspend fun hydratePending() {
        val legacy = readPendingEvaluations()
        val persisted = runCatching { journal.loadPendingEvaluations() }.getOrNull()
        val source = persisted?.takeIf { it.isNotEmpty() } ?: legacy
        pendingEvaluations.clear()
        pendingEvaluations += source
        if (persisted.isNullOrEmpty() && legacy.isNotEmpty()) {
            runCatching { journal.savePendingEvaluations(pendingEvaluations) }
        }
        savePendingEvaluations()
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
