package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FolderBrowseState(
    val folderId: String? = null,
    val items: List<V2Media> = emptyList(),
    val page: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val refreshing: Boolean = false,
    val anchor: HomeGridAnchor? = null,
)

/** 与搜索、相册分离的有界窗口；后台刷新成功才原子替换已经加载的页。 */
internal class FolderPagingController(
    private val scope: CoroutineScope,
    private val cache: BrowseWindowCache = BrowseWindowCache(),
    private val sourceGeneration: () -> Long = { 0L },
    private val message: (String) -> Unit = {},
    private val load: suspend (V2MediaQuery) -> V2MediaPage,
) {
    constructor(scope: CoroutineScope, load: suspend (V2MediaQuery) -> V2MediaPage) : this(scope, BrowseWindowCache(), { 0L }, {}, load)
    private val mutable = MutableStateFlow(FolderBrowseState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var key: WindowKey? = null
    private val favoriteOverrides = mutableMapOf<String, Boolean>()

    fun open(folderId: String?, spec: V2SortSpec) {
        close()
        key = WindowKey(sourceGeneration(), folderId, spec, includeDescendants = false)
        val cached = if (folderId != null) cache.get(key!!) else null
        mutable.value = if (cached == null) FolderBrowseState(folderId = folderId)
            else FolderBrowseState(folderId, cached.items, cached.page, cached.total, cached.hasMore, anchor = cached.anchor)
        if (folderId != null) request(if (cached == null) 1 else cached.page, refresh = cached != null)
    }
    fun close() {
        save(); generation++; job?.cancel(); job = null; key = null
        mutable.value = FolderBrowseState()
    }
    fun invalidate() { close(); cache.clear(); favoriteOverrides.clear() }
    fun updateAnchor(anchor: HomeGridAnchor?) { mutable.value = state.value.copy(anchor = anchor); save() }
    fun loadNextPage() {
        val current = state.value
        if (current.loading || current.refreshing || !current.hasMore || current.error != null) return
        request(current.page + 1)
    }
    fun retry() {
        val current = state.value
        if (current.loading || current.refreshing || current.error == null) return
        request(if (current.page == 0) 1 else if (current.error == "刷新失败，已保留上次内容") current.page else current.page + 1,
            refresh = current.error == "刷新失败，已保留上次内容")
    }
    fun updateFavorite(mediaId: String, favorite: Boolean) {
        favoriteOverrides[mediaId] = favorite; cache.patchFavorite(mediaId, favorite)
        mutable.value = state.value.copy(items = patched(state.value.items))
    }
    private fun patched(items: List<V2Media>) = items.map { media ->
        favoriteOverrides[media.id]?.let { media.copy(isFavorite = it) } ?: media
    }
    private fun save() {
        val active = key ?: return
        val current = state.value
        if (current.page > 0 && active.sourceGeneration == sourceGeneration())
            cache.put(active, CachedWindow(current.items, current.page, current.total, active.sort, current.anchor,
                sourceGeneration = active.sourceGeneration))
    }
    private fun request(page: Int, refresh: Boolean = false) {
        val active = key ?: return
        val folder = active.folderId ?: return
        val token = generation
        val before = state.value
        mutable.value = before.copy(loading = !refresh, refreshing = refresh, error = null)
        job = scope.launch {
            try {
                val incoming = mutableListOf<V2Media>()
                var result: V2MediaPage? = null
                for (p in if (refresh) 1..page else page..page) {
                    result = load(V2MediaQuery(p, 50, folder, spec = active.sort, includeDescendants = false))
                    if (token != generation || active.sourceGeneration != sourceGeneration()) return@launch
                    check(result.page == p && result.pageSize == 50 && result.total >= 0) { "分页响应无效" }
                    incoming += result.items
                    if (!result.hasMore) break
                }
                val final = requireNotNull(result)
                val items = (if (refresh || page == 1) incoming else state.value.items + incoming).distinctBy { it.id }
                mutable.value = state.value.copy(items = patched(items), page = final.page, total = final.total,
                    hasMore = final.hasMore, loading = false, refreshing = false)
                save()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) {
                if (token == generation && active.sourceGeneration == sourceGeneration()) {
                    mutable.value = state.value.copy(loading = false, refreshing = false,
                        error = if (refresh) "刷新失败，已保留上次内容" else "文件夹媒体加载失败")
                    if (refresh) message("刷新失败，已保留上次内容")
                }
            }
        }
    }
}
