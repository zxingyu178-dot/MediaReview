package com.mediareview.app.feature.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.core.ui.RevisionLoadGate
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
)

/** 重复文件页:完全重复 + 疑似重复(只读展示,绝不自动删除)。 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val invalidations: ContentInvalidationStore,
) : ViewModel() {
    private val loadGate = RevisionLoadGate()

    private val _ui = MutableStateFlow(DuplicatesUiState())
    val ui: StateFlow<DuplicatesUiState> = _ui.asStateFlow()

    fun loadIfNeeded() {
        if (loadGate.claim(invalidations.revision(ContentArea.Duplicates))) load()
    }

    fun load(invalidateOnSuccess: Boolean = false) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val exact = repository.loadDuplicatesExact()
            val similar = repository.loadDuplicatesSimilar()
            _ui.value = DuplicatesUiState(
                loading = false,
                exact = exact,
                similar = similar,
            )
            if (invalidateOnSuccess) invalidations.invalidate(ContentArea.Duplicates)
        }
    }

    fun refresh() = load(invalidateOnSuccess = true)
}
