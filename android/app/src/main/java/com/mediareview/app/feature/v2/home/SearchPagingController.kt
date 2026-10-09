package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SearchUiState(
    val query: String = "",
    val items: List<V2Media> = emptyList(),
    val page: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loadingFirstPage: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

internal class SearchPagingController(
    private val scope: CoroutineScope,
    private val load: suspend (V2MediaQuery) -> V2MediaPage,
) {
    private val mutable = MutableStateFlow(SearchUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun updateQuery(query: String) {
        if (query == state.value.query) return
        cancelRequest()
        mutable.value = SearchUiState(query = query)
        if (query.isNotBlank()) request(1, debounce = true)
    }

    fun loadNextPage() {
        val current = state.value
        if (current.query.isBlank() || current.loadingFirstPage || current.loadingMore ||
            !current.hasMore || current.error != null) return
        request(current.page + 1)
    }

    fun nearEnd(index: Int) {
        val current = state.value
        if (current.items.isNotEmpty() && index >= (current.items.size - 16).coerceAtLeast(0)) loadNextPage()
    }

    fun retry() {
        val current = state.value
        if (current.query.isBlank() || current.loadingFirstPage || current.loadingMore || current.error == null) return
        request(if (current.page == 0) 1 else current.page + 1)
    }

    fun clear() {
        cancelRequest()
        mutable.value = SearchUiState()
    }

    fun updateFavorite(mediaId: String, favorite: Boolean) {
        mutable.value = state.value.copy(items = state.value.items.map {
            if (it.id == mediaId) it.copy(isFavorite = favorite) else it
        })
    }

    private fun cancelRequest() {
        generation++
        job?.cancel()
        job = null
    }

    private fun request(page: Int, debounce: Boolean = false) {
        val token = generation
        val query = state.value.query.trim()
        mutable.value = state.value.copy(loadingFirstPage = page == 1, loadingMore = page > 1, error = null)
        job = scope.launch {
            try {
                if (debounce) delay(300)
                val result = load(V2MediaQuery(page = page, pageSize = 50, search = query))
                if (token != generation) return@launch
                check(result.page == page && result.pageSize == 50 && result.total >= 0) { "分页响应无效" }
                val items = (if (page == 1) result.items else state.value.items + result.items).distinctBy { it.id }
                mutable.value = state.value.copy(items = items, page = page, total = result.total, hasMore = result.hasMore)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (token == generation) mutable.value = state.value.copy(error = if (page == 1) "搜索失败" else "加载更多失败")
            } finally {
                if (token == generation) mutable.value = state.value.copy(loadingFirstPage = false, loadingMore = false)
            }
        }
    }
}
