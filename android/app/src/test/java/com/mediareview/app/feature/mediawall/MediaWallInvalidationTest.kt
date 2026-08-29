package com.mediareview.app.feature.mediawall

import com.mediareview.app.FakeMediaDataSource
import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.core.datastore.MediaWallSettings
import com.mediareview.app.core.datastore.MediaWallSettingsDataSource
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaPage
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

@OptIn(ExperimentalCoroutinesApi::class)
class MediaWallInvalidationTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun activationReloadsLibrariesAndCurrentQueryExactlyOncePerMediaRevision() = runTest(main.dispatcher) {
        val repository = CountingMediaRepository()
        val store = ContentInvalidationStore()
        val viewModel = MediaWallViewModel(repository, FakeSettingsStore(), store)
        advanceUntilIdle()
        assertEquals(1, repository.libraryLoads)
        assertEquals(1, repository.mediaLoads)

        viewModel.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(1, repository.mediaLoads)

        store.invalidate(ContentArea.Favorites)
        viewModel.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(1, repository.mediaLoads)

        store.invalidate(ContentArea.Media)
        viewModel.loadIfNeeded()
        viewModel.loadIfNeeded()
        advanceUntilIdle()
        assertEquals(2, repository.libraryLoads)
        assertEquals(2, repository.mediaLoads)
        assertEquals(MediaTypeFilter.Video, repository.lastType)
    }
}

private class FakeSettingsStore : MediaWallSettingsDataSource {
    override suspend fun current() = MediaWallSettings(type = MediaTypeFilter.Video)
    override suspend fun save(value: MediaWallSettings) = Unit
}

private class CountingMediaRepository : FakeMediaDataSource() {
    var libraryLoads = 0
    var mediaLoads = 0
    var lastType = MediaTypeFilter.All
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
    ): MediaPage {
        mediaLoads += 1
        lastType = type
        return MediaPage()
    }
}
