package com.mediareview.app.feature.v2.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.model.V2ContextQueue
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SortSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 首页/书架模式切换。 */
enum class V2HomeTab { MEDIA, SHELF }

/**
 * V2 首页共享 ViewModel（Activity 作用域）：
 * 持有文件夹、排序/过滤、搜索、当前列表与页面上下文队列。
 */
@HiltViewModel
class V2HomeViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val searchHistory: SearchHistoryStore,
) : ViewModel() {

    private val _folders = MutableStateFlow<List<V2Folder>>(emptyList())
    val folders: StateFlow<List<V2Folder>> = _folders.asStateFlow()

    private val _sortSpec = MutableStateFlow(V2SortSpec())
    val sortSpec: StateFlow<V2SortSpec> = _sortSpec.asStateFlow()

    /** null = 全部。 */
    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    /** 顶部"最近"胶囊选中态。 */
    var selectedRecent by mutableStateOf(false)
        private set

    private val _currentList = MutableStateFlow<List<V2Media>>(emptyList())
    val currentList: StateFlow<List<V2Media>> = _currentList.asStateFlow()

    var currentTab by mutableStateOf(V2HomeTab.MEDIA)
        private set

    var searchActive by mutableStateOf(false)
        private set

    var searchQuery by mutableStateOf("")
        private set

    var recentSearches by mutableStateOf(listOf<String>())
        private set

    /** 进入播放器/查看器时的上下文队列。 */
    var contextQueue by mutableStateOf<V2ContextQueue?>(null)
        private set

    private val _folderCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderCounts: StateFlow<Map<String, Int>> = _folderCounts.asStateFlow()

    private val _favorites = MutableStateFlow<List<V2Media>>(emptyList())
    val favorites: StateFlow<List<V2Media>> = _favorites.asStateFlow()

    init {
        viewModelScope.launch {
            _folders.value = repository.folders()
            refreshList()
        }
        // 搜索历史从 DataStore 恢复（App 重启后仍在）
        viewModelScope.launch {
            recentSearches = searchHistory.current()
        }
    }

    fun folderCount(folderId: String): Int = _folderCounts.value[folderId] ?: 0

    fun setTab(tab: V2HomeTab) {
        currentTab = tab
    }

    fun setSortSpec(spec: V2SortSpec) {
        _sortSpec.value = spec
        refreshList()
    }

    fun selectFolder(folderId: String?) {
        selectedRecent = false
        _selectedFolderId.value = folderId
        refreshList()
    }

    /** 顶部"最近"胶囊：全部媒体按最近添加倒序。 */
    fun selectRecent() {
        selectedRecent = true
        _selectedFolderId.value = null
        _sortSpec.value = _sortSpec.value.copy(field = com.mediareview.app.feature.v2.model.V2SortField.RECENT, order = com.mediareview.app.feature.v2.model.V2SortOrder.DESC)
        refreshList()
    }

    /** 顶部"全部"胶囊。 */
    fun selectAll() {
        selectedRecent = false
        _selectedFolderId.value = null
        refreshList()
    }

    fun setSearchMode(active: Boolean) {
        searchActive = active
        if (!active) {
            searchQuery = ""
            refreshList()
        }
    }

    fun updateSearchQuery(q: String) {
        searchQuery = q
        refreshList()
    }

    fun commitSearch() {
        val q = searchQuery.trim()
        if (q.isNotEmpty()) {
            recentSearches = (listOf(q) + recentSearches).distinct().take(10)
            viewModelScope.launch {
                searchHistory.add(q)
            }
        }
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        viewModelScope.launch {
            searchHistory.clear()
        }
    }

    fun setFavorite(mediaId: String, favorite: Boolean) {
        viewModelScope.launch {
            repository.setFavorite(mediaId, favorite)
            refreshList()
        }
    }

    fun markReviewed(mediaId: String) {
        viewModelScope.launch {
            repository.markReviewed(mediaId)
            refreshList()
        }
    }

    /** 打开媒体：记录当前队列与索引，供播放器/查看器携带上下文。 */
    fun openMedia(mediaId: String) {
        val list = _currentList.value
        val index = list.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
        contextQueue = V2ContextQueue(
            folderId = _selectedFolderId.value,
            sortField = _sortSpec.value.field,
            sortOrder = _sortSpec.value.order,
            typeFilter = _sortSpec.value.typeFilter,
            mediaIds = list.map { it.id },
            currentIndex = index,
        )
    }

    /** 由播放器/查看器更新队列（如左右翻页后同步索引）。 */
    fun updateQueueIndex(index: Int) {
        val q = contextQueue ?: return
        contextQueue = q.copy(currentIndex = index.coerceIn(0, q.mediaIds.size - 1))
    }

    // ---------- URI 能力（委托数据层，UI 不感知数据来源） ----------

    fun thumbUri(media: V2Media): String = repository.thumbUri(media)

    /** 统一封面 URI（视频→Poster、图片→缩略图；UI 全用这个，不感知数据来源）。 */
    fun coverUri(media: V2Media): String = repository.coverUri(media)

    /** 图片原图 URI（薄委托，加载逻辑在数据层）。 */
    fun imageUri(media: V2Media): String = repository.imageUri(media)

    fun spriteUri(media: V2Media): String? = repository.spriteUri(media)

    fun spriteManifest(media: V2Media): com.mediareview.app.feature.v2.model.V2SpriteManifest? =
        repository.spriteManifest(media)

    fun playbackUri(mediaId: String): String = repository.playbackUri(mediaId)

    fun mediaById(id: String): V2Media? = repository.mediaById(id)

    private fun refreshList() {
        viewModelScope.launch {
            val spec = _sortSpec.value
            val folder = _selectedFolderId.value
            val query = searchQuery.trim()
            val list = when {
                searchActive && query.isNotEmpty() -> repository.search(query, spec)
                folder != null -> repository.mediaInFolder(folder, spec)
                else -> repository.media(spec)
            }
            _currentList.value = list
            // 更新各文件夹计数（供书架显示）
            val all = repository.media()
            _folderCounts.value = all.groupingBy { it.folderId }.eachCount()
            _favorites.value = all.filter { it.isFavorite }
        }
    }
}
