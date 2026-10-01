package com.mediareview.app.core.network

import com.mediareview.app.core.model.CommitRequest
import com.mediareview.app.core.model.CommitResultDto
import com.mediareview.app.core.model.DeleteCommitPrepDto
import com.mediareview.app.core.model.DeleteQueueItemDto
import com.mediareview.app.core.model.DeleteQueueSummaryDto
import com.mediareview.app.core.model.DuplicateGroupDetailDto
import com.mediareview.app.core.model.DuplicateGroupPageDto
import com.mediareview.app.core.model.DuplicateKeepRequest
import com.mediareview.app.core.model.DuplicateKeepResultDto
import com.mediareview.app.core.model.DuplicateScanStatusDto
import com.mediareview.app.core.model.DuplicateSummaryDto
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.core.model.HealthOut
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.JellyfinStatusOut
import com.mediareview.app.core.model.MediaFolderItem
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.MutationResultDto
import com.mediareview.app.core.model.PairingStatusOut
import com.mediareview.app.core.model.PlaybackInfoDto
import com.mediareview.app.core.model.ProgressRequest
import com.mediareview.app.core.model.ReviewCreateRequest
import com.mediareview.app.core.model.ReviewNearestDto
import com.mediareview.app.core.model.ReviewPositionRequest
import com.mediareview.app.core.model.ReviewProgressDto
import com.mediareview.app.core.model.ReviewQueuePageDto
import com.mediareview.app.core.model.ReviewSeenRequest
import com.mediareview.app.core.model.ReviewSeenResultDto
import com.mediareview.app.core.model.ReviewSessionDto
import com.mediareview.app.core.model.SpriteEnsureOut
import com.mediareview.app.core.model.SpriteManifestDto
import com.mediareview.app.core.model.TaskStateDto
import com.mediareview.app.core.model.VerifyOut
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MediaReview Server /api/v1 接口。
 * 阶段 9 扩展:媒体库(list/selection) + 媒体墙(list)。
 */
interface MediaReviewApi {

    @GET("/api/v1/system/health")
    suspend fun health(): Envelope<HealthOut>

    @GET("/api/v1/pairing/status")
    suspend fun pairingStatus(): Envelope<PairingStatusOut>

    @GET("/api/v1/jellyfin/status")
    suspend fun jellyfinStatus(): Envelope<JellyfinStatusOut>

    @POST("/api/v1/pairing/verify")
    suspend fun verify(@Body body: VerifyRequest): Envelope<VerifyOut>

    @GET("/api/v1/libraries")
    suspend fun libraries(): Envelope<List<LibraryItem>>

    @PUT("/api/v1/libraries/selection")
    suspend fun saveLibrariesSelection(@Body body: SelectionRequest): Envelope<List<LibraryItem>>

    @GET("/api/v1/media")
    suspend fun media(
        @Query("library_id") libraryId: String? = null,
        @Query("media_type") mediaType: String? = null,
        @Query("sort_by") sortBy: String = "name",
        @Query("sort_order") sortOrder: String = "asc",
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
        @Query("search") search: String? = null,
        @Query("exclude_favorites") excludeFavorites: Boolean = false,
        @Query("folder_id") folderId: String? = null,
    ): Envelope<MediaPage>

    @GET("/api/v1/media/folders")
    suspend fun mediaFolders(
        @Query("library_id") libraryId: String? = null,
        @Query("media_type") mediaType: String? = null,
        @Query("search") search: String? = null,
        @Query("exclude_favorites") excludeFavorites: Boolean = false,
    ): Envelope<List<MediaFolderItem>>

    @GET("/api/v1/media/{media_id}")
    suspend fun mediaDetail(@Path("media_id") mediaId: String): Envelope<MediaSummary>

    @GET("/api/v1/cache/sprites/{media_id}")
    suspend fun spriteManifest(@Path("media_id") mediaId: String): Envelope<SpriteManifestDto>

    @POST("/api/v1/cache/sprites/{media_id}")
    suspend fun ensureSprite(@Path("media_id") mediaId: String): Envelope<SpriteEnsureOut>

    @GET("/api/v1/tasks/{task_id}")
    suspend fun taskDetail(@Path("task_id") taskId: String): Envelope<TaskStateDto>

    @POST("/api/v1/tasks/{task_id}/cancel")
    suspend fun cancelTask(@Path("task_id") taskId: String): Envelope<TaskStateDto>

    @POST("/api/v1/tasks/{task_id}/pause")
    suspend fun pauseTask(@Path("task_id") taskId: String): Envelope<TaskStateDto>

    @POST("/api/v1/tasks/{task_id}/resume")
    suspend fun resumeTask(@Path("task_id") taskId: String): Envelope<TaskStateDto>

    @GET("/api/v1/media/{media_id}/playback")
    suspend fun playback(@Path("media_id") mediaId: String): Envelope<PlaybackInfoDto>

    @POST("/api/v1/media/{media_id}/progress")
    suspend fun reportProgress(
        @Path("media_id") mediaId: String,
        @Body body: ProgressRequest,
    ): Envelope<MutationResultDto>

    @POST("/api/v1/review/sessions")
    suspend fun createReviewSession(@Body body: ReviewCreateRequest): Envelope<ReviewSessionDto>

    @GET("/api/v1/review/sessions/latest")
    suspend fun latestReviewSession(): Envelope<ReviewSessionDto>

    /**
     * 单会话权威进度（Stage 8B.2 §13）：只在"准备完成会话"前刷新一次，
     * 用于拿到 remaining / unavailable / completed 的**服务端权威值**。
     */
    @GET("/api/v1/review/sessions/{session_id}")
    suspend fun reviewSession(@Path("session_id") sessionId: String): Envelope<ReviewSessionDto>

    @GET("/api/v1/review/sessions/{session_id}/queue")
    suspend fun reviewQueue(
        @Path("session_id") sessionId: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Envelope<ReviewQueuePageDto>

    /**
     * 最近可用项（Stage 8B.2 §16：SQL 直查稀疏队列）。
     *
     * `index` 为查询锚点，`direction` ∈ forward / backward / nearest；
     * 返回 `{"index": <int|null>}` —— null 表示**服务端明确**没有可用媒体。
     */
    @GET("/api/v1/review/sessions/{session_id}/nearest")
    suspend fun nearestReviewIndex(
        @Path("session_id") sessionId: String,
        @Query("index") index: Int,
        @Query("direction") direction: String,
    ): Envelope<ReviewNearestDto>

    @POST("/api/v1/review/sessions/{session_id}/seen")
    suspend fun markSeen(
        @Path("session_id") sessionId: String,
        @Body body: ReviewSeenRequest,
    ): Envelope<ReviewSeenResultDto>

    @POST("/api/v1/review/sessions/{session_id}/position")
    suspend fun setReviewPosition(
        @Path("session_id") sessionId: String,
        @Body body: ReviewPositionRequest,
    ): Envelope<ReviewProgressDto>

    /**
     * 手动完成批阅会话（Stage 8B §40/§16）：
     * 队列全部项稳定批阅后调用，长期状态以服务端为准（不靠本地 last page 判断）。
     */
    @POST("/api/v1/review/sessions/{session_id}/complete")
    suspend fun completeReviewSession(@Path("session_id") sessionId: String): Envelope<ReviewProgressDto>

    @GET("/api/v1/favorites")
    suspend fun listFavorites(): Envelope<List<FavoriteItemDto>>

    @POST("/api/v1/favorites/{media_id}")
    suspend fun addFavorite(@Path("media_id") mediaId: String): Envelope<MutationResultDto>

    @DELETE("/api/v1/favorites/{media_id}")
    suspend fun removeFavorite(@Path("media_id") mediaId: String): Envelope<MutationResultDto>

    @GET("/api/v1/delete-queue")
    suspend fun listDeleteQueue(): Envelope<List<DeleteQueueItemDto>>

    /** 待删除摘要(Stage 8C §11):只统计 pending,供 Organize 首页使用。 */
    @GET("/api/v1/delete-queue/summary")
    suspend fun deleteQueueSummary(): Envelope<DeleteQueueSummaryDto>

    @POST("/api/v1/delete-queue/{media_id}")
    suspend fun enqueueDelete(@Path("media_id") mediaId: String): Envelope<MutationResultDto>

    @DELETE("/api/v1/delete-queue/{media_id}")
    suspend fun dequeueDelete(@Path("media_id") mediaId: String): Envelope<MutationResultDto>

    @POST("/api/v1/delete-queue/commit/prepare")
    suspend fun prepareDeleteCommit(): Envelope<DeleteCommitPrepDto>

    @POST("/api/v1/delete-queue/commit")
    suspend fun commitDeleteQueue(@Body body: CommitRequest): Envelope<CommitResultDto>

    /** 完全重复分页(Stage 8C.1 §11): items/total/page/page_size,读取侧分页。 */
    @GET("/api/v1/duplicates/exact")
    suspend fun duplicatesExact(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Envelope<DuplicateGroupPageDto>

    /** 疑似重复分页(Stage 8C.1 §11): 与 exact 相同合同,独立维护分页。 */
    @GET("/api/v1/duplicates/similar")
    suspend fun duplicatesSimilar(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Envelope<DuplicateGroupPageDto>

    /** 重复媒体摘要(Stage 8C §12):计数 + 最近扫描任务状态,不返回分组本体。 */
    @GET("/api/v1/duplicates/summary")
    suspend fun duplicatesSummary(): Envelope<DuplicateSummaryDto>

    /** 分组详情(Stage 8C §33):分组 + 成员媒体摘要,一次请求,客户端零 N+1。 */
    @GET("/api/v1/duplicates/{group_id}")
    suspend fun duplicateDetail(@Path("group_id") groupId: String): Envelope<DuplicateGroupDetailDto>

    @POST("/api/v1/duplicates/scan")
    suspend fun scanDuplicates(): Envelope<TaskStateDto>

    @GET("/api/v1/duplicates/status")
    suspend fun duplicatesStatus(): Envelope<DuplicateScanStatusDto>

    @POST("/api/v1/duplicates/{group_id}/keep")
    suspend fun setDuplicateKeep(
        @Path("group_id") groupId: String,
        @Body body: DuplicateKeepRequest,
    ): Envelope<DuplicateKeepResultDto>
}

@Serializable
data class VerifyRequest(
    val device_id: String,
    val code: String,
)

@Serializable
data class SelectionRequest(
    val selected: List<String>,
)

/**
 * 说明:
 * - 配对码生成(POST /pairing/code)只允许服务器本机或管理后台,手机端不调用;
 *   配对码由用户在服务器/管理界面查看到后在 App 内输入。
 * - 危险/状态修改 API(收藏/批阅/待删除/重复等)依赖 token,统一由 [AuthInterceptor] 附加。
 */
