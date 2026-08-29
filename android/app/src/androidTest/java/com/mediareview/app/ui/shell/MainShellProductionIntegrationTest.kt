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
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.PlaybackInfoDto
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
import org.junit.Assert.assertEquals
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
}

private class ShellSettingsStore : MediaWallSettingsDataSource {
    override suspend fun current() = MediaWallSettings()
    override suspend fun save(value: MediaWallSettings) = Unit
}

private class ShellPlaybackController : ReviewPlaybackController {
    var deactivations = 0
    override fun settle(index: Int, current: ReviewPlayable) = Unit
    override fun prepareNext(index: Int, item: ReviewPlayable?) = Unit
    override fun snapshotFor(mediaId: String): ProgressSnapshot? = null
    override fun onSwipeStarted() = Unit
    override fun deactivateReview() { deactivations += 1 }
}

private class ShellRepository : MediaDataSource {
    var mediaLoads = 0
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
    ): MediaPage {
        mediaLoads += 1
        return MediaPage()
    }
    override suspend fun loadPlayback(mediaId: String): PlaybackInfoDto? = null
    override suspend fun createReviewSession(): ReviewSessionDto? = null
    override suspend fun latestReviewSession() = ReviewSessionDto(session_id = "session", status = "active")
    override suspend fun reviewQueue(sessionId: String, page: Int, pageSize: Int) = ReviewQueuePageDto()
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
