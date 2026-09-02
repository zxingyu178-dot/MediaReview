package com.mediareview.app.feature.deletequeue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.core.model.DeleteQueueItemDto
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.core.ui.ContentMutation
import com.mediareview.app.core.ui.RevisionLoadGate
import com.mediareview.app.feature.home.data.MediaDataSource
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeleteQueueUiState(
    val loading: Boolean = true,
    val items: List<DeleteQueueItemDto> = emptyList(),
    val totalBytes: Long = 0,
    val error: String? = null,
    /** 最终删除结果中文汇总(按服务端 success/missing/failed 合同统计)。 */
    val commitResult: String? = null,
) {
    val pendingCount: Int get() = items.count { it.status == "pending" }
}

/** 待删除页:列表 + 预计释放空间 + 单项恢复 + 最终删除确认。 */
@HiltViewModel
class DeleteQueueViewModel private constructor(
    private val repository: MediaDataSource,
    private val invalidations: ContentInvalidationStore,
) : ViewModel() {
    @Inject
    constructor(repository: MediaRepository, invalidations: ContentInvalidationStore) :
        this(repository as MediaDataSource, invalidations)

    internal constructor(
        repository: MediaDataSource,
        invalidations: ContentInvalidationStore,
        testSeam: Unit = Unit,
    ) : this(repository, invalidations)
    private val loadGate = RevisionLoadGate()

    private val _ui = MutableStateFlow(DeleteQueueUiState())
    val ui: StateFlow<DeleteQueueUiState> = _ui.asStateFlow()

    fun loadIfNeeded() {
        if (loadGate.claim(invalidations.revision(ContentArea.DeleteQueue))) load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val items = repository.listDeleteQueue()
            // 用 update 而非整体替换:保留尚未清除的 commitResult 摘要
            _ui.update {
                it.copy(
                    loading = false,
                    items = items,
                    totalBytes = items.sumOf { item -> item.size_bytes ?: 0 },
                )
            }
        }
    }

    /** 单项恢复。 */
    fun restore(mediaId: String) {
        viewModelScope.launch {
            if (repository.dequeueDelete(mediaId)) {
                invalidations.invalidate(ContentMutation.DeleteQueue)
            }
            load()
        }
    }

    /** 最终确认删除(两阶段:先 prepare 取一次性 nonce,再 commit 真实删除)。 */
    fun commit() {
        viewModelScope.launch {
            val prep = repository.prepareDeleteCommit()
            val nonce = prep?.nonce
            if (nonce.isNullOrBlank()) {
                _ui.update { it.copy(commitResult = "删除失败:无法发起删除确认,请重试") }
                load()
                return@launch
            }
            val result = repository.commitDeleteQueue(nonce)
            val parsed = result?.outcome?.mapValues { DeleteOutcomeStatus.fromWire(it.value) } ?: emptyMap()
            _ui.update { it.copy(commitResult = summarize(parsed)) }
            // 任何实际变更(成功删除或文件已缺失)都要推进下游内容;全失败不推进
            if (parsed.values.any { it.changed }) {
                invalidations.invalidate(ContentMutation.FinalDelete)
            }
            load()
        }
    }

    /** 按服务端合同汇总:success 计成功,missing 单独列示,failed/未知计失败。 */
    private fun summarize(parsed: Map<String, DeleteOutcomeStatus>): String {
        if (parsed.isEmpty()) return "删除完成"
        val ok = parsed.values.count { it.successful }
        val missing = parsed.values.count { it == DeleteOutcomeStatus.Missing }
        val failed = parsed.values.count { !it.successful && it != DeleteOutcomeStatus.Missing }
        return buildString {
            append("删除成功 ").append(ok).append(" 项")
            if (missing > 0) append(",缺失 ").append(missing).append(" 项")
            if (failed > 0) append(",失败 ").append(failed).append(" 项")
        }
    }

    fun clearCommitResult() = _ui.update { it.copy(commitResult = null) }
}
