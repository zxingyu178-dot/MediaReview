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
 * 批阅模式（Stage6 抖音式 V1）ViewModel：
 * - 队列 = 进入时刻的未审视频（视频优先）；
 * - 每页停稳 + 400~600ms 后自动 markReviewed（进度持久化，不重排队列）；
 * - 收藏复用仓库状态；待删除用独立 [pendingDeleteIds]（与 isReviewed 解耦，可撤销）；
 * - 全部滑完（最后一页已批阅）→ complete；"重新批阅"清除本次队列的已批阅标记。
 */
@HiltViewModel
class V2ReviewViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _queue = MutableStateFlow<List<V2Media>>(emptyList())
    val queue: StateFlow<List<V2Media>> = _queue.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeleteIds: StateFlow<Set<String>> = _pendingDeleteIds.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    private val _reviewedIds = MutableStateFlow<Set<String>>(emptySet())
    val reviewedIds: StateFlow<Set<String>> = _reviewedIds.asStateFlow()

    /** 首次加载（进入批阅 Tab 时调用；重复调用只加载一次）。 */
    fun loadIfNeeded() {
        if (_ready.value) return
        viewModelScope.launch {
            val videos = repository.media().filter { it.isVideo && !it.isReviewed }
            _queue.value = videos
            _pendingDeleteIds.value = repository.pendingDeleteIds()
            _favoriteIds.value = videos.filter { it.isFavorite }.map { it.id }.toSet()
            _reviewedIds.value = videos.filter { it.isReviewed }.map { it.id }.toSet()
            _ready.value = true
        }
    }

    /** 页面停稳后自动批阅（UI 在 settled 后延迟 ~480ms 调用）。 */
    fun markReviewed(mediaId: String) {
        viewModelScope.launch {
            repository.markReviewed(mediaId)
            _reviewedIds.value = _reviewedIds.value + mediaId
        }
    }

    fun toggleFavorite(mediaId: String) {
        viewModelScope.launch {
            val now = mediaId !in _favoriteIds.value
            repository.setFavorite(mediaId, now)
            _favoriteIds.value = if (now) _favoriteIds.value + mediaId
            else _favoriteIds.value - mediaId
        }
    }

    /** 加入待删除（可撤销）。 */
    fun addPendingDelete(mediaId: String) {
        viewModelScope.launch {
            repository.setPendingDelete(mediaId, true)
            _pendingDeleteIds.value = _pendingDeleteIds.value + mediaId
        }
    }

    /** 撤销待删除。 */
    fun undoPendingDelete(mediaId: String) {
        viewModelScope.launch {
            repository.setPendingDelete(mediaId, false)
            _pendingDeleteIds.value = _pendingDeleteIds.value - mediaId
        }
    }

    /** 重新批阅：清空本次队列的已批阅标记并回到起点。 */
    fun restartReview() {
        viewModelScope.launch {
            _queue.value.forEach { repository.unmarkReviewed(it.id) }
            _reviewedIds.value = emptySet()
        }
    }

    fun isFavorite(mediaId: String): Boolean = mediaId in _favoriteIds.value
    fun isPendingDelete(mediaId: String): Boolean = mediaId in _pendingDeleteIds.value
    fun isReviewed(mediaId: String): Boolean = mediaId in _reviewedIds.value
}