package com.mediareview.app.feature.mediawall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mediareview.app.core.datastore.MediaWallSettings
import com.mediareview.app.core.datastore.MediaWallSettingsDataSource
import com.mediareview.app.core.datastore.MediaWallSettingsStore
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaFolderItem
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.MediaSyncDto
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.core.ui.RevisionLoadGate
import com.mediareview.app.feature.home.data.MediaDataSource
import com.mediareview.app.feature.home.data.MediaRepository
import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
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
    /** 当前文件夹筛选(null = 全部);ID 是服务器派生 ID,非文件路径。 */
    val folderId: String? = null,
    val folders: List<MediaFolderItem> = emptyList(),
    val syncState: String = MediaSyncDto().state,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaWallViewModel private constructor(
    private val repository: MediaDataSource,
    private val settingsStore: MediaWallSettingsDataSource,
    private val invalidations: ContentInvalidationStore,
) : ViewModel() {
    @Inject
    constructor(
        repository: MediaRepository,
        settingsStore: MediaWallSettingsStore,
        invalidations: ContentInvalidationStore,
    ) : this(
        repository as MediaDataSource,
        settingsStore as MediaWallSettingsDataSource,
        invalidations,
    )

    internal constructor(
        repository: MediaDataSource,
        settingsStore: MediaWallSettingsDataSource,
        invalidations: ContentInvalidationStore,
        testSeam: Unit = Unit,
    ) : this(repository, settingsStore, invalidations)

    private val _ui = MutableStateFlow(MediaWallUiState())
    val ui: StateFlow<MediaWallUiState> = _ui.asStateFlow()

    /** 当前查询;任何字段变化都会产生新实例并驱动新 Pager。 */
    private val _query = MutableStateFlow(MediaQuery())

    /** 查询快照(测试与调试观察用;分页流以此为单位重建)。 */
    val query: StateFlow<MediaQuery> = _query.asStateFlow()

    /** 刷新代数:revision 失效或主壳激活时的 claim 都会 +1,重建分页流。 */
    private val _refresh = MutableStateFlow(0)

    private val _search = MutableStateFlow("")
    private val loadGate = RevisionLoadGate().apply {
        claim(invalidations.revision(ContentArea.Media))
    }

    /** 分页流:新查询/新刷新代数 → 新 Pager;旧流被 flatMapLatest 取消。
     *  cachedIn(viewModelScope) 是唯一保留的页缓存。 */
    val pagingData: Flow<PagingData<MediaSummary>> =
        combine(_query, _refresh) { query, _ -> query }
            .flatMapLatest { query ->
                Pager(
                    config = PagingConfig(
                        pageSize = MediaPagingSource.DEFAULT_PAGE_SIZE,
                        initialLoadSize = MediaPagingSource.DEFAULT_PAGE_SIZE,
                        enablePlaceholders = false,
                        prefetchDistance = MediaPagingSource.DEFAULT_PAGE_SIZE / 2,
                    ),
                    initialKey = 1,
                ) {
                    MediaPagingSource(
                        repository,
                        query,
                        pageSize = MediaPagingSource.DEFAULT_PAGE_SIZE,
                        onSyncLoaded = { sync ->
                            _ui.update { it.copy(syncState = sync.state) }
                        },
                    )
                }.flow
            }
            .cachedIn(viewModelScope)

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
            _query.update {
                it.copy(type = saved.type, sortBy = saved.sortBy, sortOrder = saved.sortOrder)
            }
            loadLibraries()
            loadFolders()
            _refresh.value += 1
        }
        viewModelScope.launch {
            _search
                .drop(1)
                .debounce(400)
                .distinctUntilChanged()
                .collect { q ->
                    _ui.update { st -> st.copy(search = q) }
                    _query.update { it.copy(search = q.ifBlank { null }) }
                }
        }
    }

    /** 主壳每次激活媒体根时调用；同一 revision 不重复加载。 */
    fun loadIfNeeded() {
        if (!loadGate.claim(invalidations.revision(ContentArea.Media))) return
        viewModelScope.launch {
            loadLibraries()
            loadFolders()
            _refresh.value += 1
        }
    }

    private suspend fun loadLibraries() {
        runCatching { repository.loadLibraries() }
            .onSuccess { libs -> _ui.update { it.copy(libraries = libs) } }
    }

    /** 文件夹辅助视图与当前筛选保持一致;失败时静默保留旧列表(非关键路径)。 */
    private suspend fun loadFolders() {
        val st = _ui.value
        runCatching {
            repository.loadMediaFolders(
                libraryId = st.libraryId,
                type = st.type,
                search = st.search.ifBlank { null },
                excludeFavorites = st.excludeFavorites,
            )
        }.onSuccess { folders -> _ui.update { it.copy(folders = folders) } }
    }

    private fun setLibraryId(id: String?) {
        _ui.update { it.copy(libraryId = id) }
        _query.update { it.copy(libraryId = id) }
        viewModelScope.launch { loadFolders() }
    }

    fun onLibrarySelected(id: String) {
        setLibraryId(id)
    }

    fun onLibraryAll() {
        setLibraryId(null)
    }

    fun setType(type: MediaTypeFilter) {
        _ui.update { it.copy(type = type) }
        _query.update { it.copy(type = type) }
        persistSettings()
        viewModelScope.launch { loadFolders() }
    }

    fun setSort(field: SortField) {
        val st = _ui.value
        val newOrder = if (st.sortBy == field) {
            if (st.sortOrder == SortOrder.Asc) SortOrder.Desc else SortOrder.Asc
        } else {
            SortOrder.Asc
        }
        _ui.update { it.copy(sortBy = field, sortOrder = newOrder) }
        _query.update { it.copy(sortBy = field, sortOrder = newOrder) }
        persistSettings()
    }

    /** 切换未点赞筛选(只显示尚未点赞的媒体)。 */
    fun setExcludeFavorites(exclude: Boolean) {
        _ui.update { it.copy(excludeFavorites = exclude) }
        _query.update { it.copy(excludeFavorites = exclude) }
        viewModelScope.launch { loadFolders() }
    }

    /** 文件夹辅助筛选:在媒体墙内部切换,null 表示全部文件夹。 */
    fun setFolder(folderId: String?) {
        _ui.update { it.copy(folderId = folderId) }
        _query.update { it.copy(folderId = folderId) }
    }

    fun setGridColumns(columns: Int) {
        val c = columns.coerceIn(2, 5)
        _ui.update { it.copy(gridColumns = c) }
        viewModelScope.launch { settingsStore.save(currentSettings()) }
    }

    fun onSearchChange(value: String) {
        _search.value = value
    }

    private fun persistSettings() {
        viewModelScope.launch { settingsStore.save(currentSettings()) }
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
}

sealed interface MediaWallEvent
