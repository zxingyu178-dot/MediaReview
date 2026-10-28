package com.mediareview.app.feature.v2.nsfw.network

import com.mediareview.app.feature.v2.nsfw.data.NsfwDecisionRequest
import com.mediareview.app.feature.v2.nsfw.data.NsfwDecisionResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwEnvelope
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationReceipt
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationRequest
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwHealth
import com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairingRequest
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairingResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairingStatus
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptImagesPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptSummary
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * NSFW Studio Mobile Review Bridge（8767）客户端合同。
 *
 * 端点与字段冻结于 docs/integration/NSFW_MOBILE_API_CONTRACT_V2.md；
 * 现有 Bridge 只实现 health / pairing / 图片二进制，v2 端点尚未实现。
 */
interface NsfwStudioApi {

    @GET("/mobile/system/health")
    suspend fun health(): NsfwEnvelope<NsfwHealth>

    @GET("/mobile/pairing/status")
    suspend fun pairingStatus(): NsfwEnvelope<NsfwPairingStatus>

    @POST("/mobile/pairing/verify")
    suspend fun verifyPairing(@Body body: NsfwPairingRequest): NsfwEnvelope<NsfwPairingResult>

    @GET("/mobile/images/feed")
    suspend fun feed(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): NsfwEnvelope<NsfwFeedPage>

    @GET("/mobile/images/{image_id}/details")
    suspend fun imageDetails(@Path("image_id") imageId: String): NsfwEnvelope<NsfwImageDetails>

    @POST("/mobile/review/images/{image_id}/decision")
    suspend fun decide(
        @Path("image_id") imageId: String,
        @Body body: NsfwDecisionRequest,
    ): NsfwEnvelope<NsfwDecisionResult>

    @GET("/mobile/prompts/{prompt_id}/review-summary")
    suspend fun promptSummary(
        @Path("prompt_id") promptId: String,
        @Query("version_id") versionId: String? = null,
    ): NsfwEnvelope<NsfwPromptSummary>

    @GET("/mobile/prompts/{prompt_id}/images")
    suspend fun promptImages(
        @Path("prompt_id") promptId: String,
        @Query("version_id") versionId: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 30,
    ): NsfwEnvelope<NsfwPromptImagesPage>

    @POST("/mobile/prompts/{prompt_id}/evaluations")
    suspend fun createEvaluation(
        @Path("prompt_id") promptId: String,
        @Body body: NsfwEvaluationRequest,
    ): NsfwEnvelope<NsfwEvaluationResult>

    @GET("/mobile/prompts/evaluations/{client_request_id}")
    suspend fun evaluationReceipt(
        @Path("client_request_id") clientRequestId: String,
    ): NsfwEnvelope<NsfwEvaluationReceipt>
}
