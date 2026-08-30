package com.mediareview.app.feature.mediawall

import androidx.paging.PagingSource
import androidx.paging.testing.asSnapshot
import com.mediareview.app.FakeMediaDataSource
import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.core.datastore.MediaWallSettings
import com.mediareview.app.core.datastore.MediaWallSettingsDataSource
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaFolderItem
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Task B1: 媒体墙 Paging 3 合同。
 *
 * MediaPagingSource 必须把服务端 page/page_size/total/sync 映射为 LoadResult,
 * 异常(401/500/超时)必须以 LoadResult.Error 传播;ViewModel 必须为每个新查询
 * 创建新 Pager(flatMapLatest 取消旧流),cachedIn 是唯一保留的页缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaPagingSourceTest {
    @get:Rule val main = MainDispatcherRule()

    private class PagingRepository : FakeMediaDataSource() {
        val pages = mutableMapOf<Int, MediaPage>()
        var error: Throwable? = null
        val pageRequests = mutableListOf<Int>()
        var lastLibraryId: String? = null
        var lastType: MediaTypeFilter = MediaTypeFilter.All
        var lastSortBy: SortField = SortField.Name
        var lastSortOrder: SortOrder = SortOrder.Asc
        var lastSearch: String? = null
        var lastExcludeFavorites = false
        var lastFolderId: String? = null

        override suspend fun loadMedia(
            libraryId: String?,
            type: MediaTypeFilter,
            sortBy: SortField,
            sortOrder: SortOrder,
            page: Int,
            pageSize: Int,
            search: String?,
            excludeFavorites: Boolean,
            folderId: String?,
        ): MediaPage {
            pageRequests += page
            lastLibraryId = libraryId
            lastType = type
            lastSortBy = sortBy
            lastSortOrder = sortOrder
            lastSearch = search
            lastExcludeFavorites = excludeFavorites
            lastFolderId = folderId
            error?.let { throw it }
            return pages[page] ?: MediaPage()
        }
    }

    private fun mediaPage(ids: List<String>, total: Int, page: Int, pageSize: Int = 2) =
        MediaPage(
            items = ids.map { MediaSummary(media_id = it, name = it, media_type = "video") },
            total = total,
            page = page,
            page_size = pageSize,
        )

    private suspend fun load(
        source: MediaPagingSource,
        key: Int?,
    ): PagingSource.LoadResult<Int, MediaSummary> = source.load(
        PagingSource.LoadParams.Refresh(key = key, loadSize = 2, placeholdersEnabled = false),
    )

    @Test
    fun firstPageMapsItemsTotalAndNextKey() = runTest {
        val repository = PagingRepository()
        repository.pages[1] = mediaPage(listOf("a", "b"), total = 5, page = 1)
            .copy(sync = com.mediareview.app.core.model.MediaSyncDto(state = "running"))
        var syncSeen: String? = null
        val source = MediaPagingSource(
            repository,
            MediaQuery(libraryId = "lib-1"),
            pageSize = 2,
            onSyncLoaded = { syncSeen = it.state },
        )

        val result = load(source, null) as PagingSource.LoadResult.Page

        assertEquals(listOf("a", "b"), result.data.map { it.media_id })
        assertNull(result.prevKey)
        assertEquals(2, result.nextKey)
        assertEquals("running", syncSeen)
    }

    @Test
    fun appendPageLoadsByKeyAndStopsAtTotal() = runTest {
        val repository = PagingRepository()
        repository.pages[2] = mediaPage(listOf("c", "d"), total = 4, page = 2)
        val source = MediaPagingSource(repository, MediaQuery(), pageSize = 2)

        val page2 = load(source, 2) as PagingSource.LoadResult.Page
        assertEquals(listOf("c", "d"), page2.data.map { it.media_id })
        assertEquals(1, page2.prevKey)
        assertNull(page2.nextKey)

        // 乱序响应:先加载第 2 页再加载第 1 页,各自映射正确数据
        repository.pages[1] = mediaPage(listOf("a", "b"), total = 4, page = 1)
        val page1 = load(source, 1) as PagingSource.LoadResult.Page
        assertEquals(listOf("a", "b"), page1.data.map { it.media_id })
    }

    @Test
    fun emptyIndexYieldsEmptyPageWithoutNextKey() = runTest {
        val repository = PagingRepository()
        repository.pages[1] = MediaPage()
        val source = MediaPagingSource(repository, MediaQuery(), pageSize = 2)

        val result = load(source, null) as PagingSource.LoadResult.Page

        assertTrue(result.data.isEmpty())
        assertNull(result.nextKey)
    }

    @Test
    fun dataSourceFailuresPropagateAsLoadResultError() = runTest {
        val failures = listOf(
            "HTTP 401 未授权" to RuntimeException("HTTP 401 未授权"),
            "HTTP 500" to RuntimeException("HTTP 500 服务器错误"),
            "timeout" to java.util.concurrent.TimeoutException("超时"),
        )
        for ((label, failure) in failures) {
            val repository = PagingRepository()
            repository.error = failure
            val source = MediaPagingSource(repository, MediaQuery(), pageSize = 2)
            val result = load(source, null)
            assertTrue(label, result is PagingSource.LoadResult.Error)
        }
    }

    @Test
    fun cancellationExceptionsPropagateWithoutWrapping() = runTest {
        val repository = PagingRepository()
        repository.error = kotlinx.coroutines.CancellationException("分页加载已取消")
        val source = MediaPagingSource(repository, MediaQuery(), pageSize = 2)
        val failure = runCatching { load(source, null) }.exceptionOrNull()
        assertTrue("取消不得被包装成 LoadResult.Error", failure is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun queryParametersPassThroughToDataSource() = runTest {
        val repository = PagingRepository()
        repository.pages[1] = mediaPage(listOf("a"), total = 1, page = 1)
        val source = MediaPagingSource(
            repository,
            MediaQuery(
                libraryId = "lib-7",
                type = MediaTypeFilter.Image,
                sortBy = SortField.Size,
                sortOrder = SortOrder.Desc,
                search = "abc",
                excludeFavorites = true,
                folderId = "folder-9",
            ),
            pageSize = 2,
        )

        load(source, null)

        assertEquals("lib-7", repository.lastLibraryId)
        assertEquals(MediaTypeFilter.Image, repository.lastType)
        assertEquals(SortField.Size, repository.lastSortBy)
        assertEquals(SortOrder.Desc, repository.lastSortOrder)
        assertEquals("abc", repository.lastSearch)
        assertTrue(repository.lastExcludeFavorites)
        assertEquals("folder-9", repository.lastFolderId)
    }

    @Test
    fun refreshKeyAnchorsToClosestPage() = runTest {
        val repository = PagingRepository()
        val source = MediaPagingSource(repository, MediaQuery(), pageSize = 2)
        val state = androidx.paging.PagingState<Int, MediaSummary>(
            pages = emptyList(),
            anchorPosition = 4,
            config = androidx.paging.PagingConfig(pageSize = 2),
            leadingPlaceholderCount = 0,
        )
        assertEquals(3, source.getRefreshKey(state))
    }

    @Test
    fun viewModelCreatesNewPagerPerQueryAndOldFlowStopsLoading() = runTest(main.dispatcher) {
        val repository = PagingRepository()
        repository.pages[1] = mediaPage(listOf("a"), total = 1, page = 1)
        val store = ContentInvalidationStore()
        val viewModel = MediaWallViewModel(repository, FakeSettingsStore(), store)
        advanceUntilIdle()

        assertEquals(listOf("a"), viewModel.pagingData.asSnapshot().map { it.media_id })
        val loadsBefore = repository.pageRequests.size

        // 新查询:切库触发新 Pager;旧流被 flatMapLatest 取消,不再追加请求
        viewModel.onLibrarySelected("lib-2")
        repository.pages[1] = mediaPage(listOf("b1", "b2"), total = 2, page = 1)
        val next = viewModel.pagingData.asSnapshot()
        advanceUntilIdle()
        assertEquals(listOf("b1", "b2"), next.map { it.media_id })
        assertEquals("lib-2", repository.lastLibraryId)
        val loadsAfterSwitch = repository.pageRequests.size
        advanceTimeBy(2_000)
        advanceUntilIdle()
        assertEquals("旧查询不得继续加载", loadsAfterSwitch, repository.pageRequests.size)
        assertTrue(loadsAfterSwitch > loadsBefore)
    }

    @Test
    fun viewModelMediaRevisionRefreshRebuildsPaging() = runTest(main.dispatcher) {
        val repository = PagingRepository()
        repository.pages[1] = mediaPage(listOf("old"), total = 1, page = 1)
        val store = ContentInvalidationStore()
        val viewModel = MediaWallViewModel(repository, FakeSettingsStore(), store)
        advanceUntilIdle()
        assertEquals(listOf("old"), viewModel.pagingData.asSnapshot().map { it.media_id })

        // FinalDelete 失效推进 Media revision → 主壳 loadIfNeeded → 必须重建分页
        store.invalidate(ContentArea.Media)
        repository.pages[1] = mediaPage(listOf("fresh"), total = 1, page = 1)
        viewModel.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf("fresh"), viewModel.pagingData.asSnapshot().map { it.media_id })
    }

    @Test
    fun viewModelDebouncedSearchRebuildsQuery() = runTest(main.dispatcher) {
        val repository = PagingRepository()
        repository.pages[1] = mediaPage(listOf("a"), total = 1, page = 1)
        val viewModel = MediaWallViewModel(repository, FakeSettingsStore(), ContentInvalidationStore())
        advanceUntilIdle()

        viewModel.onSearchChange("四月")
        advanceTimeBy(399)
        assertNull("防抖窗口内不得触发新查询", viewModel.query.value.search)

        advanceTimeBy(50)
        assertEquals("四月", viewModel.query.value.search)
        advanceUntilIdle()

        // 新查询驱动新分页:数据源必须收到搜索词
        viewModel.pagingData.asSnapshot()
        assertEquals("四月", repository.lastSearch)
    }
}
