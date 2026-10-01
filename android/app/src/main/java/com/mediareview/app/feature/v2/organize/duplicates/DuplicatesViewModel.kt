package com.mediareview.app.feature.v2.organize.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupDetail
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupType
import com.mediareview.app.feature.v2.organize.data.DuplicateScanState
import com.mediareview.app.feature.v2.organize.data.OrganizeFeatureUnavailableInDemoException
import com.mediareview.app.feature.v2.organize.data.V2OrganizeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 重复媒体中心状态（Stage 8C §27~§30）。
 *
 * 完全重复 / 疑似重复分开维护；扫描状态来自服务端任务接口，只在 pending/running 轮询。
 */
data class DuplicatesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Demo 模式没有重复媒体能力：整页显示「仅服务器模式可用」。 */
    val demoUnavailable: Boolean = false,
    val exact: List<DuplicateGroupSummary> = emptyList(),
    val similar: List<DuplicateGroupSummary> = emptyList(),
    val scan: DuplicateScanState = DuplicateScanState(null, null, 0, null),
    /** 控制类请求（扫描/暂停/继续/取消）进行中：按钮防重。 */
    val scanBusy: Boolean = false,
)

/**
 * 重复媒体中心 ViewModel。
 *
 * 关键约束：
 * - **绝不自动删除重复文件**（§31）：本页只发现 / 对比 / 记录保留选择；
 * - 扫描复用 `POST /duplicates/scan` + `GET /duplicates/status` + pause/resume/cancel；
 * - 进入页面先 GET status 恢复（§29），仅在 pending/running 轮询（1.5s），
 *   离开/切后台停止；`succeeded` 后自动重载 exact / similar（§30）；
 * - Demo 模式显式「仅服务器模式可用」，不假装 0 组（§43/§45）。
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val repository: V2OrganizeRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(DuplicatesUiState())
    val ui: StateFlow<DuplicatesUiState> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var pollJob: Job? = null
    private var screenActive = false

    /** 进入页面：先 GET status 恢复 + 加载分组；active 时恢复轮询（§29）。 */
    fun enterScreen() {
        screenActive = true
        loadAll()
    }

    /** 离开页面 / 切后台：停止轮询（§29）。 */
    fun leaveScreen() {
        screenActive = false
        stopPolling()
    }

    fun refresh() = loadAll()

    // ---------- 扫描控制 ----------

    /** 触发扫描（服务端幂等）；成功后进入轮询。 */
    fun startScan() {
        // 同步置忙（StateFlow update 立即生效）：同一帧内的重复点击只会发出一次请求
        if (_ui.value.scanBusy) return
        _ui.update { it.copy(scanBusy = true) }
        viewModelScope.launch {
            try {
                val state = repository.startDuplicateScan()
                _ui.update { it.copy(scan = state) }
                if (state.isActive) startPolling() else loadGroups()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _messages.tryEmit("无法发起重复扫描，请重试")
            } finally {
                _ui.update { it.copy(scanBusy = false) }
            }
        }
    }

    fun pauseScan() = taskAction { repository.pauseDuplicateScan(it) }

    fun resumeScan() = taskAction(resumePolling = true) { repository.resumeDuplicateScan(it) }

    fun cancelScan() = taskAction(reloadGroups = true) { repository.cancelDuplicateScan(it) }

    /** 暂停/继续/取消统一入口：防重 + 终态停止轮询 + 失败提示。 */
    private fun taskAction(
        resumePolling: Boolean = false,
        reloadGroups: Boolean = false,
        block: suspend (String) -> DuplicateScanState,
    ) {
        val taskId = _ui.value.scan.taskId ?: return
        // 同步置忙（StateFlow update 立即生效）：防止连续点击发出多个任务控制请求
        if (_ui.value.scanBusy) return
        _ui.update { it.copy(scanBusy = true) }
        viewModelScope.launch {
            try {
                val state = block(taskId)
                _ui.update { it.copy(scan = state) }
                when {
                    resumePolling && state.isActive -> startPolling()
                    else -> stopPolling()
                }
                if (reloadGroups || state.isSucceeded) loadGroups()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _messages.tryEmit("操作失败，请重试")
            } finally {
                _ui.update { it.copy(scanBusy = false) }
            }
        }
    }

    // ---------- 加载 ----------

    private fun loadAll() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = it.exact.isEmpty() && it.similar.isEmpty(), error = null) }
            try {
                val status = repository.duplicateScanStatus()
                _ui.update { it.copy(scan = status, demoUnavailable = false) }
                if (status.isActive && screenActive) startPolling() else stopPolling()
                val exact = repository.loadDuplicateGroups(DuplicateGroupType.EXACT)
                val similar = repository.loadDuplicateGroups(DuplicateGroupType.SIMILAR)
                _ui.update {
                    it.copy(loading = false, error = null, exact = exact, similar = similar)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OrganizeFeatureUnavailableInDemoException) {
                // Demo：明确不可用，而不是显示 0 组
                _ui.update { it.copy(loading = false, demoUnavailable = true, error = null) }
            } catch (error: Throwable) {
                _ui.update { it.copy(loading = false, error = error.message ?: "加载失败") }
            }
        }
    }

    private fun loadGroups() {
        viewModelScope.launch {
            try {
                val exact = repository.loadDuplicateGroups(DuplicateGroupType.EXACT)
                val similar = repository.loadDuplicateGroups(DuplicateGroupType.SIMILAR)
                _ui.update { it.copy(loading = false, error = null, exact = exact, similar = similar) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 静默失败：保留旧分组，用户可下拉/重试刷新
            }
        }
    }

    // ---------- 轮询（仅 pending/running；离开页面即停） ----------

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (screenActive) {
                delay(DuplicateScanUi.POLL_INTERVAL_MS)
                val state = try {
                    repository.duplicateScanStatus()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // 单次网络失败不当作"扫描结束"：保留上次状态，下个周期重试
                    continue
                }
                _ui.update { it.copy(scan = state) }
                if (!state.isActive) {
                    if (state.isSucceeded) loadGroups() // §30：成功后自动重载 exact/similar
                    break
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}

/** 对比页状态（Stage 8C §34）。 */
data class DuplicateCompareUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: DuplicateGroupDetail? = null,
    val keepBusy: Boolean = false,
)

/**
 * 重复对比 ViewModel：详情一次请求（客户端零 N+1），"保留"选择服务端确认后才更新 UI。
 *
 * keep 只是人工整理选择，**绝不会**自动把其它成员加入待删除（§36）。
 */
@HiltViewModel
class DuplicateCompareViewModel @Inject constructor(
    private val repository: V2OrganizeRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(DuplicateCompareUiState())
    val ui: StateFlow<DuplicateCompareUiState> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun load(groupId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val detail = repository.loadDuplicateDetail(groupId)
                _ui.update { it.copy(loading = false, detail = detail, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _ui.update { it.copy(loading = false, error = error.message ?: "加载失败") }
            }
        }
    }

    /** 保留 / 取消保留：Server 成功后更新 UI，失败不改变并提示（§35）。 */
    fun setKeep(groupId: String, mediaId: String, keep: Boolean) {
        if (_ui.value.keepBusy) return
        viewModelScope.launch {
            _ui.update { it.copy(keepBusy = true) }
            try {
                repository.setDuplicateKeep(groupId, mediaId, keep)
                _ui.update { state ->
                    state.copy(
                        keepBusy = false,
                        detail = state.detail?.let { detail ->
                            detail.copy(
                                members = detail.members.map { member ->
                                    if (member.media.id == mediaId) member.copy(keep = keep) else member
                                },
                            )
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _ui.update { it.copy(keepBusy = false) }
                _messages.tryEmit("保留操作失败，请重试")
            }
        }
    }
}