package com.mediareview.app.feature.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.core.ui.RevisionLoadGate
import com.mediareview.app.feature.home.data.MediaDataSource
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DuplicatesUiState(
    val loading: Boolean = true,
    val exact: List<DuplicateGroupDto> = emptyList(),
    val similar: List<DuplicateGroupDto> = emptyList(),
    val error: String? = null,
    /** 重复扫描后台任务(触发/进度/暂停/继续/取消)。 */
    val scanTaskId: String? = null,
    val scanStatus: String? = null, // pending | running | paused | succeeded | failed | cancelled
    val scanProgress: Int = 0,
    val scanError: String? = null,
    val scanPolling: Boolean = false,
    /** 双栏对比页:当前分组 + 成员媒体摘要。 */
    val compareGroup: DuplicateGroupDto? = null,
    val compareSummaries: Map<String, MediaSummary> = emptyMap(),
    val compareLoading: Boolean = false,
) {
    val scanActive: Boolean get() = scanStatus in SCAN_ACTIVE_STATUSES
    val scanSucceeded: Boolean get() = scanStatus == "succeeded"
}

/** 需要持续轮询的扫描状态。 */
private val SCAN_ACTIVE_STATUSES = setOf("pending", "running")

/** 轮询到的终态(停止轮询,等待人工操作或重新加载)。 */
private val SCAN_TERMINAL_STATUSES = setOf("succeeded", "failed", "cancelled", "paused")

/** 重复文件页:完全重复 + 疑似重复(只读展示,绝不自动删除) + 后台扫描 + 双栏对比/保留选择。 */
@HiltViewModel
class DuplicatesViewModel private constructor(
    private val repository: MediaDataSource,
    private val invalidations: ContentInvalidationStore,
    private val pollIntervalMs: Long = DEFAULT_SCAN_POLL_INTERVAL_MS,
) : ViewModel() {
    @Inject
    constructor(repository: MediaRepository, invalidations: ContentInvalidationStore) :
        this(repository as MediaDataSource, invalidations)

    internal constructor(
        repository: MediaDataSource,
        invalidations: ContentInvalidationStore,
        testSeam: Unit = Unit,
        pollIntervalMs: Long = DEFAULT_SCAN_POLL_INTERVAL_MS,
    ) : this(repository, invalidations, pollIntervalMs)
    private val loadGate = RevisionLoadGate()

    private val _ui = MutableStateFlow(DuplicatesUiState())
    val ui: StateFlow<DuplicatesUiState> = _ui.asStateFlow()

    fun loadIfNeeded() {
        if (loadGate.claim(invalidations.revision(ContentArea.Duplicates))) load()
    }

    fun load(invalidateOnSuccess: Boolean = false) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            refreshScanStatus()
            val state = _ui.value
            if (state.scanTaskId != null && state.scanStatus in SCAN_ACTIVE_STATUSES && !state.scanPolling) {
                pollScan(state.scanTaskId)
            }
            loadGroups()
            if (invalidateOnSuccess) invalidations.invalidate(ContentArea.Duplicates)
        }
    }

    fun refresh() = load(invalidateOnSuccess = true)

    // ---- 后台扫描任务控制 ----

    /** 触发重复扫描(服务端幂等);成功后开始轮询进度。 */
    fun triggerScan() {
        viewModelScope.launch {
            val task = repository.triggerDuplicateScan()
            val taskId = task?.task_id
            if (taskId.isNullOrBlank()) {
                _ui.update { it.copy(scanError = "无法发起重复扫描,请稍后重试") }
                return@launch
            }
            _ui.update {
                it.copy(
                    scanTaskId = taskId,
                    scanStatus = task.status,
                    scanProgress = task.progress,
                    scanError = null,
                )
            }
            pollScan(taskId)
        }
    }

    fun pauseScan() {
        val taskId = _ui.value.scanTaskId ?: return
        viewModelScope.launch {
            repository.pauseTask(taskId)
            _ui.update { it.copy(scanStatus = "paused", scanPolling = false) }
        }
    }

    fun resumeScan() {
        val taskId = _ui.value.scanTaskId ?: return
        viewModelScope.launch {
            val task = repository.resumeTask(taskId)
            _ui.update { it.copy(scanStatus = task?.status ?: "pending", scanError = null) }
            pollScan(taskId)
        }
    }

    fun cancelScan() {
        val taskId = _ui.value.scanTaskId ?: return
        viewModelScope.launch {
            repository.cancelTask(taskId)
            _ui.update { it.copy(scanStatus = "cancelled", scanPolling = false) }
            loadGroups()
        }
    }

    // ---- 双栏对比 + 保留选择 ----

    fun openCompare(groupId: String) {
        viewModelScope.launch {
            var group = findGroup(groupId)
            if (group == null) {
                loadGroups()
                group = findGroup(groupId)
            }
            if (group == null) return@launch
            _ui.update { it.copy(compareGroup = group, compareLoading = true, compareSummaries = emptyMap()) }
            val summaries = mutableMapOf<String, MediaSummary>()
            group.members.forEach { member ->
                repository.loadMediaSummary(member.media_id)?.let { summaries[member.media_id] = it }
            }
            _ui.update { it.copy(compareLoading = false, compareSummaries = summaries) }
        }
    }

    fun closeCompare() {
        _ui.update { it.copy(compareGroup = null, compareSummaries = emptyMap(), compareLoading = false) }
    }

    /** 记录某成员的"保留"选择(成功后才更新本地状态)。 */
    fun setKeep(groupId: String, mediaId: String, keep: Boolean) {
        viewModelScope.launch {
            if (!repository.setDuplicateKeep(groupId, mediaId, keep)) return@launch
            _ui.update { state ->
                state.copy(
                    compareGroup = state.compareGroup?.withKeep(mediaId, keep),
                    exact = state.exact.map { if (it.group_id == groupId) it.withKeep(mediaId, keep) else it },
                    similar = state.similar.map { if (it.group_id == groupId) it.withKeep(mediaId, keep) else it },
                )
            }
        }
    }

    // ---- 内部实现 ----

    /** 刷新扫描任务状态(未开始扫描时为 null)。 */
    private suspend fun refreshScanStatus() {
        val status = repository.duplicateScanStatus()
        if (status?.task_id == null) {
            _ui.update { it.copy(scanTaskId = null, scanStatus = null, scanProgress = 0, scanError = null, scanPolling = false) }
        } else {
            _ui.update {
                it.copy(
                    scanTaskId = status.task_id,
                    scanStatus = status.status,
                    scanProgress = status.progress ?: it.scanProgress,
                    scanError = status.error,
                )
            }
        }
    }

    /** 轮询扫描进度;到达终态或任务被替换后停止。 */
    private suspend fun pollScan(taskId: String) {
        _ui.update { it.copy(scanPolling = true) }
        var consecutiveMisses = 0
        while (true) {
            delay(pollIntervalMs)
            val status = repository.duplicateScanStatus()
            if (status?.task_id == null) {
                consecutiveMisses += 1
                if (consecutiveMisses >= MAX_SCAN_POLL_MISSES) {
                    _ui.update { it.copy(scanPolling = false, scanError = "无法获取扫描进度,请稍后刷新") }
                    return
                }
                continue
            }
            consecutiveMisses = 0
            if (status.task_id != taskId) {
                // 任务已不存在或已被替换:以最新状态为准,终止轮询
                _ui.update { it.copy(scanPolling = false) }
                loadGroups()
                return
            }
            val nextStatus = status.status ?: ""
            _ui.update {
                it.copy(
                    scanStatus = nextStatus,
                    scanProgress = status.progress ?: it.scanProgress,
                    scanError = status.error,
                )
            }
            if (nextStatus == "succeeded") {
                _ui.update { it.copy(scanPolling = false) }
                loadGroups()
                return
            }
            if (nextStatus in SCAN_TERMINAL_STATUSES) {
                _ui.update { it.copy(scanPolling = false) }
                return
            }
        }
    }

    private suspend fun loadGroups() {
        val exact = repository.loadDuplicatesExact()
        val similar = repository.loadDuplicatesSimilar()
        _ui.update { it.copy(loading = false, exact = exact, similar = similar) }
    }

    private fun findGroup(groupId: String): DuplicateGroupDto? =
        _ui.value.exact.firstOrNull { it.group_id == groupId }
            ?: _ui.value.similar.firstOrNull { it.group_id == groupId }

    private fun DuplicateGroupDto.withKeep(mediaId: String, keep: Boolean): DuplicateGroupDto =
        copy(members = members.map { if (it.media_id == mediaId) it.copy(keep = keep) else it })

    private companion object {
        const val DEFAULT_SCAN_POLL_INTERVAL_MS = 1500L
        const val MAX_SCAN_POLL_MISSES = 20
    }
}
