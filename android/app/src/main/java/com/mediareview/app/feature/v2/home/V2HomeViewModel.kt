package com.mediareview.app.feature.v2.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.pairing.PairingRepository
import com.mediareview.app.core.pairing.REQUIRED_SERVER_API_CONTRACT
import com.mediareview.app.core.pairing.normalizeBaseUrl
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.data.server.V2ServerHealthMonitor
import com.mediareview.app.feature.v2.data.server.V2ServerSession
import com.mediareview.app.feature.v2.data.server.V2ServerSessionBootstrap
import com.mediareview.app.feature.v2.data.server.V2ServerStatus
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import com.mediareview.app.feature.v2.data.server.shouldLoadServerBusinessData
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.buildBrowseContext
import com.mediareview.app.feature.v2.model.V2ContextQueue
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2TypeFilter
import com.mediareview.app.feature.v2.perf.V2Perf
import com.mediareview.app.feature.v2.viewer.state.PendingDeleteController
import com.mediareview.app.feature.v2.viewer.state.PendingDeleteMessage
import com.mediareview.app.feature.v2.viewer.state.AlbumPagingController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private val homeDisplayModeStore: com.mediareview.app.feature.v2.data.HomeDisplayModeStore,
) : ViewModel() {

    val displayMode = homeDisplayModeStore.mode
    fun toggleDisplayMode() {
        val next = if (displayMode.value == HomeDisplayMode.TWO_COLUMN) HomeDisplayMode.ONE_COLUMN else HomeDisplayMode.TWO_COLUMN
        viewModelScope.launch {
            try { homeDisplayModeStore.set(next) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { _messages.emit("布局偏好保存失败") }
        }
    }

    private val _folderTree = MutableStateFlow<List<com.mediareview.app.feature.v2.model.V2FolderNode>>(emptyList())
    val folderTree = _folderTree.asStateFlow()
    private val _categoryHint = MutableStateFlow<String?>(null)
    val categoryHint = _categoryHint.asStateFlow()
    private val browseCache = BrowseWindowCache()
    private val folderBrowserController = FolderPagingController(viewModelScope, browseCache, { albumSourceGeneration }, ::emitMessage, repository::mediaPage)
    val folderBrowserState = folderBrowserController.state
    fun openFolderBrowser(folderId: String?, includeDescendants: Boolean = true) {
        if (isServerMode && !statusStore.supportsFolderTree) {
            folderBrowserController.close()
            return
        }
        folderBrowserController.open(folderId, _sortSpec.value, includeDescendants)
    }
    fun closeFolderBrowser() = folderBrowserController.close()
    fun updateFolderAnchor(anchor: HomeGridAnchor?) = folderBrowserController.updateAnchor(anchor)
    fun nextFolderPage() = folderBrowserController.loadNextPage()
    fun retryFolderPage() = folderBrowserController.retry()
    fun retryFolders() {
        folderJob?.cancel()
        folderJob = viewModelScope.launch { loadFolders() }
    }
    fun openFolderMedia(window: List<V2Media>, mediaId: String, folderId: String?): Boolean {
        val context = buildBrowseContext(window, mediaId) ?: return false
        invalidateAlbumViewer()
        contextQueue = context.copy(folderId = folderId, sortField = _sortSpec.value.field, sortOrder = _sortSpec.value.order)
        return true
    }

    // ---------- 数据源 / Server 状态 ----------

    val dataMode: StateFlow<V2DataMode> = modeStore.mode
    val serverStatus: StateFlow<V2ServerStatus> = statusStore.status

    /** Server 人类可读版本 + API Contract（Stage 8D.1 §36：设置页展示/排查用）。 */
    val serverVersion: StateFlow<String> = statusStore.serverVersion
    val serverApiContract: StateFlow<Int> = statusStore.serverApiContract
    val serverCapabilities = statusStore.capabilities

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

    /** 已确认的收藏变更；Viewer 独立窗口中的媒体也能原位更新。 */
    private val _confirmedFavoriteStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val confirmedFavoriteStates: StateFlow<Map<String, Boolean>> = _confirmedFavoriteStates.asStateFlow()

    private val _albums = MutableStateFlow<List<V2Album>>(emptyList())
    val albums: StateFlow<List<V2Album>> = _albums.asStateFlow()

    /**
     * P1 辅助数据的独立错误态（阶段 8A.1.1 §2）。
     *
     * 规则：媒体列表（P0）与文件夹/书架/收藏（P1）**完全分离**。
     * `GET /media?page=1` 成功后，即使 folders/albums 失败，媒体墙仍必须正常显示，
     * 不得在媒体墙底部出现"加载失败，点击重试"。
     */
    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()

    private val _albumError = MutableStateFlow<String?>(null)
    val albumError: StateFlow<String?> = _albumError.asStateFlow()

    private val _favoritesError = MutableStateFlow<String?>(null)
    val favoritesError: StateFlow<String?> = _favoritesError.asStateFlow()

    /**
     * 文件夹/书架补齐 Job（P1）。
     *
     * Stage 8A.1 启动优先级：首屏媒体（P0）绝不等待文件夹与书架；
     * 它们在同一时刻后台并行加载，到达后各自刷新 UI。
     */
    private var folderJob: Job? = null

    /** 收藏是否已从服务器加载过（收藏页首次进入才加载，见 [ensureFavoritesLoaded]）。 */
    private var favoritesLoaded = false
    private var favoritesLoading = false

    // 列表刷新 Job 竞态控制：只有最后一次触发的刷新生效（连点文件夹/排序/输入搜索词时，
    // 旧一次的结果不得覆盖新一次）；搜索输入走防抖路径，分页加载用 generation 丢弃过期响应。
    private var listRefreshJob: Job? = null
    private var paginationJob: Job? = null
    private var listGeneration = 0
    private var loadedPage = 1

    private var homeWindowKey: WindowKey? = null
    var homeAnchor: HomeGridAnchor? = null
        private set
    private var homeLeft = false
    private fun browseKey() = WindowKey(albumSourceGeneration, _selectedFolderId.value, _sortSpec.value)
    private fun saveHomeWindow() {
        val key = homeWindowKey ?: return
        if (loadedPage > 0 && key.sourceGeneration == albumSourceGeneration && _listError.value == null)
            browseCache.put(key, CachedWindow(_currentList.value, loadedPage, _totalCount.value, key.sort,
                homeAnchor, sourceGeneration = key.sourceGeneration))
    }
    fun updateHomeAnchor(anchor: HomeGridAnchor?) { homeAnchor = anchor; saveHomeWindow() }
    fun suspendHomeWindow() {
        saveHomeWindow(); homeLeft = true; listRefreshJob?.cancel(); paginationJob?.cancel(); listGeneration++; _listLoading.value = false
    }
    fun resumeHomeWindow() {
        if (homeLeft) { homeLeft = false; refreshList() }
    }
    private val searchController = SearchPagingController(viewModelScope, repository::mediaPage)
    val searchState = searchController.state
    fun loadNextSearchPage() = searchController.loadNextPage()
    fun searchNearEnd(index: Int) = searchController.nearEnd(index)
    fun retrySearch() = searchController.retry()

    /** 当前列表是否为空且仍在加载（首页空态文案用）。 */
    val loadingFirstPage: Boolean
        get() = _listLoading.value && _currentList.value.isEmpty()

    /** Viewer / Full 共用队列及带代次的撤销消息；旧提示入口保留兼容。 */
    private val _viewerPendingDeleteMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val viewerPendingDeleteMessages: SharedFlow<String> = _viewerPendingDeleteMessages
    private val _pendingDeleteMessages = MutableSharedFlow<PendingDeleteMessage>(extraBufferCapacity = 8)
    val pendingDeleteMessages: SharedFlow<PendingDeleteMessage> = _pendingDeleteMessages
    private val viewerPendingController = PendingDeleteController(
        viewModelScope, repository::pendingDeleteIds, repository::setPendingDelete,
        { text ->
            _viewerPendingDeleteMessages.tryEmit(text)
        },
        { _pendingDeleteMessages.tryEmit(it) },
    )
    val viewerPendingDeleteState = viewerPendingController.state
    val viewerPendingDeleteBusy = viewerPendingController.busy
    fun openViewerPendingDelete(): Long = viewerPendingController.open()
    fun closeViewerPendingDelete(session: Long) = viewerPendingController.close(session)
    fun retryViewerPendingDelete(session: Long) = viewerPendingController.retry(session)
    fun toggleViewerPendingDelete(session: Long, mediaId: String) {
        if (mediaId.startsWith("loc_") || repository.mediaById(mediaId)?.canDelete == false) {
            _viewerPendingDeleteMessages.tryEmit("本地来源为只读，不能加入待删除")
            return
        }
        viewerPendingController.toggle(session, mediaId)
    }
    fun undoPendingDelete(session: Long, mediaId: String) = viewerPendingController.undo(session, mediaId)
    fun isPendingDeleteSessionCurrent(session: Long): Boolean = viewerPendingController.isCurrentSession(session)

    private var albumSourceGeneration = 0L
    private val albumController = AlbumPagingController(viewModelScope, repository::albumPage, ::emitMessage, sourceGeneration = { albumSourceGeneration })
    val albumState = albumController.state
    private val _albumViewerMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val albumViewerMessages: SharedFlow<String> = _albumViewerMessages
    private val albumViewerController = AlbumPagingController(viewModelScope, repository::albumPage, message = {
        _albumViewerMessages.tryEmit(it)
    }, cacheEnabled = false)
    val albumViewerState = albumViewerController.state
    var albumViewerSession: Long? = null
        private set
    fun openAlbum(albumId: String, spec: V2SortSpec): Long = albumController.open(albumId, spec)
    fun closeAlbum(session: Long) = albumController.close(session)
    fun updateAlbumAnchor(session: Long, anchor: HomeGridAnchor?) = albumController.updateAnchor(session, anchor)
    fun albumNearEnd(session: Long, index: Int) = albumController.nearEnd(session, index)
    fun retryAlbum(session: Long) = albumController.retry(session)
    fun closeAlbumViewer(session: Long) {
        albumViewerController.close(session)
        if (albumViewerSession == session) albumViewerSession = null
    }
    fun albumViewerNearEnd(session: Long, index: Int) = albumViewerController.nearEnd(session, index)
    fun retryAlbumViewer(session: Long) = albumViewerController.retry(session)
    private fun invalidateAlbumViewer() {
        albumViewerController.invalidate()
        albumViewerSession = null
    }
    private fun invalidateAlbumSource() {
        folderBrowserController.invalidate()
        browseCache.clear()
        homeWindowKey = null
        homeAnchor = null
        listRefreshJob?.cancel()
        resetPager()
        _currentList.value = emptyList()
        _folderTree.value = emptyList()
        _folders.value = emptyList()
        _categoryHint.value = null
        setSearchMode(false)
        _confirmedFavoriteStates.value = emptyMap()
        albumSourceGeneration++
        albumController.invalidate()
        invalidateAlbumViewer()
    }

    init {
        V2Perf.openHome()
        viewModelScope.launch {
            try { homeDisplayModeStore.bootstrap() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { _messages.emit("布局偏好读取失败") }
        }
        viewModelScope.launch {
            // 只读本地持久化模式：不联网、不等待 Server，首页立即可用
            val mode = modeStore.bootstrap()
            if (mode == V2DataMode.SERVER) {
                _serverSession.value = bootstrap.restore()
                // Stage 8D.2 §9/§10：UI 立即出现（壳 + "重新连接中…"），不阻塞首屏。
                _initializing.value = false
                viewModelScope.launch {
                    // §8：先做兼容性探测，探测完成前**绝不**发任何业务请求。
                    healthMonitor.probe()
                    // 只有兼容(Online)才加载业务数据；Incompatible / Offline / AuthRejected 一律不 reload。
                    if (shouldLoadServerBusinessData(statusStore.status.value)) {
                        reloadAll()
                    }
                }
            } else {
                statusStore.update(V2ServerStatus.Unconfigured)
                reloadAll()
                _initializing.value = false
            }
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
            viewerPendingController.invalidate()
            invalidateAlbumSource()
            // 阶段 8A.1.1 §4: 切换数据源必须失效辅助缓存
            repository.invalidateAuxiliaryCache()
            // Stage 8D.2 §25：切换数据源先清掉旧 Server 的版本 / contract / 兼容性元数据
            statusStore.reset()
            modeStore.set(mode)
            resetPager()
            _currentList.value = emptyList()
            _favorites.value = emptyList()
            _albums.value = emptyList()
            if (mode == V2DataMode.SERVER) {
                _serverSession.value = bootstrap.restore()
                // §11：先 probe 兼容性；只有 Online 才加载业务数据，其余状态不 reload。
                healthMonitor.probe()
                if (statusStore.status.value == V2ServerStatus.Online) {
                    reloadAll()
                }
            } else {
                // §12：切回 Demo 立即可用，无需任何健康探测
                reloadAll()
            }
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
                is PairingRepository.Result.Incompatible -> {
                    // Stage 8D.2 §6/§7：旧 Server 立即停止 —— 不 verify、不落库、不切 SERVER、不清 Token。
                    statusStore.recordIncompatible(healthy.version, healthy.apiContract)
                    _connectState.value = _connectState.value.copy(
                        busy = false,
                        error = "服务器版本过旧（当前 ${healthy.version}，需要 API Contract " +
                            "$REQUIRED_SERVER_API_CONTRACT），请升级 MediaReview Server",
                    )
                    return@launch
                }
                else -> Unit
            }
            when (val paired = pairingRepository.verifyAndPair(address, _connectState.value.code)) {
                is PairingRepository.Result.Paired -> {
                    viewerPendingController.invalidate()
                    invalidateAlbumSource()
                    _connectState.value = _connectState.value.copy(
                        busy = false,
                        message = "配对成功",
                        error = null,
                    )
                    // 配对成功后 TokenProvider 已由 PairingRepository 更新；刷新内存会话
                    _serverSession.value = bootstrap.refresh()
                    // 阶段 8A.1.1 §4: 连到（可能是另一个）服务器必须失效辅助缓存
                    repository.invalidateAuxiliaryCache()
                    // §25：新服务器不得沿用旧 Server 的版本 / contract / 兼容性元数据
                    statusStore.reset()
                    modeStore.set(V2DataMode.SERVER)
                    resetPager()
                    _currentList.value = emptyList()
                    // §11：probe 后再决定是否加载业务数据
                    healthMonitor.probe()
                    if (shouldLoadServerBusinessData(statusStore.status.value)) {
                        reloadAll()
                    }
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

    /** 手动重试后台健康探测（§24：恢复 Online 只能依靠兼容的 health probe）。 */
    fun probeServer() {
        viewModelScope.launch {
            healthMonitor.probe()
            if (isServerMode && shouldLoadServerBusinessData(statusStore.status.value)) {
                reloadAll()
            }
        }
    }

    /** 断开服务器配置（保留 Demo 可用）。 */
    fun clearServerConfig() {
        viewerPendingController.invalidate()
        invalidateAlbumSource()
        viewModelScope.launch {
            pairingRepository.clear()
            // 阶段 8A.1.1 §4: 断开连接必须失效辅助缓存,避免下次连别的服务器命中旧数据
            repository.invalidateAuxiliaryCache()
            _serverSession.value = V2ServerSession()
            // §25：断开连接清空 Server 版本 / contract / 兼容性元数据
            statusStore.reset()
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
            searchController.clear()
        }
    }

    fun updateSearchQuery(q: String) {
        searchQuery = q
        searchController.updateQuery(q)
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
        _listLoading.value = true
        paginationJob = viewModelScope.launch {
            // PAGING 会话: page_request → page_response 相对本次请求计时(§1.1)
            val pagingSession = V2Perf.openPaging(nextPage)
            try {
                val page = repository.mediaPage(query(nextPage))
                if (generation != listGeneration) return@launch
                val existing = _currentList.value.map { it.id }.toSet()
                _currentList.value = _currentList.value + page.items.filter { it.id !in existing }.map { media ->
                    _confirmedFavoriteStates.value[media.id]?.let { media.copy(isFavorite = it) } ?: media
                }
                loadedPage = page.page
                _totalCount.value = page.total
                _hasMore.value = page.hasMore
                _listError.value = null
                saveHomeWindow()
                pagingSession.mark("page_response", { "page=$nextPage items=${page.items.size}" })
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
        browseCache.clear()
        homeWindowKey = null
        albumController.invalidate()
        folderBrowserController.invalidate()
        albumSourceGeneration++
        invalidateAlbumViewer()
        refreshList()
    }

    fun setFavorite(mediaId: String, favorite: Boolean) {
        val sourceGeneration = albumSourceGeneration
        viewModelScope.launch {
            val confirmed = repository.setFavorite(mediaId, favorite)
            if (sourceGeneration != albumSourceGeneration) return@launch
            if (confirmed) {
                applyFavoriteLocally(mediaId, favorite)
            } else {
                // 服务器未确认：保持原状态（从未乐观更新），只提示
                emitMessage(if (favorite) "收藏失败：服务器未确认" else "取消收藏失败：服务器未确认")
            }
        }
    }

    /** 搜索与收藏只在点击时加载的同类型窗口中浏览；收藏变动不重建队列。 */
    fun openSearchMedia(loadedSearchItems: List<V2Media>, mediaId: String): Boolean =
        openBrowseWindow(loadedSearchItems, mediaId)

    fun openFavoriteMedia(favorites: List<V2Media>, mediaId: String): Boolean =
        openBrowseWindow(favorites, mediaId)

    private fun openBrowseWindow(items: List<V2Media>, mediaId: String): Boolean {
        val queue = buildBrowseContext(items, mediaId) ?: return false
        invalidateAlbumViewer()
        contextQueue = queue
        return true
    }

    /**
     * 在首页当前列表中打开某条媒体（建立 Viewer/Player 的上下文队列）。
     *
     * 阶段 8B §10：找不到 mediaId 时**不得**静默打开第 1 项
     * （旧实现 `indexOfFirst(...).coerceAtLeast(0)` 会把"未找到"变成第 0 项）。
     *
     * 但也不能因此阻断打开（收藏页 / 文件夹页 / 相册页点进来的媒体常常不在首页列表里）：
     * 目标不在当前列表时退化为**单条上下文**（只含被点击的那一条，绝不指向别的媒体）。
     *
     * @return true = 已建立上下文；false = 该 media 连数据源都查不到（不建立上下文，调用方应提示）
     */
    fun openMedia(mediaId: String): Boolean {
        invalidateAlbumViewer()
        val list = _currentList.value
        val index = list.indexOfFirst { it.id == mediaId }
        if (index < 0) {
            // 单条上下文：Context 里只有用户真正点击的那一条，播放器/查看器不会跳到别的媒体
            val single = repository.mediaById(mediaId) ?: return false
            contextQueue = V2ContextQueue(
                folderId = single.folderId.takeIf { it.isNotBlank() },
                sortField = _sortSpec.value.field,
                sortOrder = _sortSpec.value.order,
                typeFilter = if (single.isVideo) V2TypeFilter.VIDEO else V2TypeFilter.IMAGE,
                mediaIds = listOf(single.id),
                currentIndex = 0,
            )
            return true
        }
        contextQueue = V2ContextQueue(
            folderId = _selectedFolderId.value,
            sortField = _sortSpec.value.field,
            sortOrder = _sortSpec.value.order,
            typeFilter = _sortSpec.value.typeFilter,
            mediaIds = list.map { it.id },
            currentIndex = index,
        )
        return true
    }

    /**
     * 在指定媒体序列中打开某张图片（用于相册：Viewer 只在当前相册照片间翻页）。
     *
     * 阶段 8B §10：找不到 mediaId 时不建立上下文、不静默指向第 1 项。
     */
    fun openMediaIn(list: List<V2Media>, mediaId: String): Boolean {
        invalidateAlbumViewer()
        val index = list.indexOfFirst { it.id == mediaId }
        if (index < 0) return false
        contextQueue = V2ContextQueue(
            folderId = _selectedFolderId.value,
            sortField = _sortSpec.value.field,
            sortOrder = _sortSpec.value.order,
            typeFilter = V2TypeFilter.IMAGE,
            mediaIds = list.map { it.id },
            currentIndex = index,
        )
        return true
    }

    /** 相册 Viewer 独立来源，不读取首页 folder/filter/sort；只保存已加载照片窗口。 */
    fun openAlbumImage(albumId: String, loadedImages: List<V2Media>, total: Int, mediaId: String, loadedPage: Int, sortSpec: V2SortSpec): Boolean {
        val images = loadedImages.filter { !it.isVideo }.distinctBy { it.id }
        val index = images.indexOfFirst { it.id == mediaId }
        if (index < 0) return false
        albumViewerSession = albumViewerController.open(albumId, sortSpec, images, total, loadedPage)
        contextQueue = V2ContextQueue(albumId, sortSpec.field, sortSpec.order, V2TypeFilter.IMAGE, images.map { it.id }, index)
        return true
    }

    fun updateAlbumViewerIndex(index: Int) {
        val window = albumViewerState.value ?: return
        val queue = contextQueue ?: return
        if (queue.folderId == window.albumId && window.mediaIds.isNotEmpty()) {
            contextQueue = queue.copy(mediaIds = window.mediaIds, currentIndex = index.coerceIn(0, window.items.lastIndex))
        }
    }

    /**
     * 在指定视频队列中打开（批阅页 → 完整播放器：Player 按此队列上下条，
     * 返回时依旧回到批阅原位置）。
     *
     * 阶段 8B §10：找不到 mediaId 时不建立上下文、不静默指向第 1 项。
     */
    fun openMediaInVideoQueue(queue: List<V2Media>, mediaId: String): Boolean {
        invalidateAlbumViewer()
        val videos = queue.filter { it.isVideo }
        val index = videos.indexOfFirst { it.id == mediaId }
        if (index < 0) return false
        contextQueue = V2ContextQueue(
            folderId = null,
            sortField = V2SortSpec().field,
            sortOrder = V2SortSpec().order,
            typeFilter = V2TypeFilter.VIDEO,
            mediaIds = videos.map { it.id },
            currentIndex = index,
        )
        return true
    }

    /**
     * 由播放器/查看器更新队列（如左右翻页后同步索引）。
     *
     * 阶段 8B §11：空队列时 `coerceIn(0, -1)` 是非法的，必须先判空直接返回。
     */
    fun updateQueueIndex(index: Int) {
        val q = contextQueue ?: return
        val clamped = q.clampIndex(index) ?: return
        contextQueue = q.copy(currentIndex = clamped)
    }

    // ---------- URI 能力（委托数据层，UI 不感知数据来源） ----------

    fun thumbUri(media: V2Media): String = repository.thumbUri(media)

    /** 统一封面 URI（视频→Poster、图片→缩略图；UI 全用这个，不感知数据来源）。 */
    fun coverUri(media: V2Media): String = repository.coverUri(media)
    val imageCacheNamespace: String get() = repository.imageCacheNamespace

    /** 图片原图 URI（薄委托，加载逻辑在数据层；Server 模式为原图代理 URL）。 */
    fun imageUri(media: V2Media): String = repository.imageUri(media)


    fun mediaById(id: String): V2Media? = repository.mediaById(id)

    // ---------- 相册能力 ----------

    fun refreshAlbums() {
        viewModelScope.launch { refreshAlbumsNow() }
    }

    /**
     * 相册内照片分页（IMAGE ONLY，阶段 8B §12）：AlbumScreen 滚动加载下一页，
     * 大相册（>200 张）不再被截断。异常保留，由分页控制器转为可重试 Error。
     */
    suspend fun albumPage(albumId: String, page: Int, spec: V2SortSpec): V2MediaPage =
        repository.albumPage(albumId, page, ALBUM_PAGE_SIZE, spec)

    /** 用户选择相册封面（校验后持久化），随后刷新书架。 */
    fun setAlbumCover(albumId: String, mediaId: String) {
        val source = albumSourceGeneration
        viewModelScope.launch {
            try {
                repository.setAlbumCover(albumId, mediaId)
                if (source == albumSourceGeneration) refreshAlbumsNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (source == albumSourceGeneration) emitMessage("相册封面保存失败")
            }
        }
    }

    // ---------- 内部 ----------

    /**
     * 重新加载首页数据（切数据源 / 进入 Server 模式后调用）。
     *
     * Stage 8A.1 启动优先级（原实现 folders → albums → favorites → media 已废弃）：
     * - **P0** `GET /media?page=1` 必须最先开始，首屏媒体到达后立即渲染媒体墙；
     * - **P1** `GET /media/folders`（书架由同一份 FolderSummary 构建）后台并行补齐；
     * - 收藏列表**不参与启动**，用户首次进入收藏页才加载（见 [ensureFavoritesLoaded]）。
     */
    private suspend fun reloadAll() {
        if (repository.mode == V2DataMode.SERVER && !_serverSession.value.configured) {
            // Server 模式但没有配置：不发起任何请求，UI 引导去"连接服务器"
            folderJob?.cancel()
            favoritesLoaded = false
            _folders.value = emptyList()
            _folderTree.value = emptyList()
            _categoryHint.value = null
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
        favoritesLoaded = false
        _favorites.value = emptyList()
        _favoritesError.value = null
        _folderError.value = null
        _albumError.value = null

        // P0: 首屏媒体最先开始,且绝不等待文件夹 / 书架 / 收藏
        refreshList()

        // P1: 文件夹 + 书架后台并行补齐(albums 复用 folders 的短时缓存,不重复 HTTP)
        folderJob?.cancel()
        folderJob = viewModelScope.launch {
            loadFolders()
            refreshAlbumsNow()
        }
    }

    /**
     * 收藏页首次进入时懒加载（Stage 8A.1 §6：收藏不得阻塞首屏）。
     *
     * 阶段 8A.1.1 §3 修正：失败不再被永久锁死 —— 只有成功才置 favoritesLoaded；
     * [force] = true 供"重新加载"按钮使用。
     */
    fun ensureFavoritesLoaded(force: Boolean = false) {
        if (favoritesLoading) return
        if (favoritesLoaded && !force) return
        favoritesLoading = true
        viewModelScope.launch {
            try {
                favoritesLoaded = refreshFavorites()
            } finally {
                favoritesLoading = false
            }
        }
    }

    /** 收藏加载失败后的"重新加载"。 */
    fun retryFavorites() {
        ensureFavoritesLoaded(force = true)
    }

    /**
     * 最终删除完成后的 V2 刷新（Stage 8C §24）。
     *
     * success / missing 的媒体在服务端已消失：
     * - 丢弃这些媒体在 [MediaRepository] 资源缓存中的条目（避免 mediaById / 封面命中陈旧数据）；
     * - 失效文件夹辅助缓存并重载首页列表 / 文件夹 / 书架；收藏标记为待重载。
     * 只刷新数据，不改变用户当前页面（待删除中心仍停留）。
     */
    fun refreshAfterFinalDelete(removedMediaIds: List<String> = emptyList()) {
        invalidateAlbumSource()
        // 删除仅失效已删资源和folder计数；SWR期间保留未删图片的URL/源缓存身份。
        repository.dropCachedMedia(removedMediaIds)
        viewModelScope.launch { reloadAll() }
    }

    /**
     * 媒体库选择保存成功后的 V2 刷新（Stage 8C §41）：失效辅助缓存并重载首页数据，
     * 保证不继续显示旧库内容（folders / albums / media page / favorites 一并失效）。
     */
    fun refreshAfterLibraryChange() {
        invalidateAlbumSource()
        repository.invalidateAuxiliaryCache()
        viewModelScope.launch { reloadAll() }
    }

    private suspend fun loadFolders() {
        val source = albumSourceGeneration
        try {
            val tree = repository.folderTree()
            if (source != albumSourceGeneration) return
            _folderTree.value = tree.orEmpty()
            _categoryHint.value = if (tree == null) "电脑端版本过旧，层级分类需要 MediaReview Server 1.3.0+" else null
            val roots = com.mediareview.app.feature.v2.model.homeFolderRoots(tree.orEmpty())
            _folders.value = roots.map {
                V2Folder(it.id, it.name, "", emptyList(), it.recursiveMediaCount, it.directImageCount, it.coverUrl)
            }
            _folderCounts.value = roots.associate { it.id to it.recursiveMediaCount }
            _folderError.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (source == albumSourceGeneration) {
                _folderError.value = "分类加载失败"
                _categoryHint.value = "分类加载失败，点击重试"
            }
        }
    }

    private suspend fun refreshAlbumsNow() {
        val source = albumSourceGeneration
        try {
            val albums = repository.albums()
            if (source != albumSourceGeneration) return
            _albums.value = albums
            _albumError.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (source == albumSourceGeneration) _albumError.value = "书架加载失败"
        }
    }

    /**
     * 收藏加载（阶段 8A.1.1 §3）。
     *
     * - **只有服务端明确成功返回**才返回 true（调用方据此置 favoritesLoaded）；
     * - 失败时**不清空**已有收藏列表（临时故障不应让界面退化），只写 favoritesError；
     * - 首次失败后再次进入收藏页会重新请求（不再被永久锁死）。
     */
    private suspend fun refreshFavorites(): Boolean =
        runCatching { repository.favorites() }.fold(
            onSuccess = { items ->
                _favorites.value = items
                _favoritesError.value = null
                true
            },
            onFailure = { error ->
                _favoritesError.value = error.message ?: "收藏加载失败"
                false
            },
        )

    private fun query(page: Int): V2MediaQuery = V2MediaQuery(
        page = page,
        pageSize = V2MediaQuery.DEFAULT_PAGE_SIZE,
        folderId = _selectedFolderId.value,
        search = null,
        spec = _sortSpec.value,
    )

    private fun resetPager() {
        paginationJob?.cancel()
        paginationJob = null
        listGeneration++
        loadedPage = 1
        _hasMore.value = false
        _totalCount.value = 0
    }

    /** 收藏成功后本地同步（Server 已确认；不做乐观更新，失败即保持原状态）。 */
    private fun applyFavoriteLocally(mediaId: String, favorite: Boolean) {
        _confirmedFavoriteStates.value = _confirmedFavoriteStates.value + (mediaId to favorite)
        searchController.updateFavorite(mediaId, favorite)
        folderBrowserController.updateFavorite(mediaId, favorite)
        albumController.updateFavorite(mediaId, favorite)
        albumViewerController.updateFavorite(mediaId, favorite)
        browseCache.patchFavorite(mediaId, favorite)
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
        saveHomeWindow()
        listRefreshJob?.cancel()
        resetPager()
        val generation = listGeneration
        val key = browseKey()
        homeWindowKey = key
        val cached = browseCache.get(key)
        _currentList.value = cached?.items.orEmpty()
        loadedPage = cached?.page ?: 0
        _totalCount.value = cached?.total ?: 0
        _hasMore.value = cached?.hasMore ?: false
        homeAnchor = cached?.anchor
        _listError.value = null
        _listLoading.value = true
        listRefreshJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val session = V2Perf.home()
            session?.markOnce("first_media_request", "first_media_request")
            try {
                val items = mutableListOf<V2Media>()
                var result: V2MediaPage? = null
                for (pageNumber in 1..(cached?.page ?: 1)) {
                    result = repository.mediaPage(V2MediaQuery(pageNumber, 50, key.folderId, spec = key.sort))
                    if (generation != listGeneration || key.sourceGeneration != albumSourceGeneration) return@launch
                    check(result.page == pageNumber && result.pageSize == 50 && result.total >= 0)
                    items += result.items
                    if (!result.hasMore) break
                }
                val page = requireNotNull(result)
                _currentList.value = items.distinctBy { it.id }.map { media ->
                    _confirmedFavoriteStates.value[media.id]?.let { media.copy(isFavorite = it) } ?: media
                }
                loadedPage = page.page
                _totalCount.value = page.total
                _hasMore.value = page.hasMore
                _listError.value = null
                saveHomeWindow()
                session?.markOnce("first_media_response", "first_media_response", { "items=${items.size} total=${page.total}" })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (generation == listGeneration && key.sourceGeneration == albumSourceGeneration) {
                    if (cached == null) _listError.value = error.message ?: "加载失败"
                    else emitMessage("刷新失败，已保留上次内容")
                }
            } finally {
                if (generation == listGeneration) _listLoading.value = false
            }
        }
    }
    companion object {
        /** 相册每页条数（阶段 8B §12：第一页 50，接近底部继续加载下一页）。 */
        const val ALBUM_PAGE_SIZE = 50
    }
}
