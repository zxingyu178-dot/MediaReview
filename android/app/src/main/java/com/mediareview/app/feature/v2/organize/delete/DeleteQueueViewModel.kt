package com.mediareview.app.feature.v2.organize.delete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.feature.v2.organize.data.DeleteCommitResponse
import com.mediareview.app.feature.v2.organize.data.DeleteAlreadyProcessedException
import com.mediareview.app.feature.v2.organize.data.DiskDeletionReceipt
import com.mediareview.app.feature.v2.organize.data.DeleteCommitPrepare
import com.mediareview.app.feature.v2.organize.data.DeleteQueueEntry
import com.mediareview.app.feature.v2.organize.data.V2OrganizeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 最终删除状态机（Stage 8C §19~§23）：
 *
 * ```
 * Idle → Preparing（POST commit/prepare）→ ReadyToConfirm（用 prepare 的数字弹确认）
 *      → Committing（同一 nonce 只提交一次）→ Result（逐类 success/missing/failed）
 * ```
 *
 * - 确认弹窗的"12 个文件 / 1.8 GB"必须来自 prepare response，而不是列表旧快照；
 * - Committing 期间禁止再次提交、Back 不可取消；
 * - 同 nonce 只允许 commit 一次（single-flight）。
 */
sealed interface DeleteCommitPhase {
    data object Idle : DeleteCommitPhase

    data object Preparing : DeleteCommitPhase

    data class ReadyToConfirm(val prepare: DeleteCommitPrepare) : DeleteCommitPhase

    /** 提交中：携带同一份 prepare（弹窗继续显示同一组数字），按钮 disabled、不可取消。 */
    data class Committing(val prepare: DeleteCommitPrepare) : DeleteCommitPhase

    data class Result(val result: DeleteCommitResult) : DeleteCommitPhase
}

/** 最终删除结果（逐类显示；失败项仍留在待删除队列）。 */
data class DeleteCommitResult(
    val successCount: Int,
    val missingCount: Int,
    val failedCount: Int,
    val failedNames: List<String>,
    val pendingCount: Int = 0,
    /** success + missing：这些媒体在服务端已消失，需要上层同步刷新并丢弃缓存。 */
    val changedMediaIds: List<String>,
    val diskReceipt: DiskDeletionReceipt? = null,
    val demo: Boolean = false,
) {
    val total: Int get() = successCount + missingCount + failedCount + pendingCount
    val pendingMessage: String? get() =
        if (pendingCount > 0) "删除请求已提交，等待系统完成" else null
}

/** 一次性事件（Snackbar / 上层刷新）。 */
sealed interface DeleteQueueEvent {
    data class Info(val text: String) : DeleteQueueEvent

    /** 最终删除完成（存在实际变更）：通知上层刷新首页内容与缓存（§24）。 */
    data class FinalDeleteCompleted(val changedMediaIds: List<String>) : DeleteQueueEvent
}

/**
 * 服务端 failed 原因（guard 代码）→ 简短中文（Stage 8C.1 §23；Stage 9 追加本地源失败码）。
 *
 * Server 侧 error 形如 `file_size_changed` / `file_modified_changed` /
 * `library_not_allowed` / `stat_failed:[Errno ...]`，本地源（native_delete_v1）追加
 * `local_root_disabled` / `local_root_missing` / `path_rejected` /
 * `file_identity_changed` / `file_in_use` / `delete_access_denied` / `delete_not_verified`。
 *
 * 只做**前缀 → 固定中文**映射：绝不把原始 Python 异常/堆栈/服务端绝对路径展示给用户
 * （未知码统一回退"删除失败"）。
 */
internal fun deleteFailureReason(error: String?): String? = when {
    error.isNullOrBlank() -> null
    error.startsWith("file_size_changed") -> "文件已发生变化"
    error.startsWith("file_modified_changed") -> "文件已发生变化"
    error.startsWith("library_not_allowed") -> "文件已不在允许的媒体库，无法确认身份"
    error.startsWith("stat_failed") -> "文件访问失败"
    // Stage 9：本地源两阶段安全删除的失败码（只显示原因，不显示任何路径）
    error.startsWith("local_root_disabled") -> "本地媒体源已停用，无法删除"
    error.startsWith("local_root_missing") -> "本地媒体源目录不存在或不可访问"
    error.startsWith("path_rejected") -> "文件不在允许的媒体库内，已拒绝删除"
    error.startsWith("file_identity_changed") -> "文件身份已变化，请刷新队列后重新标记"
    error.startsWith("file_in_use") -> "文件正在被占用，请稍后重试"
    error.startsWith("delete_access_denied") -> "没有权限删除该文件"
    error.startsWith("delete_not_verified") -> "删除结果未通过校验，请刷新队列核对"
    else -> "删除失败"
}

data class DeleteQueueUiState(
    val loading: Boolean = true,
    val entries: List<DeleteQueueEntry> = emptyList(),
    val error: String? = null,
    val phase: DeleteCommitPhase = DeleteCommitPhase.Idle,
) {
    /** 可最终删除的条数（服务端只允许 pending 被删除）。 */
    val pendingCount: Int get() = entries.count { it.status == "pending" }

    val pendingBytes: Long get() = entries.filter { it.status == "pending" }.sumOf { it.sizeBytes }
    val pendingUnknownSizes: Int get() = entries.count { it.status == "pending" && !it.sizeKnown }
}

/**
 * 待删除中心 ViewModel（Stage 8C §15/§17/§19~§25）。
 *
 * 服务端确认制：恢复失败列表保持原样并提示；删除必须 prepare → 用户确认 → commit；
 * commit 完成后**停留在本页**刷新列表（失败项可见），不自动退出。
 */
@HiltViewModel
class DeleteQueueViewModel @Inject constructor(
    private val repository: V2OrganizeRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(DeleteQueueUiState())
    val ui: StateFlow<DeleteQueueUiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<DeleteQueueEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DeleteQueueEvent> = _events.asSharedFlow()

    /** 同一个 nonce 只允许 commit 一次（§21）；成功/失败后都不复用。 */
    private var committedNonce: String? = null

    /** 恢复单项的 in-flight 防重。 */
    private val restoringIds = mutableSetOf<String>()

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val entries = repository.loadDeleteQueue()
                _ui.update { it.copy(loading = false, entries = entries, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // 网络失败 ≠ 空队列：保留旧数据（若有），显示错误并提供重试
                _ui.update {
                    it.copy(loading = false, error = error.message ?: "加载失败")
                }
            }
        }
    }

    /** 撤销待删除：只有服务端成功才刷新列表（失败保持原样 + 提示，§17）。 */
    fun restore(mediaId: String) {
        if (!restoringIds.add(mediaId)) return
        viewModelScope.launch {
            try {
                repository.restoreDeleteItem(mediaId)
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _events.tryEmit(DeleteQueueEvent.Info("恢复失败，请重试"))
            } finally {
                restoringIds.remove(mediaId)
            }
        }
    }

    /** 第一步：准备删除确认（拿到服务端锁定的快照后**才**弹确认框）。 */
    fun requestFinalDelete() {
        val phase = _ui.value.phase
        if (phase is DeleteCommitPhase.Preparing || phase is DeleteCommitPhase.Committing) return
        viewModelScope.launch {
            _ui.update { it.copy(phase = DeleteCommitPhase.Preparing) }
            try {
                val prepare = repository.prepareDeleteCommit()
                if (prepare.nonce.isBlank() || prepare.count <= 0) {
                    _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
                    _events.tryEmit(DeleteQueueEvent.Info("没有可删除的内容"))
                } else {
                    _ui.update { it.copy(phase = DeleteCommitPhase.ReadyToConfirm(prepare)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
                _events.tryEmit(DeleteQueueEvent.Info("无法发起删除确认，请重试"))
            }
        }
    }

    /** 用户取消确认（Committing 期间不可取消）。 */
    fun cancelFinalDelete() {
        if (_ui.value.phase is DeleteCommitPhase.Committing) return
        _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
    }

    /** 第二步：用 prepare 的 nonce 提交（禁止重新 prepare；同一 nonce 只提交一次）。 */
    fun confirmFinalDelete() {
        val prepare = (_ui.value.phase as? DeleteCommitPhase.ReadyToConfirm)?.prepare ?: return
        if (prepare.nonce == committedNonce) return
        committedNonce = prepare.nonce
        viewModelScope.launch {
            _ui.update { it.copy(phase = DeleteCommitPhase.Committing(prepare)) }
            try {
                val response = repository.commitDelete(prepare.nonce)
                if (response.outcome.isEmpty()) {
                    _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
                    _events.tryEmit(DeleteQueueEvent.Info("没有需要删除的内容"))
                } else {
                    val result = summarize(response)
                    _ui.update { it.copy(phase = DeleteCommitPhase.Result(result)) }
                    if (result.changedMediaIds.isNotEmpty()) {
                        _events.tryEmit(DeleteQueueEvent.FinalDeleteCompleted(result.changedMediaIds))
                    }
                }
                // 失败项仍留在服务端队列：刷新后仍可见（不自动退出本页）
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DeleteAlreadyProcessedException) {
                _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
                _events.tryEmit(DeleteQueueEvent.Info("删除请求已处理，请刷新队列核对结果，勿重复提交"))
                load()
            } catch (_: Throwable) {
                _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
                _events.tryEmit(DeleteQueueEvent.Info("删除结果未确认，请刷新队列核对，勿重复提交"))
                load()
            }
        }
    }

    fun dismissResult() {
        _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
    }

    /** 按服务端合同汇总：pending 单列等待系统完成，不算成功或失败。 */
    private fun summarize(response: DeleteCommitResponse): DeleteCommitResult {
        val outcome = response.outcome
        val success = outcome.count { (_, status) -> status.successful }
        val missing = outcome.count { (_, status) -> status == DeleteOutcomeStatus.Missing }
        val pending = outcome.count { (_, status) -> status == DeleteOutcomeStatus.Pending }
        val failedIds = outcome.filter { (_, status) ->
            status == DeleteOutcomeStatus.Failed || status == DeleteOutcomeStatus.Unknown
        }.keys.toList()
        val changed = outcome.filter { (_, status) -> status.changed }.keys.toList()
        val nameById = _ui.value.entries.associate { it.mediaId to (it.media?.name ?: it.mediaId) }
        return DeleteCommitResult(
            successCount = success,
            missingCount = missing,
            failedCount = failedIds.size,
            failedNames = failedIds.map { nameById[it] ?: it },
            pendingCount = pending,
            changedMediaIds = changed,
            diskReceipt = response.diskReceipt,
            demo = response.demo,
        )
    }
}
