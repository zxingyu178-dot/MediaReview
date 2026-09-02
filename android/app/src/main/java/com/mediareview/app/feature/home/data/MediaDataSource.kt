package com.mediareview.app.feature.home.data

import com.mediareview.app.core.model.CommitResultDto
import com.mediareview.app.core.model.DeleteCommitPrepDto
import com.mediareview.app.core.model.DeleteQueueItemDto
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.model.DuplicateScanStatusDto
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaFolderItem
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.PlaybackInfoDto
import com.mediareview.app.core.model.ReviewQueuePageDto
import com.mediareview.app.core.model.ReviewSessionDto
import com.mediareview.app.core.model.TaskStateDto

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
    /** 触发重复扫描后台任务,返回任务引用供进度/控制。 */
    suspend fun triggerDuplicateScan(): TaskStateDto?
    /** 最近一次重复扫描任务状态(无任务时为 null)。 */
    suspend fun duplicateScanStatus(): DuplicateScanStatusDto?
    /** 记录重复分组内某成员的人工"保留"选择。 */
    suspend fun setDuplicateKeep(groupId: String, mediaId: String, keep: Boolean): Boolean
    /** 后台任务协作暂停/继续/取消(重复扫描控制)。 */
    suspend fun pauseTask(taskId: String): TaskStateDto?
    suspend fun resumeTask(taskId: String): TaskStateDto?
    suspend fun cancelTask(taskId: String): TaskStateDto?
    /** 单条媒体摘要(重复对比页封面/元数据)。 */
    suspend fun loadMediaSummary(mediaId: String): MediaSummary?
    suspend fun reportProgress(mediaId: String, positionMs: Long, isPaused: Boolean)
}
