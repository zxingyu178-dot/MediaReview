package com.mediareview.app.feature

import com.mediareview.app.FakeMediaDataSource
import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.core.model.CommitResultDto
import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.core.model.DeleteQueueItemDto
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.PlaybackInfoDto
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
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
    fun deleteCommitUsesServerOutcomeContractForSummaryAndRevisions() = runTest(main.dispatcher) {
        // Server 真实合同:success/missing/failed(server/app/services/delete_queue.py)。
        // changed=true 才推进 DeleteQueue/Media/Favorites/Duplicates 四个 revision;
        // successful 只计 success;missing 是"已不存在"而非删除成功。
        val cases = listOf(
            Triple(mapOf("a" to "success"), "删除成功 1 项", true),
            Triple(mapOf("a" to "failed"), "删除成功 0 项,失败 1 项", false),
            Triple(emptyMap<String, String>(), "删除完成", false),
            Triple(mapOf("a" to "missing"), "删除成功 0 项,缺失 1 项", true),
            Triple(
                mapOf("a" to "success", "b" to "missing", "c" to "failed"),
                "删除成功 1 项,缺失 1 项,失败 1 项",
                true,
            ),
            Triple(mapOf("a" to "unknown-wire"), "删除成功 0 项,失败 1 项", false),
        )
        for ((outcome, expectedSummary, expectedChanged) in cases) {
            val repository = MutationRepository()
            val store = ContentInvalidationStore()
            val viewModel = DeleteQueueViewModel(repository, store)

            repository.commitResult = CommitResultDto(outcome)
            viewModel.commit()
            advanceUntilIdle()

            assertEquals("outcome=$outcome 摘要", expectedSummary, viewModel.ui.value.commitResult)
            val expectedRevision = if (expectedChanged) 1L else 0L
            assertEquals("outcome=$outcome DeleteQueue", expectedRevision, store.revision(ContentArea.DeleteQueue))
            assertEquals("outcome=$outcome Media", expectedRevision, store.revision(mediaArea()))
            assertEquals("outcome=$outcome Favorites", expectedRevision, store.revision(ContentArea.Favorites))
            assertEquals("outcome=$outcome Duplicates", expectedRevision, store.revision(ContentArea.Duplicates))
        }
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

    @Test
    fun deleteOutcomeParserMapsWireValues() {
        assertEquals(DeleteOutcomeStatus.Success, DeleteOutcomeStatus.fromWire("success"))
        assertEquals(DeleteOutcomeStatus.Success, DeleteOutcomeStatus.fromWire("SUCCESS"))
        assertEquals(DeleteOutcomeStatus.Missing, DeleteOutcomeStatus.fromWire("missing"))
        assertEquals(DeleteOutcomeStatus.Failed, DeleteOutcomeStatus.fromWire("failed"))
        assertEquals(DeleteOutcomeStatus.Unknown, DeleteOutcomeStatus.fromWire("deleted"))
        assertEquals(DeleteOutcomeStatus.Unknown, DeleteOutcomeStatus.fromWire(""))
        // changed 矩阵:success/missing 推进下游;failed/未知不推进
        assertEquals(true, DeleteOutcomeStatus.Success.changed)
        assertEquals(true, DeleteOutcomeStatus.Missing.changed)
        assertEquals(false, DeleteOutcomeStatus.Failed.changed)
        assertEquals(false, DeleteOutcomeStatus.Unknown.changed)
        // successful 矩阵:只有 success 计删除成功
        assertEquals(true, DeleteOutcomeStatus.Success.successful)
        assertEquals(false, DeleteOutcomeStatus.Missing.successful)
        assertEquals(false, DeleteOutcomeStatus.Failed.successful)
    }

    @Test
    fun reviewRootDeactivationTokenGuardsInFlightSettleResumption() = runTest(main.dispatcher) {
        val repository = MutationRepository()
        val store = ContentInvalidationStore()
        val playback = CountingPlaybackController()
        val viewModel = ReviewViewModel(repository, playback, store)
        viewModel.load()
        advanceUntilIdle()

        // settle 在途:loadPlayback 挂起在不可取消停车点(模拟网络响应尚未返回)
        repository.parkPlaybackLookups = true
        viewModel.onSettled(0)
        advanceUntilIdle()
        assertEquals(1, repository.lookupsStarted)
        assertEquals(0, playback.settles)

        // 主壳切离批阅根:token reset → job cancel → deactivate;旧 lookup 仍被挂起
        viewModel.onRootDeactivated()
        advanceUntilIdle()
        assertEquals(1, playback.deactivations)

        // 响应在取消之后才到达:已取消协程恢复后必须被失效 token 拦截,不得落位或播放
        repository.releaseParkedLookup()
        advanceUntilIdle()
        assertEquals("旧 settle 不得落地", 0, playback.settles)
        assertEquals("旧 settle 不得触发播放", 0, playback.plays)
        assertEquals(0, playback.prepares)

        // 重新 settle:新令牌必须能正常落位并播放
        repository.parkPlaybackLookups = false
        viewModel.onSettled(0)
        advanceUntilIdle()
        assertEquals(1, playback.settles)
        assertEquals(1, playback.plays)
    }
}

private fun mediaArea(): ContentArea = ContentArea.entries.single { it.name == "Media" }

private class MutationRepository : FakeMediaDataSource() {
    var favoriteSuccess = true
    var deleteSuccess = true
    var commitResult: CommitResultDto? = null

    // settle 竞态控制:挂起在不可取消停车点,release 后即使协程已被取消也会恢复,
    // 用于复现"响应在取消后到达"的生产窗口(token reset 是唯一拦截手段)。
    var parkPlaybackLookups = false
    var lookupsStarted = 0
    private var parkedLookup: Continuation<PlaybackInfoDto>? = null

    override suspend fun loadPlayback(mediaId: String): PlaybackInfoDto? {
        lookupsStarted += 1
        if (!parkPlaybackLookups) {
            return PlaybackInfoDto(media_id = mediaId, stream_url = "stream://$mediaId")
        }
        return suspendCoroutine { cont -> parkedLookup = cont }
    }

    fun releaseParkedLookup() {
        val cont = parkedLookup ?: return
        parkedLookup = null
        cont.resume(PlaybackInfoDto(media_id = "media-1", stream_url = "stream://media-1"))
    }
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

private class CountingPlaybackController : ReviewPlaybackController {
    var settles = 0
    var plays = 0
    var prepares = 0
    var deactivations = 0

    override fun settle(index: Int, current: ReviewPlayable) {
        settles += 1
        // 播放语义:settle 携带非空 stream_url 才代表真正开始播放
        if (!current.streamUrl.isNullOrBlank()) plays += 1
    }

    override fun prepareNext(index: Int, item: ReviewPlayable?) {
        prepares += 1
    }

    override fun snapshotFor(mediaId: String): ProgressSnapshot? = null
    override fun onSwipeStarted() = Unit
    override fun deactivateReview() {
        deactivations += 1
    }
}
