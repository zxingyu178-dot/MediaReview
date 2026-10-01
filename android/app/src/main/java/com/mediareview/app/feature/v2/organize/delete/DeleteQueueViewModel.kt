package com.mediareview.app.feature.v2.organize.delete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.DeleteOutcomeStatus
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
    /** success + missing：这些媒体在服务端已消失，需要上层同步刷新并丢弃缓存。 */
    val changedMediaIds: List<String>,
) {
    val total: Int get() = successCount + missingCount + failedCount
}

/** 一次性事件（Snackbar / 上层刷新）。 */
sealed interface DeleteQueueEvent {
    data class Info(val text: String) : DeleteQueueEvent

    /** 最终删除完成（存在实际变更）：通知上层刷新首页内容与缓存（§24）。 */
    data class FinalDeleteCompleted(val changedMediaIds: List<String>) : DeleteQueueEvent
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
                val outcome = repository.commitDelete(prepare.nonce)
                if (outcome.isEmpty()) {
                    _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
                    _events.tryEmit(DeleteQueueEvent.Info("没有需要删除的内容"))
                } else {
                    val result = summarize(outcome)
                    _ui.update { it.copy(phase = DeleteCommitPhase.Result(result)) }
                    if (result.changedMediaIds.isNotEmpty()) {
                        _events.tryEmit(DeleteQueueEvent.FinalDeleteCompleted(result.changedMediaIds))
                    }
                }
                // 失败项仍留在服务端队列：刷新后仍可见（不自动退出本页）
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
                _events.tryEmit(DeleteQueueEvent.Info("删除提交失败，请重试"))
                load()
            }
        }
    }

    fun dismissResult() {
        _ui.update { it.copy(phase = DeleteCommitPhase.Idle) }
    }

    /** 按服务端合同汇总：success 计成功；missing 单列；failed/未知计失败（失败项留队列）。 */
    private fun summarize(outcome: Map<String, DeleteOutcomeStatus>): DeleteCommitResult {
        val success = outcome.count { (_, status) -> status.successful }
        val missing = outcome.count { (_, status) -> status == DeleteOutcomeStatus.Missing }
        val failedIds = outcome.filter { (_, status) -> !status.changed }.keys.toList()
        val changed = outcome.filter { (_, status) -> status.changed }.keys.toList()
        val nameById = _ui.value.entries.associate { it.mediaId to (it.media?.name ?: it.mediaId) }
        return DeleteCommitResult(
            successCount = success,
            missingCount = missing,
            failedCount = failedIds.size,
            failedNames = failedIds.map { nameById[it] ?: it },
            changedMediaIds = changed,
        )
    }
}