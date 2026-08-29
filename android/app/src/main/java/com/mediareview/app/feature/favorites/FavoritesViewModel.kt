package com.mediareview.app.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.core.ui.RevisionLoadGate
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FavoritesUiState(
    val loading: Boolean = true,
    val items: List<FavoriteItemDto> = emptyList(),
    val error: String? = null,
)

/** 喜欢页:独立媒体墙,可取消喜欢,原文件不变化。 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val invalidations: ContentInvalidationStore,
) : ViewModel() {
    private val loadGate = RevisionLoadGate()

    private val _ui = MutableStateFlow(FavoritesUiState())
    val ui: StateFlow<FavoritesUiState> = _ui.asStateFlow()

    fun loadIfNeeded() {
        if (loadGate.claim(invalidations.revision(ContentArea.Favorites))) load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.value = FavoritesUiState(loading = true)
            val items = repository.listFavorites()
            _ui.value = FavoritesUiState(loading = false, items = items)
        }
    }

    /** 取消喜欢(API 成功后才移除列表项)。 */
    fun remove(mediaId: String) {
        viewModelScope.launch {
            if (repository.removeFavorite(mediaId)) {
                _ui.value = _ui.value.copy(items = _ui.value.items.filterNot { it.media_id == mediaId })
                invalidations.invalidate(ContentArea.Favorites)
            }
        }
    }
}
