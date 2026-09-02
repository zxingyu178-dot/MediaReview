package com.mediareview.app.feature.home.data

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

/** ViewModel 使用的媒体业务边界；生产实现为 [MediaRepository]，测试可使用受控 fake。 */
interface MediaDataSource {
    suspend fun loadLibraries(): List<LibraryItem>
    suspend fun saveSelection(selectedIds: List<String>): List<LibraryItem>
    suspend fun loadMedia(
        libraryId: String? = null,
        type: MediaTypeFilter = MediaTypeFilter.All,
        sortBy: SortField = SortField.Name,
        sortOrder: SortOrder = SortOrder.Asc,
        page: Int = 1,
        pageSize: Int = 50,
        search: String? = null,
        excludeFavorites: Boolean = false,
        folderId: String? = null,
    ): MediaPage

    /** 文件夹辅助视图:与当前筛选一致的分目录聚合(folder_id 为服务器 ID,非路径)。 */
    suspend fun loadMediaFolders(
        libraryId: String? = null,
        type: MediaTypeFilter = MediaTypeFilter.All,
        search: String? = null,
        excludeFavorites: Boolean = false,
    ): List<MediaFolderItem>
    suspend fun loadPlayback(mediaId: String): PlaybackInfoDto?
    suspend fun createReviewSession(): ReviewSessionDto?
    suspend fun latestReviewSession(): ReviewSessionDto?
    suspend fun reviewQueue(sessionId: String, page: Int = 1, pageSize: Int = 50): ReviewQueuePageDto
    suspend fun markSeen(sessionId: String, mediaId: String)
    suspend fun addFavorite(mediaId: String): Boolean
    suspend fun removeFavorite(mediaId: String): Boolean
    suspend fun enqueueDelete(mediaId: String): Boolean
    suspend fun dequeueDelete(mediaId: String): Boolean
    suspend fun setReviewPosition(sessionId: String, index: Int)
    suspend fun listFavorites(): List<FavoriteItemDto>
    suspend fun listDeleteQueue(): List<DeleteQueueItemDto>
    /** 两阶段最终删除:先 prepare 取得一次性 nonce,再 commit(nonce) 真实删除。 */
    suspend fun prepareDeleteCommit(): DeleteCommitPrepDto?
    suspend fun commitDeleteQueue(nonce: String): CommitResultDto?
    suspend fun loadDuplicatesExact(): List<DuplicateGroupDto>
    suspend fun loadDuplicatesSimilar(): List<DuplicateGroupDto>
    suspend fun reportProgress(mediaId: String, positionMs: Long, isPaused: Boolean)
}
