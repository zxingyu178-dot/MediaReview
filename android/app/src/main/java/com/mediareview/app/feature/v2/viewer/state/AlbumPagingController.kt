package com.mediareview.app.feature.v2.viewer.state

import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.home.AlbumWindowCache
import com.mediareview.app.feature.v2.home.CachedWindow
import com.mediareview.app.feature.v2.home.HomeGridAnchor
import com.mediareview.app.feature.v2.home.WindowKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AlbumLoadStatus { Loading, Ready, Empty, Error }

data class AlbumPagingState(
    val albumId: String,
    val sortSpec: V2SortSpec,
    val items: List<V2Media> = emptyList(),
    val total: Int = 0,
    val loadedPage: Int = 0,
    val pageSize: Int = 50,
    val status: AlbumLoadStatus = AlbumLoadStatus.Loading,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
    val refreshing: Boolean = false,
    val anchor: HomeGridAnchor? = null,
) {
    val mediaIds: List<String> get() = items.map { it.id }
}

/** 相册页面与 Viewer 共用的增量窗口；Viewer seeded open 保留既有分页语义。 */
class AlbumPagingController(
    private val scope: CoroutineScope,
    private val fetch: suspend (String, Int, Int, V2SortSpec) -> V2MediaPage,
    private val message: (String) -> Unit = {},
    private val cache: AlbumWindowCache = AlbumWindowCache(),
    private val sourceGeneration: () -> Long = { 0L },
    private val cacheEnabled: Boolean = true,
) {
    private val mutableState = MutableStateFlow<AlbumPagingState?>(null)
    val state: StateFlow<AlbumPagingState?> = mutableState
    private var generation = 0L
    private var job: Job? = null
    private var lastAttemptIndex: Int? = null
    private var key: WindowKey? = null
    private val favoriteOverrides = mutableMapOf<String, Boolean>()

    fun open(albumId: String, spec: V2SortSpec, items: List<V2Media> = emptyList(), total: Int = 0, loadedPage: Int = 0): Long {
        closeCurrent()
        key = WindowKey(sourceGeneration(), albumId, spec)
        val cached = if (cacheEnabled && loadedPage == 0) cache.get(key!!) else null
        val images = patched(items.filter { it.type == V2MediaType.IMAGE }.distinctBy { it.id })
        mutableState.value = if (cached != null) AlbumPagingState(albumId, spec, cached.items, cached.total, cached.page,
            status = if (cached.total == 0) AlbumLoadStatus.Empty else AlbumLoadStatus.Ready, anchor = cached.anchor)
        else AlbumPagingState(albumId, spec, images, total, loadedPage,
            status = if (loadedPage == 0) AlbumLoadStatus.Loading else if (total == 0) AlbumLoadStatus.Empty else AlbumLoadStatus.Ready)
        val session = generation
        if (cached != null) revalidate(session) else if (loadedPage == 0) load(session)
        return session
    }
    fun close(session: Long) { if (current(session)) closeCurrent() }
    private fun closeCurrent() {
        save()
        generation++
        job?.cancel(); job = null; lastAttemptIndex = null
        mutableState.value = null; key = null
    }
    /** 显式 source/library/delete/refresh 失效，不同于普通关闭保存窗口。 */
    fun invalidate() {
        closeCurrent(); cache.clear(); favoriteOverrides.clear()
    }
    fun updateAnchor(session: Long, anchor: HomeGridAnchor?) {
        if (current(session)) { mutableState.value = mutableState.value?.copy(anchor = anchor); save() }
    }
    fun updateFavorite(id: String, favorite: Boolean) {
        favoriteOverrides[id] = favorite
        cache.patchFavorite(id, favorite)
        mutableState.value = mutableState.value?.let { it.copy(items = patched(it.items)) }
    }
    private fun patched(items: List<V2Media>) = items.map { media ->
        favoriteOverrides[media.id]?.let { media.copy(isFavorite = it) } ?: media
    }
    private fun save() {
        val state = mutableState.value ?: return
        val activeKey = key ?: return
        if (cacheEnabled && state.loadedPage > 0 && activeKey.sourceGeneration == sourceGeneration())
            cache.put(activeKey, CachedWindow(state.items, state.loadedPage, state.total, state.sortSpec,
                state.anchor, sourceGeneration = activeKey.sourceGeneration))
    }
    fun nearEnd(session: Long, index: Int) {
        val state = mutableState.value ?: return
        if (!current(session) || state.status != AlbumLoadStatus.Ready || state.loadingMore || state.refreshing ||
            state.items.size >= state.total || index < state.items.size - 3 || index == lastAttemptIndex) return
        lastAttemptIndex = index; load(session)
    }
    fun retry(session: Long) {
        val state = mutableState.value ?: return
        if (!current(session) || state.loadingMore || state.refreshing) return
        if (state.moreError == "刷新失败 · 重试") revalidate(session)
        else if (state.status == AlbumLoadStatus.Error || state.moreError != null) load(session)
    }
    private fun revalidate(session: Long) {
        val before = mutableState.value ?: return
        mutableState.value = before.copy(refreshing = true, moreError = null)
        job = scope.launch {
            try {
                val refreshed = mutableListOf<V2Media>()
                var total = before.total
                var pageNumber = 0
                for (page in 1..before.loadedPage) {
                    val result = fetch(before.albumId, page, before.pageSize, before.sortSpec)
                    if (!current(session)) return@launch
                    check(result.page == page && result.pageSize == before.pageSize && result.total >= 0)
                    val incoming = result.items.filter { it.type == V2MediaType.IMAGE }
                    check(result.total == 0 || incoming.isNotEmpty())
                    refreshed += incoming; total = result.total; pageNumber = page
                    if (!result.hasMore) break
                }
                mutableState.value = before.copy(items = patched(refreshed.distinctBy { it.id }), total = total,
                    loadedPage = pageNumber, status = if (total == 0) AlbumLoadStatus.Empty else AlbumLoadStatus.Ready,
                    anchor = mutableState.value?.anchor ?: before.anchor, refreshing = false, moreError = null)
                save()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) {
                if (current(session)) {
                    mutableState.value = before.copy(items = patched(before.items), anchor = mutableState.value?.anchor ?: before.anchor,
                        refreshing = false, moreError = "刷新失败 · 重试")
                    message("刷新失败，已保留上次内容")
                }
            }
        }
    }
    private fun load(session: Long) {
        val before = mutableState.value ?: return
        val first = before.loadedPage == 0
        mutableState.value = before.copy(status = if (first) AlbumLoadStatus.Loading else before.status,
            loadingMore = !first, moreError = null)
        job = scope.launch {
            try {
                val page = fetch(before.albumId, before.loadedPage + 1, before.pageSize, before.sortSpec)
                if (!current(session)) return@launch
                val incoming = page.items.filter { it.type == V2MediaType.IMAGE }
                check(page.page == before.loadedPage + 1 && page.pageSize == before.pageSize && page.total >= 0 && (page.total == 0 || incoming.isNotEmpty()))
                mutableState.value = before.copy(items = patched((before.items + incoming).distinctBy { it.id }), total = page.total,
                    loadedPage = page.page, status = if (first && page.total == 0) AlbumLoadStatus.Empty else AlbumLoadStatus.Ready,
                    anchor = mutableState.value?.anchor ?: before.anchor, loadingMore = false, moreError = null)
                save()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) {
                if (current(session)) {
                    mutableState.value = before.copy(items = patched(before.items), status = if (first) AlbumLoadStatus.Error else AlbumLoadStatus.Ready,
                        loadingMore = false, moreError = if (first) null else "加载更多失败 · 重试")
                    if (!first) message("加载更多失败，请重试")
                }
            } finally {
                if (current(session) && mutableState.value?.loadingMore == true)
                    mutableState.value = mutableState.value?.copy(loadingMore = false)
            }
        }
    }
    private fun current(session: Long) = generation == session && mutableState.value != null && key?.sourceGeneration == sourceGeneration()
}
