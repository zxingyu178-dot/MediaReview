package com.mediareview.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.LibraryItem
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

data class LibraryUiState(
    val libraries: List<LibraryItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class LibraryViewModel private constructor(
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
    private var persistedSelectedIds: Set<String> = emptySet()

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    init {
        loadIfNeeded()
    }

    fun loadIfNeeded() {
        if (loadGate.claim(invalidations.revision(ContentArea.Libraries))) refresh()
    }

    fun refresh() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.loadLibraries() }
                .onSuccess { list ->
                    persistedSelectedIds = list.filter(LibraryItem::selected)
                        .map(LibraryItem::jellyfin_id).toSet()
                    _ui.update {
                        it.copy(
                            loading = false,
                            libraries = list,
                            selectedIds = list.filter(LibraryItem::selected)
                                .map(LibraryItem::jellyfin_id).toSet(),
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message) }
                }
        }
    }

    fun toggle(libraryId: String) {
        _ui.update {
            val next = it.selectedIds.toMutableSet()
            if (!next.add(libraryId)) next.remove(libraryId)
            it.copy(selectedIds = next, saved = false)
        }
    }

    fun save() {
        if (_ui.value.selectedIds == persistedSelectedIds) {
            _ui.update { it.copy(saving = false, error = null, saved = true) }
            return
        }
        _ui.update { it.copy(saving = true, error = null, saved = false) }
        viewModelScope.launch {
            runCatching { repository.saveSelection(_ui.value.selectedIds.toList()) }
                .onSuccess { list ->
                    persistedSelectedIds = _ui.value.selectedIds
                    _ui.update { st -> st.copy(saving = false, saved = true) }
                    invalidations.invalidate(ContentMutation.LibrarySelection)
                }
                .onFailure { e ->
                    _ui.update { it.copy(saving = false, error = e.message) }
                }
        }
    }
}
