package com.mediareview.app.feature.v2.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2ContextQueue
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2TypeFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val _albums = MutableStateFlow<List<V2Album>>(emptyList())
    val albums: StateFlow<List<V2Album>> = _albums.asStateFlow()

    // Stage6：列表刷新 Job 竞态控制。
    // 只有最后一次触发的 refreshList 生效（连点文件夹/排序/输入搜索词时，
    // 旧一次的结果不得覆盖新一次）；搜索输入走防抖路径。
    private var listRefreshJob: Job? = null

    /** 搜索防抖窗口（输入停顿该时长后才真正查询）。 */
    private val searchDebounceMs = 300L

    // ---------- 雪碧图预览单实例状态 ----------

    /** 当前播放雪碧图预览的媒体 id（null = 无预览）。 */
    var activePreviewMediaId by mutableStateOf<String?>(null)
        private set

    /** 请求播放某媒体雪碧图（同一时间仅 1 个 Preview）。 */
    fun startSpritePreview(mediaId: String) {
        activePreviewMediaId = mediaId
    }

    /** 停止当前雪碧图预览（滚动 / 切 Tab / 打开媒体 / 等）。 */
    fun stopSpritePreview() {
        activePreviewMediaId = null
    }

    init {
        viewModelScope.launch {
            _folders.value = repository.folders()
            _albums.value = repository.albums()
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
        // 输入防抖：停顿 300ms 后才真正查询，避免每个字符都触发一次全量过滤
        refreshList(debounceMs = searchDebounceMs)
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

    /**
     * 在指定媒体序列中打开某张图片（用于相册：Viewer 只在当前相册照片间翻页）。
     */
    fun openMediaIn(list: List<V2Media>, mediaId: String) {
        val index = list.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
        contextQueue = V2ContextQueue(
            folderId = _selectedFolderId.value,
            sortField = _sortSpec.value.field,
            sortOrder = _sortSpec.value.order,
            typeFilter = V2TypeFilter.IMAGE,
            mediaIds = list.map { it.id },
            currentIndex = index,
        )
    }

    /**
     * 在指定视频队列中打开（批阅页 → 完整播放器：Player 按此队列上下条，
     * 返回时依旧回到批阅原位置）。
     */
    fun openMediaInVideoQueue(queue: List<V2Media>, mediaId: String) {
        val videos = queue.filter { it.isVideo }
        val index = videos.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
        contextQueue = V2ContextQueue(
            folderId = null,
            sortField = V2SortSpec().field,
            sortOrder = V2SortSpec().order,
            typeFilter = V2TypeFilter.VIDEO,
            mediaIds = videos.map { it.id },
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

    /** 播放请求头（Demo 空；未来 Server 直连传入鉴权头），UI 不感知差异。 */
    fun playbackHeaders(mediaId: String): Map<String, String> = repository.playbackHeaders(mediaId)

    fun mediaById(id: String): V2Media? = repository.mediaById(id)

    // ---------- 相册能力 ----------

    fun refreshAlbums() {
        viewModelScope.launch {
            _albums.value = repository.albums()
        }
    }

    /** 相册内照片（IMAGE ONLY）；用于 AlbumScreen 与相册 Viewer 队列。 */
    suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media> =
        repository.imagesInAlbum(albumId, spec)

    /** 用户选择相册封面（校验后持久化），随后刷新书架。 */
    fun setAlbumCover(albumId: String, mediaId: String) {
        viewModelScope.launch {
            repository.setAlbumCover(albumId, mediaId)
            _albums.value = repository.albums()
        }
    }

    /**
     * 刷新媒体列表（mapLatest 语义：取消上一次未完成的刷新，只保留最新一次）。
     * [debounceMs] > 0 时先等待该时长（用于搜索输入节流）。
     */
    private fun refreshList(debounceMs: Long = 0L) {
        listRefreshJob?.cancel()
        listRefreshJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
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
