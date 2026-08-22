package com.mediareview.app.feature.mediawall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.datastore.MediaWallSettings
import com.mediareview.app.core.datastore.MediaWallSettingsStore
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.feature.home.data.MediaRepository
import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MediaWallUiState(
    val libraryId: String? = null,
    val libraries: List<LibraryItem> = emptyList(),
    val type: MediaTypeFilter = MediaTypeFilter.All,
    val sortBy: SortField = SortField.Name,
    val sortOrder: SortOrder = SortOrder.Asc,
    val search: String = "",
    /** 未点赞筛选:true 时只显示尚未点赞的媒体。 */
    val excludeFavorites: Boolean = false,
    val gridColumns: Int = 3,
    val items: List<MediaSummary> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 50,
    val loading: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class MediaWallViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val settingsStore: MediaWallSettingsStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(MediaWallUiState())
    val ui: StateFlow<MediaWallUiState> = _ui.asStateFlow()

    private val _search = MutableStateFlow("")

    /** 请求代数:每次查询条件变化 +1;用于取消过期请求,防旧结果覆盖新结果。 */
    private var generation = 0
    private var loadJob: Job? = null
    private var initialized = false

    init {
        viewModelScope.launch {
            val saved = settingsStore.current()
            _ui.update {
                it.copy(
                    gridColumns = saved.gridColumns,
                    sortBy = saved.sortBy,
                    sortOrder = saved.sortOrder,
                    type = saved.type,
                )
            }
            loadLibraries()
            loadPage(1, append = false)
        }
        viewModelScope.launch {
            _search
                .debounce(400)
                .distinctUntilChanged()
                .collect { q ->
                    _ui.update { st -> st.copy(search = q) }
                    reload(1)
                }
        }
    }

    private suspend fun loadLibraries() {
        runCatching { repository.loadLibraries() }
            .onSuccess { libs ->
                _ui.update { it.copy(libraries = libs) }
            }
    }

    /** 取消进行中的请求,返回新代数。 */
    private fun nextGeneration(): Int {
        generation += 1
        loadJob?.cancel()
        return generation
    }

    private fun setLibraryId(id: String?) {
        _ui.update { it.copy(libraryId = id) }
        reload(1)
    }

    fun onLibrarySelected(id: String) {
        setLibraryId(id)
    }

    fun onLibraryAll() {
        setLibraryId(null)
    }

    fun setType(type: MediaTypeFilter) {
        _ui.update { it.copy(type = type) }
        persistAndReload()
    }

    fun setSort(field: SortField) {
        val st = _ui.value
        val newOrder = if (st.sortBy == field) {
            if (st.sortOrder == SortOrder.Asc) SortOrder.Desc else SortOrder.Asc
        } else {
            SortOrder.Asc
        }
        _ui.update { it.copy(sortBy = field, sortOrder = newOrder) }
        persistAndReload()
    }

    /** 切换未点赞筛选(只显示尚未点赞的媒体)。 */
    fun setExcludeFavorites(exclude: Boolean) {
        _ui.update { it.copy(excludeFavorites = exclude) }
        reload(1)
    }

    fun setGridColumns(columns: Int) {
        val c = columns.coerceIn(2, 5)
        _ui.update { it.copy(gridColumns = c) }
        viewModelScope.launch { settingsStore.save(currentSettings()) }
    }

    fun onSearchChange(value: String) {
        _search.value = value
    }

    /** 接近底部时调用;防 loading/重复。 */
    fun onScrollNearEnd() {
        val st = _ui.value
        if (st.loading || initialized.not()) return
        if (st.items.size >= st.total) return
        loadMoreInternal()
    }

    fun onRetry() {
        reload(1)
    }

    fun loadMore() = loadMoreInternal()

    private fun loadMoreInternal() {
        val st = _ui.value
        if (st.loading || st.items.size >= st.total) return
        loadPage(st.page + 1, append = true)
    }

    private fun persistAndReload() {
        viewModelScope.launch { settingsStore.save(currentSettings()) }
        reload(1)
    }

    private fun currentSettings(): MediaWallSettings {
        val st = _ui.value
        return MediaWallSettings(
            gridColumns = st.gridColumns,
            sortBy = st.sortBy,
            sortOrder = st.sortOrder,
            type = st.type,
        )
    }

    private fun reload(page: Int) {
        _ui.update { it.copy(page = page, items = emptyList(), error = null) }
        loadPage(page, append = false)
    }

    private fun loadPage(page: Int, append: Boolean) {
        val gen = nextGeneration()
        val st = _ui.value
        _ui.update { it.copy(loading = true, error = null, page = page) }
        loadJob = viewModelScope.launch {
            runCatching {
                repository.loadMedia(
                    libraryId = st.libraryId,
                    type = st.type,
                    sortBy = st.sortBy,
                    sortOrder = st.sortOrder,
                    page = page,
                    pageSize = st.pageSize,
                    search = st.search.ifBlank { null },
                    excludeFavorites = st.excludeFavorites,
                )
            }
                .onSuccess { data ->
                    if (gen != generation) return@onSuccess // 过期响应丢弃
                    _ui.update {
                        it.copy(
                            loading = false,
                            items = if (append) it.items + data.items else data.items,
                            total = data.total,
                        )
                    }
                }
                .onFailure { e ->
                    if (gen != generation) return@onFailure
                    _ui.update { it.copy(loading = false, error = e.message) }
                }
        }.also { loadJob = it }
        initialized = true
    }
}

sealed interface MediaWallEvent