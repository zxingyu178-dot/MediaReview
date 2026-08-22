package com.mediareview.app.core.network

import com.mediareview.app.core.model.CommitResultDto
import com.mediareview.app.core.model.DeleteQueueItemDto
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.core.model.HealthOut
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.PairingStatusOut
import com.mediareview.app.core.model.PlaybackInfoDto
import com.mediareview.app.core.model.ProgressRequest
import com.mediareview.app.core.model.ReviewCreateRequest
import com.mediareview.app.core.model.ReviewPositionRequest
import com.mediareview.app.core.model.ReviewQueuePageDto
import com.mediareview.app.core.model.ReviewSeenRequest
import com.mediareview.app.core.model.ReviewSessionDto
import com.mediareview.app.core.model.SpriteEnsureOut
import com.mediareview.app.core.model.SpriteManifestDto
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
    ): Envelope<MediaPage>

    @GET("/api/v1/media/{media_id}")
    suspend fun mediaDetail(@Path("media_id") mediaId: String): Envelope<MediaSummary>

    @GET("/api/v1/cache/sprites/{media_id}")
    suspend fun spriteManifest(@Path("media_id") mediaId: String): Envelope<SpriteManifestDto>

    @POST("/api/v1/cache/sprites/{media_id}")
    suspend fun ensureSprite(@Path("media_id") mediaId: String): Envelope<SpriteEnsureOut>

    @GET("/api/v1/media/{media_id}/playback")
    suspend fun playback(@Path("media_id") mediaId: String): Envelope<PlaybackInfoDto>

    @POST("/api/v1/media/{media_id}/progress")
    suspend fun reportProgress(
        @Path("media_id") mediaId: String,
        @Body body: ProgressRequest,
    ): Envelope<Map<String, String>>

    @POST("/api/v1/review/sessions")
    suspend fun createReviewSession(@Body body: ReviewCreateRequest): Envelope<ReviewSessionDto>

    @GET("/api/v1/review/sessions/latest")
    suspend fun latestReviewSession(): Envelope<ReviewSessionDto>

    @GET("/api/v1/review/sessions/{session_id}/queue")
    suspend fun reviewQueue(
        @Path("session_id") sessionId: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Envelope<ReviewQueuePageDto>

    @POST("/api/v1/review/sessions/{session_id}/seen")
    suspend fun markSeen(
        @Path("session_id") sessionId: String,
        @Body body: ReviewSeenRequest,
    ): Envelope<Map<String, String>>

    @POST("/api/v1/review/sessions/{session_id}/position")
    suspend fun setReviewPosition(
        @Path("session_id") sessionId: String,
        @Body body: ReviewPositionRequest,
    ): Envelope<Map<String, Any>>

    @GET("/api/v1/favorites")
    suspend fun listFavorites(): Envelope<List<FavoriteItemDto>>

    @POST("/api/v1/favorites/{media_id}")
    suspend fun addFavorite(@Path("media_id") mediaId: String): Envelope<Map<String, String>>

    @DELETE("/api/v1/favorites/{media_id}")
    suspend fun removeFavorite(@Path("media_id") mediaId: String): Envelope<Map<String, String>>

    @GET("/api/v1/delete-queue")
    suspend fun listDeleteQueue(): Envelope<List<DeleteQueueItemDto>>

    @POST("/api/v1/delete-queue/{media_id}")
    suspend fun enqueueDelete(@Path("media_id") mediaId: String): Envelope<Map<String, String>>

    @DELETE("/api/v1/delete-queue/{media_id}")
    suspend fun dequeueDelete(@Path("media_id") mediaId: String): Envelope<Map<String, String>>

    @POST("/api/v1/delete-queue/commit")
    suspend fun commitDeleteQueue(): Envelope<CommitResultDto>

    @GET("/api/v1/duplicates/exact")
    suspend fun duplicatesExact(): Envelope<List<DuplicateGroupDto>>

    @GET("/api/v1/duplicates/similar")
    suspend fun duplicatesSimilar(): Envelope<List<DuplicateGroupDto>>
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