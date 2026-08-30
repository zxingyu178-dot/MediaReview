package com.mediareview.app.ui.shell

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mediareview.app.core.datastore.MediaWallSettings
import com.mediareview.app.core.datastore.MediaWallSettingsDataSource
import com.mediareview.app.core.media.ProgressSnapshot
import com.mediareview.app.core.media.ReviewPlayable
import com.mediareview.app.core.media.ReviewPlaybackController
import com.mediareview.app.core.model.CommitResultDto
import com.mediareview.app.core.model.DeleteQueueItemDto
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaFolderItem
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.PlaybackInfoDto
import com.mediareview.app.core.model.ReviewQueueItemDto
import com.mediareview.app.core.model.ReviewQueuePageDto
import com.mediareview.app.core.model.ReviewSessionDto
import com.mediareview.app.core.ui.ContentArea
import com.mediareview.app.core.ui.ContentInvalidationStore
import com.mediareview.app.feature.connect.data.ConnectionState
import com.mediareview.app.feature.deletequeue.DeleteQueueViewModel
import com.mediareview.app.feature.duplicates.DuplicatesViewModel
import com.mediareview.app.feature.favorites.FavoritesViewModel
import com.mediareview.app.feature.home.data.MediaDataSource
import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder
import com.mediareview.app.feature.library.LibraryViewModel
import com.mediareview.app.feature.mediawall.MediaWallViewModel
import com.mediareview.app.feature.review.ReviewViewModel
import com.mediareview.app.ui.theme.MediaReviewTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainShellProductionIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun productionShellWiresDeactivationMutationAndPreciseMediaReload() {
        val repository = ShellRepository()
        val invalidations = ContentInvalidationStore()
        val playback = ShellPlaybackController()
        val media = MediaWallViewModel(repository, ShellSettingsStore(), invalidations)
        val review = ReviewViewModel(repository, playback, invalidations)
        val favorites = FavoritesViewModel(repository, invalidations)
        val libraries = LibraryViewModel(repository, invalidations)
        val deleteQueue = DeleteQueueViewModel(repository, invalidations)
        val duplicates = DuplicatesViewModel(repository, invalidations)

        compose.setContent {
            MediaReviewTheme {
                MainShellScreen(
                    onOpenSettings = {},
                    onOpenLibrary = {},
                    onOpenDeleteQueue = {},
                    onOpenDuplicates = {},
                    onOpenMedia = {},
                    connectionOverride = ConnectionState(),
                    mediaWallViewModel = media,
                    reviewViewModel = review,
                    favoritesViewModel = favorites,
                    libraryViewModel = libraries,
                    deleteQueueViewModel = deleteQueue,
                    duplicatesViewModel = duplicates,
                    rootContentOverride = { root ->
                        if (root == MainRoot.Favorites) {
                            Button(onClick = { favorites.remove("media-1") }) {
                                Text("执行取消收藏")
                            }
                        } else {
                            Text("${root.label}生产根")
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(1, repository.mediaLoads)

        compose.onNodeWithContentDescription("批阅导航").performClick()
        compose.onNodeWithContentDescription("收藏导航").performClick()
        compose.waitForIdle()
        assertEquals(1, playback.deactivations)

        compose.onNodeWithText("执行取消收藏").performClick()
        compose.waitForIdle()
        assertEquals(1L, invalidations.revision(ContentArea.Favorites))
        assertEquals(1L, invalidations.revision(ContentArea.Media))

        compose.onNodeWithContentDescription("媒体导航").performClick()
        compose.waitForIdle()
        assertEquals(2, repository.mediaLoads)

        compose.onNodeWithContentDescription("整理导航").performClick()
        compose.onNodeWithContentDescription("媒体导航").performClick()
        compose.waitForIdle()
        assertEquals(2, repository.mediaLoads)
    }

    /**
     * F3 集成门禁:生产主壳 + 真实 ReviewViewModel + 真实切根失活路径。
     *
     * settle 在途时切离批阅根,旧 lookup 在取消之后才返回:
     * 失效 token 必须拦截旧 settle/play;重入批阅后新 token 必须能 settle/play。
     * lookup 用不可取消停车点(非 CompletableDeferred):可取消 await 会被 cancel()
     * 直接杀死,无法复现"响应晚于取消到达"的生产竞态窗口。
     */
    @Test
    fun productionShellGuardsInFlightSettleAcrossRootSwitch() {
        val repository = ShellRepository()
        val invalidations = ContentInvalidationStore()
        val playback = ShellPlaybackController()
        val media = MediaWallViewModel(repository, ShellSettingsStore(), invalidations)
        val review = ReviewViewModel(repository, playback, invalidations)
        val favorites = FavoritesViewModel(repository, invalidations)
        val libraries = LibraryViewModel(repository, invalidations)
        val deleteQueue = DeleteQueueViewModel(repository, invalidations)
        val duplicates = DuplicatesViewModel(repository, invalidations)

        compose.setContent {
            MediaReviewTheme {
                MainShellScreen(
                    onOpenSettings = {},
                    onOpenLibrary = {},
                    onOpenDeleteQueue = {},
                    onOpenDuplicates = {},
                    onOpenMedia = {},
                    connectionOverride = ConnectionState(),
                    mediaWallViewModel = media,
                    reviewViewModel = review,
                    favoritesViewModel = favorites,
                    libraryViewModel = libraries,
                    deleteQueueViewModel = deleteQueue,
                    duplicatesViewModel = duplicates,
                    rootContentOverride = { root ->
                        if (root == MainRoot.Review) {
                            Button(onClick = { review.onSettled(0) }) {
                                Text("执行批阅落位")
                            }
                        } else {
                            Text("${root.label}生产根")
                        }
                    },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("批阅导航").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("执行批阅落位").performClick()
        assertTrue("playback lookup 必须已进入挂起", repository.lookupStarted.await(5, TimeUnit.SECONDS))

        compose.onNodeWithContentDescription("收藏导航").performClick()
        compose.waitForIdle()
        // 失活已完成(reset+cancel+deactivate);此刻释放旧 lookup,响应晚于取消到达
        repository.releaseHeldLookup()
        compose.waitForIdle()
        assertEquals(1, playback.deactivations)
        assertEquals("旧 settle 不得落地", 0, playback.settles)
        assertEquals("旧 settle 不得触发播放", 0, playback.plays)
        assertEquals(0, playback.prepares)

        // 重入批阅根:settle 必须仍然可用(真实队列项仍在,新令牌生效)
        repository.lookupImmediate = true
        compose.onNodeWithContentDescription("批阅导航").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("执行批阅落位").performClick()
        compose.waitForIdle()
        assertEquals("重入后新 token 落位", 1, playback.settles)
        assertEquals("重入后新 token 播放", 1, playback.plays)
    }
}

private class ShellSettingsStore : MediaWallSettingsDataSource {
    override suspend fun current() = MediaWallSettings()
    override suspend fun save(value: MediaWallSettings) = Unit
}

private class ShellPlaybackController : ReviewPlaybackController {
    var deactivations = 0
    var settles = 0
    var plays = 0
    var prepares = 0
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
    override fun deactivateReview() { deactivations += 1 }
}

private class ShellRepository : MediaDataSource {
    var mediaLoads = 0

    // settle 竞态控制:lookup 挂起在不可取消停车点;releaseHeldLookup 由测试在
    // 主壳失活之后调用,模拟"网络响应在协程被取消后才返回"的生产竞态。
    @Volatile var lookupImmediate = false
    val lookupStarted = CountDownLatch(1)
    private val parkedLookup = AtomicReference<Continuation<PlaybackInfoDto>?>(null)

    override suspend fun loadLibraries() = listOf(LibraryItem(jellyfin_id = "library", selected = true))
    override suspend fun saveSelection(selectedIds: List<String>) = loadLibraries()
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
        return MediaPage()
    }
    override suspend fun loadMediaFolders(
        libraryId: String?,
        type: MediaTypeFilter,
        search: String?,
        excludeFavorites: Boolean,
    ): List<MediaFolderItem> = emptyList()
    override suspend fun loadPlayback(mediaId: String): PlaybackInfoDto? {
        if (lookupImmediate) {
            return PlaybackInfoDto(media_id = mediaId, stream_url = "stream://$mediaId")
        }
        return suspendCoroutine { cont ->
            parkedLookup.set(cont)
            lookupStarted.countDown()
        }
    }
    override suspend fun createReviewSession(): ReviewSessionDto? = null
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
    fun releaseHeldLookup() {
        val cont = parkedLookup.getAndSet(null) ?: return
        cont.resume(PlaybackInfoDto(media_id = "media-1", stream_url = "stream://media-1"))
    }
    override suspend fun markSeen(sessionId: String, mediaId: String) = Unit
    override suspend fun addFavorite(mediaId: String) = true
    override suspend fun removeFavorite(mediaId: String) = true
    override suspend fun enqueueDelete(mediaId: String) = true
    override suspend fun dequeueDelete(mediaId: String) = true
    override suspend fun setReviewPosition(sessionId: String, index: Int) = Unit
    override suspend fun listFavorites() = listOf(FavoriteItemDto(media_id = "media-1"))
    override suspend fun listDeleteQueue(): List<DeleteQueueItemDto> = emptyList()
    override suspend fun commitDeleteQueue(): CommitResultDto? = null
    override suspend fun loadDuplicatesExact(): List<DuplicateGroupDto> = emptyList()
    override suspend fun loadDuplicatesSimilar(): List<DuplicateGroupDto> = emptyList()
    override suspend fun reportProgress(mediaId: String, positionMs: Long, isPaused: Boolean) = Unit
}
