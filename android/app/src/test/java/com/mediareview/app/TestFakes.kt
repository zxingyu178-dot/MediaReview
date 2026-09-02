package com.mediareview.app

import com.mediareview.app.core.model.CommitResultDto
import com.mediareview.app.core.model.DeleteCommitPrepDto
import com.mediareview.app.core.model.DeleteQueueItemDto
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaFolderItem
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.PlaybackInfoDto
import com.mediareview.app.core.model.ReviewQueuePageDto
import com.mediareview.app.core.model.ReviewSessionDto
import com.mediareview.app.feature.home.data.MediaDataSource
import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

open class FakeMediaDataSource : MediaDataSource {
    override suspend fun loadLibraries(): List<LibraryItem> = emptyList()
    override suspend fun saveSelection(selectedIds: List<String>): List<LibraryItem> = emptyList()
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
    ): MediaPage = MediaPage()
    override suspend fun loadMediaFolders(
        libraryId: String?,
        type: MediaTypeFilter,
        search: String?,
        excludeFavorites: Boolean,
    ): List<MediaFolderItem> = emptyList()
    override suspend fun loadPlayback(mediaId: String): PlaybackInfoDto? = null
    override suspend fun createReviewSession(): ReviewSessionDto? = null
    override suspend fun latestReviewSession(): ReviewSessionDto? = null
    override suspend fun reviewQueue(sessionId: String, page: Int, pageSize: Int): ReviewQueuePageDto =
        ReviewQueuePageDto()
    override suspend fun markSeen(sessionId: String, mediaId: String) = Unit
    override suspend fun addFavorite(mediaId: String): Boolean = false
    override suspend fun removeFavorite(mediaId: String): Boolean = false
    override suspend fun enqueueDelete(mediaId: String): Boolean = false
    override suspend fun dequeueDelete(mediaId: String): Boolean = false
    override suspend fun setReviewPosition(sessionId: String, index: Int) = Unit
    override suspend fun listFavorites(): List<FavoriteItemDto> = emptyList()
    override suspend fun listDeleteQueue(): List<DeleteQueueItemDto> = emptyList()
    override suspend fun prepareDeleteCommit(): DeleteCommitPrepDto? =
        DeleteCommitPrepDto(nonce = "test-nonce")
    override suspend fun commitDeleteQueue(nonce: String): CommitResultDto? = null
    override suspend fun loadDuplicatesExact(): List<DuplicateGroupDto> = emptyList()
    override suspend fun loadDuplicatesSimilar(): List<DuplicateGroupDto> = emptyList()
    override suspend fun reportProgress(mediaId: String, positionMs: Long, isPaused: Boolean) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
