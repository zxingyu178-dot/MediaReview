package com.mediareview.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.media.LatestWinsScheduler
import com.mediareview.app.core.media.PlayerCore
import com.mediareview.app.core.media.ReviewPlayable
import com.mediareview.app.core.media.ReviewQueueWindow
import com.mediareview.app.core.model.ReviewQueueItemDto
import com.mediareview.app.core.ui.InitialLoadGate
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val sessionId: String = "",
    val resumed: Boolean = false,
    /** 起始 pager 位置(本地索引,基于已加载分页)。 */
    val startIndex: Int = 0,
    /** 已加载列表第一项对应的绝对索引(分页懒加载 + 断点恢复 + 向前分页用)。 */
    val baseIndex: Int = 0,
    val items: List<ReviewQueueItemDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val loadingMore: Boolean = false,
    val loadingPrev: Boolean = false,
    val likedSet: Set<String> = emptySet(),
    val deletedSet: Set<String> = emptySet(),
    /** 真实最后一条加入待删除的 media_id,撤销不依赖当前 pager index。 */
    val lastDeletedMediaId: String? = null,
)

/** 批阅页事件(供 UI 响应,不直接改 pager 状态)。 */
sealed interface ReviewEvent {
    /** 服务器 enqueue 待删除成功后发出(UI 据此翻到下一条)。 */
    data object DeleteSucceeded : ReviewEvent
}

private const val PAGE_SIZE = 40

/**
 * 批阅模式:会话由服务端按已选媒体库构建,队列分页懒加载(支持数千/上万媒体)。
 * - 断点恢复:只加载包含目标绝对索引的分页并正确定位(baseIndex)
 * - **latest-wins**:页面快速连续滑动时取消旧的切换/位置任务 + 令牌校验,只保留最新一次
 * - **P0/P1**:页面停稳后立即切换已 ready 的当前视频;下一条 URL 获取与 prepare 在后台。
 *   槽位身份一律使用服务端**绝对 queue index**(ReviewQueueItemDto.index),向前分页
 *   prepend 导致的 pager localIndex 变化不会影响 PlayerCore 槽位匹配。
 * - **进度上报防串片**:切换前快照旧 mediaId 的播放器再上报;禁止拿新 activePlayer 给旧 mediaId
 * - **position 串行上报**:Channel.CONFLATED + 单消费者,快速滑动时服务器永远按正确顺序收到最新位置
 * - 向前分页:向上滑到顶部时加载更早分页并保持定位;结束判断 baseIndex+items.size >= total
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: MediaRepository,
    val core: PlayerCore,
    private val invalidations: ContentInvalidationStore,
) : ViewModel() {
    private val initialLoad = InitialLoadGate()

    private val _ui = MutableStateFlow(ReviewUiState())
    val ui: StateFlow<ReviewUiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<ReviewEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ReviewEvent> = _events.asSharedFlow()

    private val streamUrlCache = mutableMapOf<String, String>()

    /** 分页窗口(纯逻辑,可单测)。 */
    private val window = ReviewQueueWindow(PAGE_SIZE)

    /** latest-wins:取消旧任务并领取新令牌,只有最新令牌的切换才执行。 */
    private val settleScheduler = LatestWinsScheduler()
    private var settleJob: Job? = null

    /** 当前正在播放的 mediaId(切换前用于补报旧媒体最后进度)。 */
    private var currentMediaId: String = ""

    /** position 上报通道:CONFLATED 只保留最新位置,单消费者串行写服务器。 */
    private val positionChannel = Channel<Pair<String, Int>>(Channel.CONFLATED)

    init {
        // 单消费者串行上报 position:快速滑动时旧请求不会晚到覆盖新位置
        viewModelScope.launch {
            for ((sessionId, index) in positionChannel) {
                runCatching { repository.setReviewPosition(sessionId, index) }
            }
        }
    }

    fun loadIfNeeded() {
        if (initialLoad.claim()) load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val latest = repository.latestReviewSession()
                if (latest != null && latest.status == "active") {
                    resumeSession(latest.session_id, latest.current_index)
                } else {
                    createNewSession()
                }
            } catch (e: Exception) {
                _ui.value = ReviewUiState(loading = false, error = e.message ?: "加载批阅失败")
            }
        }
    }

    /** 强制新建会话(服务端会先完成旧 active 会话)。 */
    fun startNew() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                createNewSession()
            } catch (e: Exception) {
                _ui.value = ReviewUiState(loading = false, error = e.message ?: "创建批阅会话失败")
            }
        }
    }

    private suspend fun createNewSession() {
        val session = repository.createReviewSession()
            ?: throw IllegalStateException("创建批阅会话失败")
        resumeSession(session.session_id, 0)
    }

    private suspend fun resumeSession(sessionId: String, absoluteIndex: Int) {
        // 只加载包含目标绝对索引的分页并正确定位(baseIndex + 本地索引 = 绝对索引)
        val plan = window.resumePage(absoluteIndex)
        settleScheduler.reset()
        _ui.update {
            it.copy(
                sessionId = sessionId,
                resumed = true,
                startIndex = plan.localStart,
                baseIndex = plan.baseIndex,
                page = plan.page,
            )
        }
        val result = repository.reviewQueue(sessionId, page = plan.page, pageSize = PAGE_SIZE)
        window.applyInitial(result.items, result.total, plan.page)
        _ui.update {
            it.copy(
                loading = false,
                items = window.items,
                total = window.total,
                page = window.page,
                baseIndex = window.baseIndex,
            )
        }
        restoreMarkedStates()
    }

    /** 启动恢复已有喜欢/待删除状态。 */
    private suspend fun restoreMarkedStates() {
        val favs = repository.listFavorites()
        val dels = repository.listDeleteQueue()
        _ui.update {
            it.copy(
                likedSet = favs.mapNotNull { it.media_id }.toSet(),
                deletedSet = dels.mapNotNull { it.media_id }.toSet(),
            )
        }
    }

    /**
     * 接近底部时加载下一页。结束判断:已加载窗口末端 baseIndex + items.size 达到 total。
     * 任何失败都必须恢复 loadingMore 并允许重试。
     */
    fun loadMore() {
        val st = _ui.value
        if (st.loadingMore || st.loadingPrev || st.sessionId.isBlank()) return
        if (window.atEnd()) return
        val nextPage = window.nextPage()
        viewModelScope.launch {
            _ui.update { it.copy(loadingMore = true) }
            try {
                val page = repository.reviewQueue(st.sessionId, page = nextPage, pageSize = PAGE_SIZE)
                window.append(page.items, page.total, nextPage)
                _ui.update {
                    it.copy(
                        page = window.page,
                        items = window.items,
                        total = window.total,
                    )
                }
            } finally {
                _ui.update { it.copy(loadingMore = false) }
            }
        }
    }

    /**
     * 向前分页:向上滑回到更早分页时加载前一页,并前置到列表头部。
     * @param onLoaded 前置条数(供 UI 补偿 pager 偏移)。
     */
    fun loadPrev(onLoaded: (Int) -> Unit = {}) {
        val st = _ui.value
        if (st.loadingPrev || st.loadingMore || st.sessionId.isBlank()) return
        if (!window.canLoadPrev()) return
        val prevPage = window.prevPage()
        viewModelScope.launch {
            _ui.update { it.copy(loadingPrev = true) }
            try {
                val page = repository.reviewQueue(st.sessionId, page = prevPage, pageSize = PAGE_SIZE)
                val added = window.prepend(page.items, page.total, prevPage)
                _ui.update {
                    it.copy(
                        page = window.page,
                        baseIndex = window.baseIndex,
                        startIndex = it.startIndex + added,
                        items = window.items,
                        total = window.total,
                    )
                }
                onLoaded(added)
            } finally {
                _ui.update { it.copy(loadingPrev = false) }
            }
        }
    }

    /**
     * 页面停稳后调用(本地索引):标记 seen、上报位置、切换播放。
     *
     * latest-wins:取消旧任务 + 令牌校验;旧媒体切换前先快照并补报最后进度。
     * 槽位身份使用服务端绝对 queue index(`ReviewQueueItemDto.index`),prepend 不破坏匹配。
     * 当前视频 URL 只解析当前项(立即切换);下一条 URL 获取 + prepare 放后台。
     */
    fun onSettled(localIndex: Int, onLoadPrev: (Int) -> Unit = {}) {
        val st = _ui.value
        if (localIndex !in st.items.indices) return
        // 接近末尾提前加载(边界保护,避免空白页)
        if (localIndex >= st.items.size - 5) loadMore()
        // 接近顶部且尚未加载到第一页时向前分页(并保持定位)
        if (localIndex <= 2 && window.canLoadPrev()) loadPrev(onLoaded = onLoadPrev)
        // 绝对队列索引:prepend 后 localIndex 会变化,但 DTO.index 恒定
        val absolute = st.items[localIndex].index
        val media = st.items[localIndex].media ?: return
        val mediaId = media.media_id
        val prevMediaId = currentMediaId
        if (st.sessionId.isNotBlank()) {
            viewModelScope.launch { repository.markSeen(st.sessionId, mediaId) }
            // position 走 CONFLATED 通道串行上报,只保留最新位置
            positionChannel.trySend(st.sessionId to absolute)
        }
        settleJob?.cancel()
        val token = settleScheduler.next()
        settleJob = viewModelScope.launch {
            // 媒体切换前:快照旧 mediaId 对应播放器再补报最后进度(防串片)
            if (prevMediaId.isNotBlank() && prevMediaId != mediaId) {
                snapshotAndReport(prevMediaId)
            }
            // 立即切换当前视频:只解析当前项 URL(绝对索引),不等待"下一条的下一条"
            val current = resolvePlayable(st.items, localIndex)
            if (!settleScheduler.isValid(token)) return@launch // 已被更新的滑动取代
            core.settle(current.index, current)
            currentMediaId = mediaId
            // P1 后台:获取下一条 URL + prepare(绝对索引),不阻塞当前播放
            val nextIndex = localIndex + 1
            val next = resolvePlayable(st.items, nextIndex)
            if (!settleScheduler.isValid(token)) return@launch
            core.prepareNext(current.index + 1, next)
        }
    }

    /** 滑动中:播放交给 settle,这里不切换。 */
    fun onSwipeStarted() {
        core.onSwipeStarted()
    }

    /** 点赞/取消点赞(只写本项目数据库,成功才更新 UI)。 */
    fun onLike(index: Int) {
        val st = _ui.value
        val media = st.items.getOrNull(index)?.media ?: return
        val id = media.media_id
        viewModelScope.launch {
            if (id in st.likedSet) {
                if (repository.removeFavorite(id)) {
                    _ui.update { it.copy(likedSet = it.likedSet - id) }
                    invalidations.invalidate(ContentArea.Favorites)
                }
            } else {
                if (repository.addFavorite(id)) {
                    _ui.update { it.copy(likedSet = it.likedSet + id) }
                    invalidations.invalidate(ContentArea.Favorites)
                }
            }
        }
    }

    /**
     * 加入待删除(可撤销),保存真实 media_id,显示撤销提示。
     * 只有服务器 enqueue 成功后才发出 [ReviewEvent.DeleteSucceeded](UI 据此翻到下一条)。
     */
    fun onDelete(index: Int) {
        val st = _ui.value
        val media = st.items.getOrNull(index)?.media ?: return
        val id = media.media_id
        viewModelScope.launch {
            if (repository.enqueueDelete(id)) {
                _ui.update {
                    it.copy(
                        deletedSet = it.deletedSet + id,
                        lastDeletedMediaId = id,
                    )
                }
                _events.tryEmit(ReviewEvent.DeleteSucceeded)
                invalidations.invalidate(ContentArea.DeleteQueue)
            }
        }
    }

    /** 撤销待删除:使用 lastDeletedMediaId,服务器成功后才更新 UI。 */
    fun undoDelete() {
        val id = _ui.value.lastDeletedMediaId ?: return
        viewModelScope.launch {
            if (repository.dequeueDelete(id)) {
                _ui.update {
                    it.copy(
                        deletedSet = it.deletedSet - id,
                        lastDeletedMediaId = null,
                    )
                }
                invalidations.invalidate(ContentArea.DeleteQueue)
            }
        }
    }

    /** 关闭删除撤销提示。 */
    fun clearSnackbar() = _ui.update { it.copy(lastDeletedMediaId = null) }

    /**
     * 回传播放进度:按 mediaId 快照其对应播放器(不拿新的 activePlayer 给旧 mediaId)。
     * 媒体已切换导致快照缺失时直接跳过,防止串片。
     */
    fun reportPosition(mediaId: String) {
        if (mediaId.isBlank()) return
        val snap = core.snapshotFor(mediaId) ?: return
        viewModelScope.launch {
            repository.reportProgress(mediaId, snap.positionMs, !snap.isPlaying)
        }
    }

    /** 供批阅页获取当前视频要显示哪个播放器实例。 */
    fun activePlayer() = core.activePlayer()

    /** 主壳切离批阅根时停止不可见页面的声音，同时取消尚未落地的 settle。 */
    fun onRootDeactivated() {
        settleJob?.cancel()
        core.deactivateReview()
    }

    private suspend fun snapshotAndReport(mediaId: String) {
        val snap = core.snapshotFor(mediaId) ?: return
        runCatching {
            repository.reportProgress(mediaId, snap.positionMs, !snap.isPlaying)
        }
    }

    /** 只解析 [index] 一项的 URL;槽位身份使用 DTO 的绝对 queue index。 */
    private suspend fun resolvePlayable(
        items: List<ReviewQueueItemDto>,
        index: Int,
    ): ReviewPlayable {
        val dto = items.getOrNull(index)
        val media = dto?.media
        if (media == null) return ReviewPlayable(dto?.index ?: index, "")
        if (!media.isVideo) return ReviewPlayable(dto.index, media.media_id)
        val url = streamUrlCache.getOrPut(media.media_id) {
            repository.loadPlayback(media.media_id)?.stream_url.orEmpty()
        }
        return ReviewPlayable(dto.index, media.media_id, url.ifBlank { null })
    }

    override fun onCleared() {
        settleJob?.cancel()
        core.release()
        super.onCleared()
    }
}
