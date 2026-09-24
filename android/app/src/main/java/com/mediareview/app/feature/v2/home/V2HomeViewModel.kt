package com.mediareview.app.feature.v2.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.connect.data.PairingRepository
import com.mediareview.app.feature.connect.normalizeBaseUrl
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.data.server.V2ServerHealthMonitor
import com.mediareview.app.feature.v2.data.server.V2ServerSession
import com.mediareview.app.feature.v2.data.server.V2ServerSessionBootstrap
import com.mediareview.app.feature.v2.data.server.V2ServerStatus
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2ContextQueue
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2TypeFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 首页/书架模式切换。 */
enum class V2HomeTab { MEDIA, SHELF }

/** 数据源 Sheet 的"连接服务器"表单状态（第一版：手动 IP + 配对码）。 */
data class V2ServerConnectState(
    val address: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/**
 * V2 首页共享 ViewModel（Activity 作用域）：
 * 持有数据源模式、文件夹、排序/过滤、搜索、分页列表、收藏与页面上下文队列。
 *
 * Stage 8A 要点：
 * - 启动只读本地数据源模式（DataStore），**不等待 Server**：DEMO 立刻可用，
 *   SERVER 模式下健康探测在后台进行（顶部轻量状态提示），不 Blocking 页面；
 * - 列表走统一分页接口 [MediaRepository.mediaPage]：Server 模式由服务端执行
 *   search / sort / filter / page（50 条/页，滚动加载下一页），Demo 模式内存分页；
 * - 收藏以服务器确认为准：失败不改变 UI 状态并给出提示（禁止假成功）。
 */
@HiltViewModel
class V2HomeViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val searchHistory: SearchHistoryStore,
    private val modeStore: V2DataModeStore,
    private val bootstrap: V2ServerSessionBootstrap,
    private val healthMonitor: V2ServerHealthMonitor,
    private val statusStore: V2ServerStatusStore,
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    // ---------- 数据源 / Server 状态 ----------

    val dataMode: StateFlow<V2DataMode> = modeStore.mode
    val serverStatus: StateFlow<V2ServerStatus> = statusStore.status

    private val _serverSession = MutableStateFlow(V2ServerSession())
    val serverSession: StateFlow<V2ServerSession> = _serverSession.asStateFlow()

    private val _initializing = MutableStateFlow(true)
    val initializing: StateFlow<Boolean> = _initializing.asStateFlow()

    private val _listLoading = MutableStateFlow(false)
    val listLoading: StateFlow<Boolean> = _listLoading.asStateFlow()

    private val _listError = MutableStateFlow<String?>(null)
    val listError: StateFlow<String?> = _listError.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _connectState = MutableStateFlow(V2ServerConnectState())
    val connectState: StateFlow<V2ServerConnectState> = _connectState.asStateFlow()

    /** 一次性用户提示（Snackbar 用；收藏失败 / 连接结果等）。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val isServerMode: Boolean get() = repository.mode == V2DataMode.SERVER

    // ---------- 首页状态 ----------

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

    // 列表刷新 Job 竞态控制：只有最后一次触发的刷新生效（连点文件夹/排序/输入搜索词时，
    // 旧一次的结果不得覆盖新一次）；搜索输入走防抖路径，分页加载用 generation 丢弃过期响应。
    private var listRefreshJob: Job? = null
    private var listGeneration = 0
    private var loadedPage = 1

    /** 搜索防抖窗口（输入停顿该时长后才真正查询）。 */
    private val searchDebounceMs = 300L

    /** 当前列表是否为空且仍在加载（首页空态文案用）。 */
    val loadingFirstPage: Boolean
        get() = _listLoading.value && _currentList.value.isEmpty()

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
            // 只读本地持久化模式：不联网、不等待 Server，首页立即可用
            val mode = modeStore.bootstrap()
            if (mode == V2DataMode.SERVER) {
                _serverSession.value = bootstrap.restore()
                // 后台快速探测（不阻塞启动 / 不阻塞首页）
                viewModelScope.launch { healthMonitor.probe() }
            } else {
                statusStore.update(V2ServerStatus.Unconfigured)
            }
            reloadAll()
            _initializing.value = false
        }
        // 搜索历史从 DataStore 恢复（App 重启后仍在）
        viewModelScope.launch {
            recentSearches = searchHistory.current()
        }
    }

    // ---------- 数据源切换 / 服务器连接 ----------

    /** 切换数据源（设置里的 ● Demo / ○ 我的服务器）。Server 挂了也能随时切回 Demo。 */
    fun setDataMode(mode: V2DataMode) {
        viewModelScope.launch {
            if (mode == repository.mode) return@launch
            modeStore.set(mode)
            if (mode == V2DataMode.SERVER) {
                _serverSession.value = bootstrap.restore()
                viewModelScope.launch { healthMonitor.probe() }
            }
            resetPager()
            _currentList.value = emptyList()
            _favorites.value = emptyList()
            _albums.value = emptyList()
            reloadAll()
            emitMessage(if (mode == V2DataMode.SERVER) "已切换到我的服务器" else "已切换到演示数据")
        }
    }

    fun onConnectAddressChange(value: String) {
        _connectState.value = _connectState.value.copy(address = value, error = null, message = null)
    }

    fun onConnectCodeChange(value: String) {
        _connectState.value = _connectState.value.copy(code = value, error = null, message = null)
    }

    /**
     * 首次 Server 配置（V2 内部 Sheet，不跳出 V2）：
     * 手动输入地址 → 复用 1.1 的 PairingRepository.checkHealthy / verifyAndPair → 配对待 token。
     */
    fun connectServer() {
        val state = _connectState.value
        if (state.busy) return
        val address = runCatching { normalizeBaseUrl(state.address) }.getOrElse { error ->
            _connectState.value = state.copy(error = error.message ?: "服务器地址无效")
            return
        }
        if (state.code.isBlank()) {
            _connectState.value = state.copy(error = "请输入配对码")
            return
        }
        _connectState.value = state.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            when (val healthy = pairingRepository.checkHealthy(address)) {
                is PairingRepository.Result.Failure -> {
                    _connectState.value = _connectState.value.copy(
                        busy = false,
                        error = healthy.message,
                    )
                    return@launch
                }
                else -> Unit
            }
            when (val paired = pairingRepository.verifyAndPair(address, _connectState.value.code)) {
                is PairingRepository.Result.Paired -> {
                    _connectState.value = _connectState.value.copy(
                        busy = false,
                        message = "配对成功",
                        error = null,
                    )
                    // 配对成功后 TokenProvider 已由 PairingRepository 更新；刷新内存会话
                    _serverSession.value = bootstrap.refresh()
                    modeStore.set(V2DataMode.SERVER)
                    resetPager()
                    _currentList.value = emptyList()
                    reloadAll()
                    viewModelScope.launch { healthMonitor.probe() }
                    emitMessage("已连接服务器")
                }
                is PairingRepository.Result.Failure -> {
                    _connectState.value = _connectState.value.copy(
                        busy = false,
                        error = paired.message,
                    )
                }
                else -> _connectState.value = _connectState.value.copy(busy = false)
            }
        }
    }

    /** 手动重试后台健康探测。 */
    fun probeServer() {
        viewModelScope.launch { healthMonitor.probe() }
    }

    /** 断开服务器配置（保留 Demo 可用）。 */
    fun clearServerConfig() {
        viewModelScope.launch {
            pairingRepository.clear()
            _serverSession.value = V2ServerSession()
            statusStore.update(V2ServerStatus.Unconfigured)
            modeStore.set(V2DataMode.DEMO)
            resetPager()
            _currentList.value = emptyList()
            reloadAll()
            emitMessage("已断开服务器，切回演示数据")
        }
    }

    // ---------- 列表 / 分页 ----------

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
        _sortSpec.value = _sortSpec.value.copy(
            field = com.mediareview.app.feature.v2.model.V2SortField.RECENT,
            order = com.mediareview.app.feature.v2.model.V2SortOrder.DESC,
        )
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
        // 输入防抖：停顿 300ms 后才真正查询（Server 模式下避免每个字符都发请求）
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

    /**
     * 滚动到底部时加载下一页（Server 模式：服务端分页；Demo：内存分页）。
     * 同一时刻只允许一个加载，过期响应按 generation 丢弃。
     */
    fun loadNextPage() {
        if (_listLoading.value || !_hasMore.value) return
        val generation = listGeneration
        val nextPage = loadedPage + 1
        viewModelScope.launch {
            _listLoading.value = true
            try {
                val page = repository.mediaPage(query(nextPage))
                if (generation != listGeneration) return@launch
                val existing = _currentList.value.map { it.id }.toSet()
                _currentList.value = _currentList.value + page.items.filter { it.id !in existing }
                loadedPage = page.page
                _totalCount.value = page.total
                _hasMore.value = page.hasMore
                _listError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == listGeneration) {
                    _listError.value = error.message ?: "加载失败"
                }
            } finally {
                if (generation == listGeneration) _listLoading.value = false
            }
        }
    }

    /** 手动重试当前列表（网络失败后）。 */
    fun retryList() {
        refreshList()
    }

    fun setFavorite(mediaId: String, favorite: Boolean) {
        viewModelScope.launch {
            val confirmed = repository.setFavorite(mediaId, favorite)
            if (confirmed) {
                applyFavoriteLocally(mediaId, favorite)
            } else {
                // 服务器未确认：保持原状态（从未乐观更新），只提示
                emitMessage(if (favorite) "收藏失败：服务器未确认" else "取消收藏失败：服务器未确认")
            }
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

    /** 图片原图 URI（薄委托，加载逻辑在数据层；Server 模式为原图代理 URL）。 */
    fun imageUri(media: V2Media): String = repository.imageUri(media)

    fun spriteUri(media: V2Media): String? = repository.spriteUri(media)

    fun spriteManifest(media: V2Media): com.mediareview.app.feature.v2.model.V2SpriteManifest? =
        repository.spriteManifest(media)

    fun mediaById(id: String): V2Media? = repository.mediaById(id)

    // ---------- 相册能力 ----------

    fun refreshAlbums() {
        viewModelScope.launch {
            _albums.value = runCatching { repository.albums() }.getOrDefault(emptyList())
        }
    }

    /** 相册内照片（IMAGE ONLY）；用于 AlbumScreen 与相册 Viewer 队列。 */
    suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media> =
        runCatching { repository.imagesInAlbum(albumId, spec) }.getOrDefault(emptyList())

    /** 用户选择相册封面（校验后持久化），随后刷新书架。 */
    fun setAlbumCover(albumId: String, mediaId: String) {
        viewModelScope.launch {
            repository.setAlbumCover(albumId, mediaId)
            _albums.value = runCatching { repository.albums() }.getOrDefault(emptyList())
        }
    }

    // ---------- 内部 ----------

    /** 重新加载全部首页数据（切数据源 / 进入 Server 模式后调用）。 */
    private suspend fun reloadAll() {
        if (repository.mode == V2DataMode.SERVER && !_serverSession.value.configured) {
            // Server 模式但没有配置：不发起任何请求，UI 引导去"连接服务器"
            _folders.value = emptyList()
            _folderCounts.value = emptyMap()
            _albums.value = emptyList()
            _favorites.value = emptyList()
            _currentList.value = emptyList()
            _totalCount.value = 0
            _hasMore.value = false
            _listError.value = null
            statusStore.update(V2ServerStatus.Unconfigured)
            return
        }
        loadFolders()
        refreshAlbumsNow()
        refreshFavorites()
        refreshList()
    }

    private suspend fun loadFolders() {
        val folders = runCatching { repository.folders() }.getOrElse { error ->
            _listError.value = error.message ?: "文件夹加载失败"
            _folders.value = emptyList()
            _folderCounts.value = emptyMap()
            return
        }
        _folders.value = folders
        // 文件夹计数来自数据源聚合（Server 由服务端 count 下发，App 不做 N+1）
        _folderCounts.value = folders.associate { it.id to it.count }
    }

    private suspend fun refreshAlbumsNow() {
        _albums.value = runCatching { repository.albums() }.getOrDefault(emptyList())
    }

    private suspend fun refreshFavorites() {
        _favorites.value = runCatching { repository.favorites() }.getOrDefault(emptyList())
    }

    private fun query(page: Int): V2MediaQuery = V2MediaQuery(
        page = page,
        pageSize = V2MediaQuery.DEFAULT_PAGE_SIZE,
        folderId = _selectedFolderId.value,
        search = if (searchActive) searchQuery.trim().takeIf { it.isNotEmpty() } else null,
        spec = _sortSpec.value,
    )

    private fun resetPager() {
        listGeneration++
        loadedPage = 1
        _hasMore.value = false
        _totalCount.value = 0
    }

    /** 收藏成功后本地同步（Server 已确认；不做乐观更新，失败即保持原状态）。 */
    private fun applyFavoriteLocally(mediaId: String, favorite: Boolean) {
        _currentList.value = _currentList.value.map { media ->
            if (media.id == mediaId) media.copy(isFavorite = favorite) else media
        }
        if (!favorite) {
            _favorites.value = _favorites.value.filterNot { it.id == mediaId }
        } else if (_favorites.value.none { it.id == mediaId }) {
            repository.mediaById(mediaId)?.let { media ->
                _favorites.value = _favorites.value + media.copy(isFavorite = true)
            }
        }
    }

    private fun emitMessage(message: String) {
        _messages.tryEmit(message)
    }

    /**
     * 刷新媒体列表（只保留最新一次；分页 = 每页 50 条滚动加载，绝不一次拉全量）。
     * [debounceMs] > 0 时先等待该时长（用于搜索输入节流）。
     */
    private fun refreshList(debounceMs: Long = 0L) {
        listRefreshJob?.cancel()
        resetPager()
        val generation = listGeneration
        listRefreshJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _listLoading.value = true
            try {
                val page = repository.mediaPage(query(page = 1))
                if (generation != listGeneration) return@launch
                _currentList.value = page.items
                loadedPage = page.page
                _totalCount.value = page.total
                _hasMore.value = page.hasMore
                _listError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == listGeneration) {
                    _currentList.value = emptyList()
                    _listError.value = error.message ?: "加载失败"
                }
            } finally {
                if (generation == listGeneration) _listLoading.value = false
            }
        }
    }
}