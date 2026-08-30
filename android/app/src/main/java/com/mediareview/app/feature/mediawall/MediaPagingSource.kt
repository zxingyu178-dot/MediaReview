package com.mediareview.app.feature.mediawall

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.MediaSyncDto
import com.mediareview.app.feature.home.data.MediaDataSource
import kotlin.coroutines.cancellation.CancellationException

/**
 * 服务端分页合同(page/page_size/total/sync)到 Paging 3 的唯一映射点。
 *
 * - 键即服务端页码(1 起);load 与 loadSize 无关,始终按 [pageSize] 请求,
 *   保证服务端 page_size 稳定、nextKey 推进可预测。
 * - 异常(401/500/超时/断网)在本层包装为 LoadResult.Error 显式传播
 *   (CancellationException 原样上抛,不得吞掉取消);
 *   UI 从 LoadState 统一给出中文离线/重试状态。
 * - 不在内存持有全部行:任何时刻只保留当前页与缓存页。
 */
class MediaPagingSource(
    private val dataSource: MediaDataSource,
    private val query: MediaQuery,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val onSyncLoaded: (MediaSyncDto) -> Unit = {},
) : PagingSource<Int, MediaSummary>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaSummary> =
        try {
            loadPage(params)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            LoadResult.Error(failure)
        }

    private suspend fun loadPage(params: LoadParams<Int>): LoadResult<Int, MediaSummary> {
        val page = params.key ?: 1
        val result = dataSource.loadMedia(
            libraryId = query.libraryId,
            type = query.type,
            sortBy = query.sortBy,
            sortOrder = query.sortOrder,
            page = page,
            pageSize = pageSize,
            search = query.search,
            excludeFavorites = query.excludeFavorites,
            folderId = query.folderId,
        )
        onSyncLoaded(result.sync)
        val loaded = result.items
        val nextKey = if (loaded.isEmpty() || page.toLong() * pageSize >= result.total) {
            null
        } else {
            page + 1
        }
        return LoadResult.Page(
            data = loaded,
            prevKey = if (page > 1) page - 1 else null,
            nextKey = nextKey,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, MediaSummary>): Int? =
        state.anchorPosition?.let { anchor ->
            ((anchor / pageSize) + 1).coerceAtLeast(1)
        }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
