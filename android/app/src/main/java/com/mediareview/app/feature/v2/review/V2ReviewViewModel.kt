package com.mediareview.app.feature.v2.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.model.V2Media
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 批阅模式（Stage7 收稳）ViewModel：
 *
 * 生命周期（替代 Stage6 的 loadIfNeeded）：
 * - 每次真正进入 Review 页面调用 [enterReview]——若刚打开过完整播放器（[onLeaveForFullPlayer]）
 *   则恢复该 Session（不重建队列、不跳回第 1 条）；否则重新检查 Repository 建立"最新未审队列"，
 *   不依赖 loadIfNeeded 的偶然状态；
 * - 队列 = 进入时刻的未审视频（视频优先）；已批阅不重排队列（避免跳动）；
 * - 页停稳 + 稳定 ~480ms 自动 [markReviewed]（进度持久化）；
 * - 完成条件 = 本次队列所有条目均已批阅（[isComplete]），不再依赖"末页已批阅"的弱判定；
 * - 收藏复用仓库状态；待删除用独立 [pendingDeleteIds]（与 reviewed 解耦，可撤销）；
 * - "重新批阅"显式拆两个入口：本次队列重审 [restartCurrentSession] / 全库重审 [restartAllVideos]。
 *   空队列完成页调用 [restartAllVideos]，保证"没有待批阅时点重新批阅"真正能生成全量队列。
 */
@HiltViewModel
class V2ReviewViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _queue = MutableStateFlow<List<ReviewMediaSource>>(emptyList())
    val queue: StateFlow<List<ReviewMediaSource>> = _queue.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeleteIds: StateFlow<Set<String>> = _pendingDeleteIds.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    private val _reviewedIds = MutableStateFlow<Set<String>>(emptySet())
    val reviewedIds: StateFlow<Set<String>> = _reviewedIds.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()

    /** 离开前的会话快照（仅用于"完整播放器 Back 恢复"）。 */
    private var session: ReviewSession? = null

    /** 是否为"打开完整播放器后返回"：true 时 [enterReview] 恢复会话而非重建队列。 */
    private var leftForFullPlayer = false

    /**
     * 进入批阅页面（每次组合进入都调用，含完整播放器返回）。
     * - 刚从完整播放器返回：恢复原 Session（保持在原页、保留已批阅/待删除）；
     * - 普通进入（含从其他 Tab 回来）：重新检查 Repository 建立最新未审队列。
     */
    fun enterReview() {
        viewModelScope.launch {
            val saved = session
            if (leftForFullPlayer && saved != null) {
                leftForFullPlayer = false
                restoreSession(saved, repository.pendingDeleteIds())
            } else {
                refreshQueue()
            }
        }
    }

    /** 打开完整播放器前调用：把当前进度记入 Session，返回后恢复而非重建队列。 */
    fun onLeaveForFullPlayer() {
        leftForFullPlayer = true
        session = snapshotSession()
    }

    /** 页面停稳后由 UI 稳定判定触发自动批阅。 */
    fun markReviewed(mediaId: String) {
        viewModelScope.launch {
            repository.markReviewed(mediaId)
            _reviewedIds.value = _reviewedIds.value + mediaId
            updateComplete()
            snapshotSession()
        }
    }

    /** 当前停稳页（UI 在 settle 后回调，用于 Session 记录与原页恢复）。 */
    fun onPageSettled(index: Int) {
        _currentIndex.value = index.coerceIn(0, _queue.value.lastIndex.coerceAtLeast(0))
        snapshotSession()
    }

    fun toggleFavorite(mediaId: String) {
        viewModelScope.launch {
            val now = mediaId !in _favoriteIds.value
            repository.setFavorite(mediaId, now)
            _favoriteIds.value = if (now) _favoriteIds.value + mediaId
            else _favoriteIds.value - mediaId
            snapshotSession()
        }
    }

    /** 加入待删除（可撤销；与批阅状态独立）。 */
    fun addPendingDelete(mediaId: String) {
        viewModelScope.launch {
            repository.setPendingDelete(mediaId, true)
            _pendingDeleteIds.value = _pendingDeleteIds.value + mediaId
            snapshotSession()
        }
    }

    /** 撤销待删除。 */
    fun undoPendingDelete(mediaId: String) {
        viewModelScope.launch {
            repository.setPendingDelete(mediaId, false)
            _pendingDeleteIds.value = _pendingDeleteIds.value - mediaId
            snapshotSession()
        }
    }

    /**
     * 重新批阅"本次队列"：仅清除当前队列的已批阅标记并回到第 1 条。
     * 不清除不在当前队列里的视频（语义收敛，避免误改）。
     */
    fun restartCurrentSession() {
        viewModelScope.launch {
            _queue.value.forEach { repository.unmarkReviewed(it.mediaId) }
            _reviewedIds.value = emptySet()
            _currentIndex.value = 0
            _isComplete.value = false
            snapshotSession()
        }
    }

    /**
     * 重新批阅"全部视频"：读取所有视频 → 清除每个视频的已批阅 →
     * 重建全量队列 → 定位第 1 条。空队列完成页必须调用本方法
     * （旧实现只操作 `_queue.value`，空队列时什么都不会做）。
     */
    fun restartAllVideos() {
        viewModelScope.launch {
            repository.media().filter { it.isVideo }.forEach { repository.unmarkReviewed(it.id) }
            rebuildQueue(allVideos(), repository.pendingDeleteIds())
            snapshotSession()
        }
    }

    fun isFavorite(mediaId: String): Boolean = mediaId in _favoriteIds.value
    fun isPendingDelete(mediaId: String): Boolean = mediaId in _pendingDeleteIds.value
    fun isReviewed(mediaId: String): Boolean = mediaId in _reviewedIds.value

    // ---------- Session / 状态内部 ----------

    /** 重新检查 Repository：重建"最新未审视频"队列（视频优先，VIDEO ONLY）。 */
    private suspend fun refreshQueue() {
        rebuildQueue(allVideos().filter { !it.isReviewed }, repository.pendingDeleteIds())
        snapshotSession()
    }

    /** 从 Repository 拉取全部视频（已应用收藏/批阅等状态覆盖）。 */
    private suspend fun allVideos(): List<V2Media> =
        repository.media().filter { it.isVideo }

    private fun rebuildQueue(videos: List<V2Media>, pendingDelete: Set<String>) {
        _queue.value = videos.map { buildSource(it) }
        val ids = _queue.value.map { it.mediaId }
        _reviewedIds.value = videos.filter { it.isReviewed }.map { it.id }.toSet().intersect(ids.toSet())
        _pendingDeleteIds.value = pendingDelete
        _favoriteIds.value = videos.filter { it.isFavorite }.map { it.id }.toSet()
        _currentIndex.value = 0
        _ready.value = true
        updateComplete()
    }

    /** 队列全部已批阅才算完成（取代旧"末页已批阅"弱判定）。 */
    private fun updateComplete() {
        _isComplete.value = _queue.value.isNotEmpty() && _queue.value.all { it.mediaId in _reviewedIds.value }
    }

    /**
     * 恢复会话：完整播放器返回场景。队列保持（进入完整播放器期间不被外部改动），
     * 按 session 恢复已批阅 / 待删除 / 当前页。
     */
    private fun restoreSession(saved: ReviewSession, pendingDelete: Set<String>) {
        val idx = saved.currentMediaId?.let { mid -> _queue.value.indexOfFirst { it.mediaId == mid } } ?: 0
        _currentIndex.value = idx.coerceAtLeast(0)
        _pendingDeleteIds.value = pendingDelete + saved.pendingDeleteIds
        _reviewedIds.value = saved.reviewedIds
        _ready.value = true
        updateComplete()
    }

    private fun snapshotSession(): ReviewSession {
        val s = ReviewSession(
            queueIds = _queue.value.map { it.mediaId },
            currentMediaId = _queue.value.getOrNull(_currentIndex.value)?.mediaId,
            reviewedIds = _reviewedIds.value,
            pendingDeleteIds = _pendingDeleteIds.value,
            sessionStarted = System.currentTimeMillis(),
        )
        session = s
        return s
    }

    /** 由 V2Media 构建 UI 唯一可见的 [ReviewMediaSource]（URL 完全由 Repository 解析，UI 不接触 Demo 资源）。 */
    private fun buildSource(m: V2Media): ReviewMediaSource = ReviewMediaSource(
        mediaId = m.id,
        title = m.name,
        code = m.code,
        folderName = m.folderName,
        durationMs = m.durationMs,
        naturalWidth = m.naturalWidth,
        naturalHeight = m.naturalHeight,
        playbackUrl = repository.playbackUri(m.id),
        headers = repository.playbackHeaders(m.id),
        coverUrl = repository.coverUri(m),
    )
}