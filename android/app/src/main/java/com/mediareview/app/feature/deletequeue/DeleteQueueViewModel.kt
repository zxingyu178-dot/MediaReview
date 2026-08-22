package com.mediareview.app.feature.deletequeue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.DeleteQueueItemDto
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
    /** 最终删除结果(success/failed 计数)。 */
    val commitResult: String? = null,
) {
    val pendingCount: Int get() = items.count { it.status == "pending" }
}

/** 待删除页:列表 + 预计释放空间 + 单项恢复 + 最终删除确认。 */
@HiltViewModel
class DeleteQueueViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(DeleteQueueUiState())
    val ui: StateFlow<DeleteQueueUiState> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val items = repository.listDeleteQueue()
            _ui.value = DeleteQueueUiState(
                loading = false,
                items = items,
                totalBytes = items.sumOf { it.size_bytes ?: 0 },
            )
        }
    }

    /** 单项恢复。 */
    fun restore(mediaId: String) {
        viewModelScope.launch {
            repository.dequeueDelete(mediaId)
            load()
        }
    }

    /** 最终确认删除(两阶段最后一步)。 */
    fun commit() {
        viewModelScope.launch {
            val result = repository.commitDeleteQueue()
            val summary = result?.outcome?.let { outcome ->
                val ok = outcome.count { it.value == "deleted" }
                val failed = outcome.count { it.value != "deleted" }
                "删除成功 $ok 项,失败 $failed 项"
            } ?: "删除完成"
            _ui.update { it.copy(commitResult = summary) }
            load()
        }
    }

    fun clearCommitResult() = _ui.update { it.copy(commitResult = null) }
}
