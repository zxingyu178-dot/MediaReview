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
)

/** 与首页、搜索、相册分离的有界窗口，点击目录只查询该目录的直接媒体。 */
internal class FolderPagingController(
    private val scope: CoroutineScope,
    private val load: suspend (V2MediaQuery) -> V2MediaPage,
) {
    private val mutable = MutableStateFlow(FolderBrowseState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var sort = V2SortSpec()

    fun open(folderId: String?, spec: V2SortSpec) {
        close()
        sort = spec
        mutable.value = FolderBrowseState(folderId = folderId)
        if (folderId != null) request(1)
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        mutable.value = FolderBrowseState()
    }

    fun loadNextPage() {
        val current = state.value
        if (current.loading || !current.hasMore || current.error != null) return
        request(current.page + 1)
    }

    fun retry() {
        val current = state.value
        if (current.loading || current.error == null) return
        request(if (current.page == 0) 1 else current.page + 1)
    }

    fun updateFavorite(mediaId: String, favorite: Boolean) {
        mutable.value = state.value.copy(items = state.value.items.map {
            if (it.id == mediaId) it.copy(isFavorite = favorite) else it
        })
    }

    private fun request(page: Int) {
        val folder = state.value.folderId ?: return
        val token = generation
        mutable.value = state.value.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val result = load(V2MediaQuery(page, 50, folder, spec = sort, includeDescendants = false))
                if (token != generation) return@launch
                check(result.page == page && result.pageSize == 50 && result.total >= 0) { "分页响应无效" }
                val items = (if (page == 1) result.items else state.value.items + result.items).distinctBy { it.id }
                mutable.value = state.value.copy(items = items, page = page, total = result.total, hasMore = result.hasMore)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (token == generation) mutable.value = state.value.copy(error = "文件夹媒体加载失败")
            } finally {
                if (token == generation) mutable.value = state.value.copy(loading = false)
            }
        }
    }
}
