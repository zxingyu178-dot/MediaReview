package com.mediareview.app.feature.v2.viewer.state

import com.mediareview.app.feature.v2.model.*
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
) {
    val mediaIds: List<String> get() = items.map { it.id }
}

/** 相册页面与 Viewer 共用的增量窗口；请求失败绝不伪造空页。 */
class AlbumPagingController(
    private val scope: CoroutineScope,
    private val fetch: suspend (String, Int, Int, V2SortSpec) -> V2MediaPage,
    private val message: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<AlbumPagingState?>(null)
    val state: StateFlow<AlbumPagingState?> = mutableState
    private var generation = 0L
    private var job: Job? = null
    private var lastAttemptIndex: Int? = null
    fun open(albumId: String, spec: V2SortSpec, items: List<V2Media> = emptyList(), total: Int = 0, loadedPage: Int = 0): Long {
        invalidate()
        val images = items.filter { it.type == V2MediaType.IMAGE }.distinctBy { it.id }
        mutableState.value = AlbumPagingState(albumId, spec, images, total, loadedPage,
            status = if (loadedPage == 0) AlbumLoadStatus.Loading else if (total == 0) AlbumLoadStatus.Empty else AlbumLoadStatus.Ready)
        val session = generation
        if (loadedPage == 0) load(session)
        return session
    }
    fun close(session: Long) { if (current(session)) invalidate() }
    fun invalidate() {
        generation++
        job?.cancel()
        job = null
        lastAttemptIndex = null
        mutableState.value = null
    }
    fun nearEnd(session: Long, index: Int) {
        val state = mutableState.value ?: return
        if (!current(session) || state.status != AlbumLoadStatus.Ready || state.loadingMore ||
            state.items.size >= state.total || index < state.items.size - 3 || index == lastAttemptIndex) return
        lastAttemptIndex = index
        load(session)
    }
    fun retry(session: Long) {
        val state = mutableState.value ?: return
        if (current(session) && (state.status == AlbumLoadStatus.Error || state.moreError != null) && !state.loadingMore) load(session)
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
                // Only an explicit successful total=0 means Empty. An inconsistent window is retryable.
                check(page.total >= 0 && (page.total == 0 || incoming.isNotEmpty()))
                mutableState.value = before.copy(
                    items = (before.items + incoming).distinctBy { it.id }, total = page.total,
                    loadedPage = before.loadedPage + 1,
                    status = if (first && page.total == 0) AlbumLoadStatus.Empty else AlbumLoadStatus.Ready,
                    loadingMore = false, moreError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (current(session)) {
                    mutableState.value = before.copy(status = if (first) AlbumLoadStatus.Error else AlbumLoadStatus.Ready,
                        loadingMore = false, moreError = if (first) null else "加载更多失败 · 重试")
                    if (!first) message("加载更多失败，请重试")
                }
            } finally {
                if (current(session) && mutableState.value?.loadingMore == true) {
                    mutableState.value = mutableState.value?.copy(loadingMore = false)
                }
            }
        }
    }
    private fun current(session: Long) = generation == session && mutableState.value != null
}
