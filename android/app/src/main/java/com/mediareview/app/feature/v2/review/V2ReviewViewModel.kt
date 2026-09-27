package com.mediareview.app.feature.v2.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.perf.V2Perf
import com.mediareview.app.feature.v2.player.V2PlaybackDecision
import com.mediareview.app.feature.v2.player.V2PlaybackSourceController
import com.mediareview.app.feature.v2.player.V2PlaybackUiState
import com.mediareview.app.feature.v2.review.data.ReviewQueuePageResult
import com.mediareview.app.feature.v2.review.data.ReviewQueuePaging
import com.mediareview.app.feature.v2.review.data.ReviewSessionOpen
import com.mediareview.app.feature.v2.review.data.V2ReviewSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 批阅模式 ViewModel（Stage 8B：真实 Server Review Session 接入）。
 *
 * 会话 / 队列 / seen / position / complete 全部委托 [V2ReviewSessionRepository]
 * （Server 或 Demo 由路由器按数据源模式决定，本 ViewModel 不感知模式）。
 *
 * 关键语义：
 * - **进入**：恢复最新 active 会话；只有数据源明确"没有会话"才新建；latest 失败 → Error
 *   （§17，绝不误建）；深位置恢复只拉包含 current_index 的那一页（§21）；
 * - **播放**：只解析当前项（P0），最多预取"下一条"一条（P1），**绝不批量 resolvePlayback**（§5/§33）；
 *   播放语义与完整播放器共用 [V2PlaybackSourceController]（Direct → 一次 HLS → Error + stale guard）；
 * - **seen**：页面停稳 + 内容揭示后由 UI 触发；**服务端确认成功才更新本地**（§26），失败可重试；
 * - **position**：settled 页变化才上报，latest-wins（取消旧请求，§28）；
 * - **完成**：队列真正走到底且末项已确认批阅 → 调用服务端 complete，**服务端确认后**才进入完成页（§40）；
 * - **完整播放器返回**：恢复原会话与原绝对位置，不重建会话（§37）。
 */
@HiltViewModel
class V2ReviewViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val sessions: V2ReviewSessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<V2ReviewUiState>(V2ReviewUiState.Idle)
    val state: StateFlow<V2ReviewUiState> = _state.asStateFlow()

    /** 当前项的播放源状态（UI 只消费本状态，不直接发网络请求）。 */
    private val _playback = MutableStateFlow<V2PlaybackUiState>(V2PlaybackUiState.Loading)
    val playback: StateFlow<V2PlaybackUiState> = _playback.asStateFlow()

    private val _pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeleteIds: StateFlow<Set<String>> = _pendingDeleteIds.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    private val _messages = MutableSharedFlow<ReviewMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<ReviewMessage> = _messages.asSharedFlow()

    /** 播放源控制器：与完整播放器共享同一套 Direct / HLS / stale 语义。 */
    private val sources = V2PlaybackSourceController()

    private var resolveJob: Job? = null
    private var prefetchJob: Job? = null
    private var positionJob: Job? = null
    private var leavePositionJob: Job? = null
    private var enterJob: Job? = null

    /** 本次会话内已确认批阅的媒体 id（服务端确认成功才加入）。 */
    private val confirmedSeen = mutableSetOf<String>()

    /** 会话建立时的服务端 seen_count 起点。 */
    private var baseSeenCount = 0

    /** 是否为"打开完整播放器后返回"：true 时 [enterReview] 恢复会话而非重新进入。 */
    private var leftForFullPlayer = false
    private var savedAbsoluteIndex = 0

    // ---------- 进入 / 恢复 ----------

    /**
     * 进入批阅页面（每次组合进入都调用，含完整播放器返回）。
     * - 刚从完整播放器返回：恢复原会话与原绝对位置（不重建会话，§37）；
     * - 普通进入：恢复最新 active 会话（没有才新建）。
     */
    fun enterReview() {
        if (leftForFullPlayer) {
            leftForFullPlayer = false
            restoreAfterPlayerReturn()
            return
        }
        openSession(forceNew = false)
    }

    /** Error 层的"重新尝试"。 */
    fun retryEnter() {
        openSession(forceNew = false)
    }

    private fun openSession(forceNew: Boolean) {
        enterJob?.cancel()
        enterJob = viewModelScope.launch {
            _state.value = V2ReviewUiState.LoadingSession
            _playback.value = V2PlaybackUiState.Loading
            V2Perf.openReview()
            val opened = try {
                sessions.enterSession(forceNew)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ReviewSessionOpen.Failed(error.message ?: "无法恢复批阅")
            }
            when (opened) {
                is ReviewSessionOpen.Ready -> applyOpened(opened)
                ReviewSessionOpen.Empty -> _state.value = V2ReviewUiState.Empty
                is ReviewSessionOpen.Failed -> _state.value = V2ReviewUiState.Error(opened.message)
            }
        }
    }

    private suspend fun applyOpened(opened: ReviewSessionOpen.Ready) {
        _state.value = V2ReviewUiState.LoadingQueue
        V2Perf.review()?.markOnce(
            "session_restore",
            "session_restore",
            { "total=${opened.session.totalCount}" },
        )
        V2Perf.review()?.markOnce(
            "queue_page",
            "queue_page",
            {
                "page=${opened.window.page} base=${opened.window.baseIndex} " +
                    "items=${opened.window.items.size} total=${opened.window.totalCount}"
            },
        )
        confirmedSeen.clear()
        baseSeenCount = opened.session.seenCount
        _pendingDeleteIds.value = runCatching { repository.pendingDeleteIds() }.getOrElse { error ->
            // 待删除集合失败不能让批阅进不去；但要如实提示（不假装"没有待删除"）
            _messages.tryEmit(ReviewMessage(error.message ?: "待删除状态获取失败"))
            emptySet()
        }
        _favoriteIds.value = opened.window.items.filter { it.favorite }.map { it.mediaId }.toSet()
        _state.value = V2ReviewUiState.Ready(
            sessionId = opened.session.sessionId,
            totalCount = opened.session.totalCount,
            absoluteCurrentIndex = opened.session.currentIndex,
            window = opened.window,
            seenCount = opened.session.seenCount,
        )
        resolveCurrent(resolveNext = true)
        maybeCompleteIfQueueFinished()
    }

    /** 完整播放器返回：恢复原窗口与原绝对位置，不重新创建会话（§37）。 */
    private fun restoreAfterPlayerReturn() {
        val ready = _state.value as? V2ReviewUiState.Ready
        if (ready == null) {
            // 进程被杀 / 状态丢失：走正常进入（服务端会返回同一个 active 会话）
            openSession(forceNew = false)
            return
        }
        val restored = if (ready.window.localIndexOf(savedAbsoluteIndex) != null) {
            savedAbsoluteIndex
        } else {
            ready.absoluteCurrentIndex
        }
        _state.value = ready.copy(absoluteCurrentIndex = restored)
        resolveCurrent(resolveNext = true)
    }

    // ---------- UI 事件 ----------

    /**
     * 页面停稳（pager settled）。[localIndex] 是 pager 的本地索引，
     * 立即映射成绝对索引（§24：绝对索引才是身份）。
     */
    fun onPageSettled(localIndex: Int) {
        val ready = _state.value as? V2ReviewUiState.Ready ?: return
        val item = ready.items.getOrNull(localIndex) ?: return
        val absolute = item.absoluteIndex
        if (absolute != ready.absoluteCurrentIndex) {
            _state.value = ready.copy(absoluteCurrentIndex = absolute)
        }
        // §28：只在 settled 页变化时上报位置；latest-wins（取消上一条请求）
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            runCatching { sessions.savePosition(absolute) }
        }
        // §20/§22：接近边界时预加载相邻页（绝不一次拉全量）
        maybeLoadAdjacent(ready, localIndex)
        resolveCurrent(resolveNext = true)
    }

    /** 内容稳定揭示后自动批阅（§25：settle + 停止滚动 + 已揭示 + 稳定 ~480ms 后调用）。 */
    fun markSeen(localIndex: Int) {
        val ready = _state.value as? V2ReviewUiState.Ready ?: return
        val item = ready.items.getOrNull(localIndex) ?: return
        if (item.mediaId in confirmedSeen) return
        viewModelScope.launch {
            if (sessions.markSeen(item.mediaId)) {
                confirmedSeen += item.mediaId
                val current = _state.value as? V2ReviewUiState.Ready
                if (current != null && current.sessionId == ready.sessionId) {
                    _state.value = current.copy(seenCount = baseSeenCount + confirmedSeen.size)
                }
                maybeCompleteIfQueueFinished()
            } else {
                // §26：不假成功 —— 保持未批阅，用户继续滑动/重试
                _messages.tryEmit(ReviewMessage("批阅标记未保存，稍后重试"))
            }
        }
    }

    fun toggleFavorite(mediaId: String) {
        viewModelScope.launch {
            val now = mediaId !in _favoriteIds.value
            // §34：复用仓库的"服务器确认制"，失败不改 UI 状态
            if (repository.setFavorite(mediaId, now)) {
                _favoriteIds.value = if (now) _favoriteIds.value + mediaId
                else _favoriteIds.value - mediaId
            } else {
                _messages.tryEmit(ReviewMessage(if (now) "收藏失败" else "取消收藏失败"))
            }
        }
    }

    /** 加入待删除（§36：服务端确认成功才更新 UI；失败保持原状态 + 提示）。 */
    fun addPendingDelete(mediaId: String) {
        viewModelScope.launch {
            if (repository.setPendingDelete(mediaId, true)) {
                _pendingDeleteIds.value = _pendingDeleteIds.value + mediaId
                _messages.tryEmit(ReviewMessage("已加入待删除", undoMediaId = mediaId))
            } else {
                _messages.tryEmit(ReviewMessage("加入待删除失败"))
            }
        }
    }

    /** 撤销待删除（服务端确认成功才更新 UI）。 */
    fun undoPendingDelete(mediaId: String) {
        viewModelScope.launch {
            if (repository.setPendingDelete(mediaId, false)) {
                _pendingDeleteIds.value = _pendingDeleteIds.value - mediaId
            } else {
                _messages.tryEmit(ReviewMessage("撤销待删除失败"))
            }
        }
    }

    fun isFavorite(mediaId: String): Boolean = mediaId in _favoriteIds.value

    fun isPendingDelete(mediaId: String): Boolean = mediaId in _pendingDeleteIds.value

    fun isSeen(mediaId: String): Boolean = mediaId in confirmedSeen

    /**
     * 打开完整播放器前调用（§37）：记录当前绝对位置并**写回服务端**，
     * 返回时恢复原会话（不重新创建）。
     */
    fun onLeaveForFullPlayer() {
        val ready = _state.value as? V2ReviewUiState.Ready ?: return
        leftForFullPlayer = true
        savedAbsoluteIndex = ready.absoluteCurrentIndex
        // 独立 Job：不随后续 settled 上报被取消，保证"离开前的位置"确实写到服务端
        leavePositionJob?.cancel()
        leavePositionJob = viewModelScope.launch {
            runCatching { sessions.savePosition(ready.absoluteCurrentIndex) }
        }
    }

    /** 会话完成页"重新批阅"（§39：Server = 新建会话；Demo = 本队列重审）。 */
    fun restart() {
        openSession(forceNew = true)
    }

    /** 空队列完成页"重新批阅"（§39：Server = 新建会话；Demo = 全量重审）。 */
    fun restartAll() {
        enterJob?.cancel()
        enterJob = viewModelScope.launch {
            _state.value = V2ReviewUiState.LoadingSession
            val opened = try {
                sessions.restartAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ReviewSessionOpen.Failed(error.message ?: "重建批阅队列失败")
            }
            when (opened) {
                is ReviewSessionOpen.Ready -> applyOpened(opened)
                ReviewSessionOpen.Empty -> _state.value = V2ReviewUiState.Empty
                is ReviewSessionOpen.Failed -> _state.value = V2ReviewUiState.Error(opened.message)
            }
        }
    }

    // ---------- 播放（P0 当前 / P1 下一条） ----------

    /** 重试当前项：必须真正回到 Direct 重新开始（与完整播放器同一套语义）。 */
    fun retryPlayback() {
        resolveCurrent(resolveNext = false)
    }

    /** 播放内核报错：带上"播放器里实际装载的 mediaId"，旧媒体的迟到错误会被忽略。 */
    fun onPlaybackFailed(failingMediaId: String) {
        if (failingMediaId.isBlank()) return
        applyDecision(sources.onPlaybackFailed(sources.token, failingMediaId))
    }

    private fun resolveCurrent(resolveNext: Boolean) {
        val ready = _state.value as? V2ReviewUiState.Ready ?: return
        val item = ready.items.getOrNull(ready.localCurrentIndex) ?: return
        resolveJob?.cancel()
        prefetchJob?.cancel()
        // §7/§8：解析开始前就切换 source identity（清空上一媒体的 source、回到 Direct）
        val eventToken = sources.moveTo(item.mediaId)
        _playback.value = V2PlaybackUiState.Loading
        resolveJob = viewModelScope.launch {
            val resolved = sources.takePrefetched(item.mediaId)
                ?: try {
                    repository.resolvePlayback(item.mediaId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    applyDecision(sources.onResolveFailed(eventToken, error.message))
                    return@launch
                }
            applyDecision(sources.onResolved(eventToken, resolved))
            if (resolveNext) prefetchNext()
        }
    }

    private fun applyDecision(decision: V2PlaybackDecision) {
        when (decision) {
            V2PlaybackDecision.Ignore -> return
            is V2PlaybackDecision.Play -> {
                val source = sources.source ?: return
                _playback.value = V2PlaybackUiState.Ready(source, decision.stage, decision.endpoint)
                V2Perf.review()?.markOnce("playback_info_ready", "playback_info_ready")
            }
            is V2PlaybackDecision.Fail -> {
                _playback.value = V2PlaybackUiState.Failed(decision.message ?: defaultFailureMessage())
            }
        }
    }

    /** P1：只预取"下一条"一个播放源（§33；快速滑动时旧预取自然作废）。 */
    private fun prefetchNext() {
        val ready = _state.value as? V2ReviewUiState.Ready ?: return
        val next = ready.items.getOrNull(ready.localCurrentIndex + 1) ?: return
        prefetchJob = viewModelScope.launch {
            val resolved = runCatching { repository.resolvePlayback(next.mediaId) }.getOrNull()
                ?: return@launch
            sources.prefetch(next.mediaId, resolved)
        }
    }

    // ---------- 分页 / 完成 ----------

    private fun maybeLoadAdjacent(ready: V2ReviewUiState.Ready, localIndex: Int) {
        val window = ready.window
        if (ReviewQueuePaging.shouldLoadNext(
                lastVisibleLocalIndex = localIndex,
                loadedCount = window.items.size,
                atEnd = window.atEnd,
                loading = ready.loadingNext,
            )
        ) {
            loadNextPage()
        }
        if (ReviewQueuePaging.shouldLoadPrev(
                firstVisibleLocalIndex = localIndex,
                canLoadPrev = window.canLoadPrev,
                loading = ready.loadingPrev,
            )
        ) {
            loadPrevPage()
        }
    }

    private fun loadNextPage() {
        val ready = _state.value as? V2ReviewUiState.Ready ?: return
        if (ready.loadingNext) return
        _state.value = ready.copy(loadingNext = true)
        viewModelScope.launch {
            val result = sessions.loadNextPage()
            applyPageResult(result, isNext = true)
        }
    }

    private fun loadPrevPage() {
        val ready = _state.value as? V2ReviewUiState.Ready ?: return
        if (ready.loadingPrev) return
        _state.value = ready.copy(loadingPrev = true)
        viewModelScope.launch {
            val result = sessions.loadPrevPage()
            applyPageResult(result, isNext = false)
        }
    }

    private fun applyPageResult(result: ReviewQueuePageResult?, isNext: Boolean) {
        val current = _state.value as? V2ReviewUiState.Ready ?: return
        if (result == null) {
            // 失败 / 已到边界：只恢复 loading 标记（不假成功，UI 可再次触发）
            _state.value = current.copy(
                loadingNext = if (isNext) false else current.loadingNext,
                loadingPrev = if (isNext) current.loadingPrev else false,
            )
            return
        }
        _state.value = current.copy(
            window = result.window,
            totalCount = result.window.totalCount,
            loadingNext = false,
            loadingPrev = false,
        )
        V2Perf.review()?.mark(
            "queue_page",
            {
                "page=${result.window.page} base=${result.window.baseIndex} " +
                    "items=${result.window.items.size} prepended=${result.prependedCount}"
            },
        )
        // 前置分页后本地索引整体后移：absoluteCurrentIndex 不变，localCurrentIndex 自动右移
        maybeCompleteIfQueueFinished()
    }

    /**
     * §40：队列真正走到底（已加载窗口到末尾且末项就是最后一条）**且**末项已确认批阅时，
     * 调用服务端 complete；**只有服务端确认成功才进入完成页**（长期状态以服务端为准）。
     */
    private fun maybeCompleteIfQueueFinished() {
        val ready = _state.value as? V2ReviewUiState.Ready ?: return
        val last = ready.items.lastOrNull() ?: return
        val reachedEnd = ready.window.atEnd && last.absoluteIndex == ready.totalCount - 1
        if (!reachedEnd || last.mediaId !in confirmedSeen) return
        viewModelScope.launch {
            if (sessions.completeSession()) {
                val current = _state.value as? V2ReviewUiState.Ready
                if (current?.sessionId == ready.sessionId) {
                    _state.value = V2ReviewUiState.Complete(ready.totalCount)
                }
            } else {
                _messages.tryEmit(ReviewMessage("会话完成未保存，请稍后重试"))
            }
        }
    }

    private fun defaultFailureMessage(): String =
        if (repository.mode == V2DataMode.SERVER) "播放失败，服务器未提供可用地址" else "播放失败"
}