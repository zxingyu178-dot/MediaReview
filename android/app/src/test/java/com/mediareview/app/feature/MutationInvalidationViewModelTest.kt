package com.mediareview.app.feature

import com.mediareview.app.FakeMediaDataSource
import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.core.model.CommitResultDto
import com.mediareview.app.core.model.DeleteQueueItemDto
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.ReviewQueueItemDto
import com.mediareview.app.core.model.ReviewQueuePageDto
import com.mediareview.app.core.model.ReviewSessionDto
import com.mediareview.app.core.media.ProgressSnapshot
import com.mediareview.app.core.media.ReviewPlayable
import com.mediareview.app.core.media.ReviewPlaybackController
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.feature.deletequeue.DeleteQueueViewModel
import com.mediareview.app.feature.favorites.FavoritesViewModel
import com.mediareview.app.feature.library.LibraryViewModel
import com.mediareview.app.feature.review.ReviewViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MutationInvalidationViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun librarySaveInvalidatesLibrariesAndMediaOnlyAfterActualSuccess() = runTest(main.dispatcher) {
        val repository = MutationRepository()
        val store = ContentInvalidationStore()
        val viewModel = LibraryViewModel(repository, store)
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()
        assertEquals(0L, store.revision(mediaArea()))

        viewModel.toggle("library-2")
        viewModel.save()
        advanceUntilIdle()
        assertEquals(1L, store.revision(ContentArea.Libraries))
        assertEquals(1L, store.revision(mediaArea()))
    }

    @Test
    fun favoriteRemovalInvalidatesFavoritesAndMediaOnlyOnSuccess() = runTest(main.dispatcher) {
        val repository = MutationRepository()
        val store = ContentInvalidationStore()
        val viewModel = FavoritesViewModel(repository, store)
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        repository.favoriteSuccess = false
        viewModel.remove("media-1")
        advanceUntilIdle()
        assertEquals(0L, store.revision(ContentArea.Favorites))
        assertEquals(0L, store.revision(mediaArea()))

        repository.favoriteSuccess = true
        viewModel.remove("media-1")
        advanceUntilIdle()
        assertEquals(1L, store.revision(ContentArea.Favorites))
        assertEquals(1L, store.revision(mediaArea()))
    }

    @Test
    fun partialDeleteCommitInvalidatesEveryDownstreamAreaButAllFailedDoesNot() = runTest(main.dispatcher) {
        val repository = MutationRepository()
        val store = ContentInvalidationStore()
        val viewModel = DeleteQueueViewModel(repository, store)

        repository.commitResult = CommitResultDto(mapOf("a" to "failed"))
        viewModel.commit()
        advanceUntilIdle()
        assertEquals(0L, store.revision(ContentArea.DeleteQueue))

        repository.commitResult = CommitResultDto(mapOf("a" to "deleted", "b" to "failed", "c" to "missing"))
        viewModel.commit()
        advanceUntilIdle()
        assertEquals(1L, store.revision(ContentArea.DeleteQueue))
        assertEquals(1L, store.revision(mediaArea()))
        assertEquals(1L, store.revision(ContentArea.Favorites))
        assertEquals(1L, store.revision(ContentArea.Duplicates))
    }

    @Test
    fun reviewLikeUnlikeInvalidateFavoritesAndMediaOnlyOnSuccess() = runTest(main.dispatcher) {
        val repository = MutationRepository()
        val store = ContentInvalidationStore()
        val viewModel = ReviewViewModel(repository, NoopPlaybackController(), store)
        viewModel.load()
        advanceUntilIdle()

        repository.favoriteSuccess = false
        viewModel.onLike(0)
        advanceUntilIdle()
        assertEquals(0L, store.revision(ContentArea.Favorites))

        repository.favoriteSuccess = true
        viewModel.onLike(0)
        advanceUntilIdle()
        assertEquals(1L, store.revision(ContentArea.Favorites))
        assertEquals(1L, store.revision(mediaArea()))

        viewModel.onLike(0)
        advanceUntilIdle()
        assertEquals(2L, store.revision(ContentArea.Favorites))
        assertEquals(2L, store.revision(mediaArea()))
    }

    @Test
    fun reviewEnqueueAndUndoOnlyInvalidateDeleteQueueAfterSuccess() = runTest(main.dispatcher) {
        val repository = MutationRepository()
        val store = ContentInvalidationStore()
        val viewModel = ReviewViewModel(repository, NoopPlaybackController(), store)
        viewModel.load()
        advanceUntilIdle()

        repository.deleteSuccess = false
        viewModel.onDelete(0)
        advanceUntilIdle()
        assertEquals(0L, store.revision(ContentArea.DeleteQueue))

        repository.deleteSuccess = true
        viewModel.onDelete(0)
        advanceUntilIdle()
        viewModel.undoDelete()
        advanceUntilIdle()
        assertEquals(2L, store.revision(ContentArea.DeleteQueue))
        assertEquals(0L, store.revision(mediaArea()))
        assertEquals(0L, store.revision(ContentArea.Favorites))
    }
}

private fun mediaArea(): ContentArea = ContentArea.entries.single { it.name == "Media" }

private class MutationRepository : FakeMediaDataSource() {
    var favoriteSuccess = true
    var deleteSuccess = true
    var commitResult: CommitResultDto? = null
    override suspend fun loadLibraries() = listOf(
        LibraryItem(jellyfin_id = "library-1", selected = true),
        LibraryItem(jellyfin_id = "library-2", selected = false),
    )
    override suspend fun saveSelection(selectedIds: List<String>) = loadLibraries().map {
        it.copy(selected = it.jellyfin_id in selectedIds)
    }
    override suspend fun listFavorites() = listOf(FavoriteItemDto(media_id = "media-1"))
    override suspend fun removeFavorite(mediaId: String) = favoriteSuccess
    override suspend fun addFavorite(mediaId: String) = favoriteSuccess
    override suspend fun enqueueDelete(mediaId: String) = deleteSuccess
    override suspend fun dequeueDelete(mediaId: String) = deleteSuccess
    override suspend fun listDeleteQueue() = listOf(DeleteQueueItemDto(media_id = "media-1", status = "pending"))
    override suspend fun commitDeleteQueue() = commitResult
    override suspend fun latestReviewSession() = ReviewSessionDto(session_id = "session", status = "active")
    override suspend fun reviewQueue(sessionId: String, page: Int, pageSize: Int) = ReviewQueuePageDto(
        items = listOf(
            ReviewQueueItemDto(
                index = 0,
                media = MediaSummary(media_id = "media-1", media_type = "video"),
            ),
        ),
        total = 1,
    )
}

private class NoopPlaybackController : ReviewPlaybackController {
    override fun settle(index: Int, current: ReviewPlayable) = Unit
    override fun prepareNext(index: Int, item: ReviewPlayable?) = Unit
    override fun snapshotFor(mediaId: String): ProgressSnapshot? = null
    override fun onSwipeStarted() = Unit
    override fun deactivateReview() = Unit
}
