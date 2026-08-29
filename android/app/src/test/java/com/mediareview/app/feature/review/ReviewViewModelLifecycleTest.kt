package com.mediareview.app.feature.review

import com.mediareview.app.FakeMediaDataSource
import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.core.media.ProgressSnapshot
import com.mediareview.app.core.media.ReviewPlayable
import com.mediareview.app.core.media.ReviewPlaybackController
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.PlaybackInfoDto
import com.mediareview.app.core.model.ReviewQueueItemDto
import com.mediareview.app.core.model.ReviewQueuePageDto
import com.mediareview.app.core.model.ReviewSessionDto
import com.mediareview.app.core.ui.ContentInvalidationStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelLifecycleTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun deactivationInvalidatesSuspendedSettleAndNewSettleCanPlay() = runTest(main.dispatcher) {
        val repository = SuspendedPlaybackRepository()
        val playback = RecordingPlaybackController()
        val viewModel = ReviewViewModel(repository, playback, ContentInvalidationStore())

        viewModel.load()
        advanceUntilIdle()
        viewModel.onSettled(0)
        runCurrent()
        repository.started.await()

        viewModel.onRootDeactivated()
        repository.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, playback.deactivations)
        assertEquals(0, playback.settles)

        repository.suspendPlayback = false
        viewModel.onSettled(0)
        advanceUntilIdle()
        assertEquals(1, playback.settles)
    }
}

private class SuspendedPlaybackRepository : FakeMediaDataSource() {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var suspendPlayback = true

    override suspend fun latestReviewSession() = ReviewSessionDto(
        session_id = "session",
        status = "active",
        total_count = 1,
    )

    override suspend fun reviewQueue(sessionId: String, page: Int, pageSize: Int) =
        ReviewQueuePageDto(
            items = listOf(
                ReviewQueueItemDto(
                    index = 0,
                    media = MediaSummary(media_id = "video", media_type = "video"),
                ),
            ),
            total = 1,
        )

    override suspend fun loadPlayback(mediaId: String): PlaybackInfoDto {
        if (suspendPlayback) {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        return PlaybackInfoDto(media_id = mediaId, stream_url = "https://example.test/video.mp4")
    }
}

private class RecordingPlaybackController : ReviewPlaybackController {
    var settles = 0
    var deactivations = 0
    override fun settle(index: Int, current: ReviewPlayable) { settles += 1 }
    override fun prepareNext(index: Int, item: ReviewPlayable?) = Unit
    override fun snapshotFor(mediaId: String): ProgressSnapshot? = null
    override fun onSwipeStarted() = Unit
    override fun deactivateReview() { deactivations += 1 }
}
