package com.mediareview.app.feature.mediawall

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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Task B1: 主壳激活/revision 失效与 Paging 3 重建的契约。
 * 同一 revision 只 claim 一次;Media revision 失效必须让分页流看到新数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaWallInvalidationTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun activationReloadsOnMediaRevisionClaimOnly() = runTest(main.dispatcher) {
        val repository = CountingMediaRepository()
        val store = ContentInvalidationStore()
        val viewModel = MediaWallViewModel(repository, FakeSettingsStore(), store)
        advanceUntilIdle()
        assertEquals(1, repository.libraryLoads)
        assertEquals(listOf("v1"), viewModel.pagingData.asSnapshot().map { it.media_id })
        val loadsAfterFirstSnapshot = repository.mediaLoads

        // 同 revision 内重复激活:不重复加载库,不追加页面请求
        viewModel.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(1, repository.libraryLoads)
        assertEquals(loadsAfterFirstSnapshot, repository.mediaLoads)

        // 无关 revision(Favorites):不重建分页
        store.invalidate(ContentArea.Favorites)
        viewModel.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(loadsAfterFirstSnapshot, repository.mediaLoads)

        // Media revision:claim 后重建分页,新数据可见
        repository.nextItems = listOf("v2")
        store.invalidate(ContentArea.Media)
        viewModel.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(listOf("v2"), viewModel.pagingData.asSnapshot().map { it.media_id })
        assertEquals(MediaTypeFilter.Video, repository.lastType)
    }

    @Test
    fun folderSelectionNarrowsQueryWithoutReloadStorm() = runTest(main.dispatcher) {
        val repository = CountingMediaRepository()
        repository.nextItems = listOf("f1", "f2")
        repository.folders = listOf(MediaFolderItem(folder_id = "folder-1", name = "MoviesA", count = 2))
        val viewModel = MediaWallViewModel(repository, FakeSettingsStore(), ContentInvalidationStore())
        advanceUntilIdle()

        viewModel.setFolder("folder-1")
        advanceUntilIdle()
        assertEquals("folder-1", viewModel.query.value.folderId)

        val items = viewModel.pagingData.asSnapshot()
        assertEquals(listOf("f1", "f2"), items.map { it.media_id })
        assertEquals("folder-1", repository.lastFolderId)
    }
}

internal class FakeSettingsStore : MediaWallSettingsDataSource {
    override suspend fun current() = MediaWallSettings(type = MediaTypeFilter.Video)
    override suspend fun save(value: MediaWallSettings) = Unit
}

private class CountingMediaRepository : FakeMediaDataSource() {
    var libraryLoads = 0
    var mediaLoads = 0
    var lastType = MediaTypeFilter.All
    var lastFolderId: String? = null
    var nextItems = listOf("v1")
    var folders: List<MediaFolderItem> = emptyList()

    override suspend fun loadLibraries(): List<LibraryItem> {
        libraryLoads += 1
        return emptyList()
    }

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
        mediaLoads += 1
        lastType = type
        lastFolderId = folderId
        return MediaPage(
            items = nextItems.map { MediaSummary(media_id = it, name = it, media_type = "video") },
            total = nextItems.size,
        )
    }

    override suspend fun loadMediaFolders(
        libraryId: String?,
        type: MediaTypeFilter,
        search: String?,
        excludeFavorites: Boolean,
    ): List<MediaFolderItem> = folders
}
