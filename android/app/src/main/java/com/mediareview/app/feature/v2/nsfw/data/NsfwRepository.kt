package com.mediareview.app.feature.v2.nsfw.data

import com.mediareview.app.feature.v2.nsfw.network.NsfwApiFactory
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** 审核页只依赖该接口；真实实现走 Bridge，测试用 MockWebServer 或假实现。 */
interface NsfwReviewDataSource {
    suspend fun feed(cursor: String?, limit: Int = 50): NsfwFeedPage
    suspend fun details(imageId: String): NsfwImageDetails
    suspend fun decide(
        imageId: String,
        action: String,
        favorite: Boolean?,
        expectedUpdatedAt: String,
        clientRequestId: String,
    ): NsfwDecisionResult

    suspend fun promptSummary(promptId: String, versionId: String?): NsfwPromptSummary
    suspend fun promptImages(
        promptId: String,
        versionId: String?,
        cursor: String? = null,
        limit: Int = 30,
    ): NsfwPromptImagesPage

    suspend fun createEvaluation(promptId: String, request: NsfwEvaluationRequest): NsfwEvaluationResult
    suspend fun evaluationReceipt(clientRequestId: String): NsfwEvaluationReceipt
}

internal fun interface NsfwBaseUrlPort {
    suspend fun baseUrl(): String
}

/**
 * v2 feed / 详情 / 审核 / 提示词评分的 HTTP 实现。
 *
 * 所有错误（HTTP 4xx/5xx、success=false、断网）统一为 [NsfwApiException]，
 * UI 只按 code 处理，不解析原始 JSON，也不在 UI 层拼接端点。
 */
class NsfwRepository internal constructor(
    private val baseUrlPort: NsfwBaseUrlPort,
    private val apiFactory: NsfwApiFactory,
) : NsfwReviewDataSource {

    constructor(store: NsfwProfileStore, apiFactory: NsfwApiFactory) : this(
        NsfwBaseUrlPort { store.current().baseUrl },
        apiFactory,
    )

    override suspend fun feed(cursor: String?, limit: Int): NsfwFeedPage {
        val safeLimit = limit.coerceIn(1, 100)
        return call { api().feed(cursor = cursor, limit = safeLimit) }
    }

    override suspend fun details(imageId: String): NsfwImageDetails {
        require(imageId.isNotBlank()) { "image_id 不能为空" }
        return call { api().imageDetails(imageId) }
    }

    override suspend fun decide(
        imageId: String,
        action: String,
        favorite: Boolean?,
        expectedUpdatedAt: String,
        clientRequestId: String,
    ): NsfwDecisionResult {
        require(action in setOf("KEEP", "REJECT", "RESET")) { "非法审核动作：$action" }
        require(expectedUpdatedAt.isNotBlank()) { "缺少 expected_updated_at" }
        require(clientRequestId.isNotBlank()) { "缺少 client_request_id" }
        return call {
            api().decide(
                imageId,
                NsfwDecisionRequest(
                    action = action,
                    favorite = favorite,
                    expectedUpdatedAt = expectedUpdatedAt,
                    clientRequestId = clientRequestId,
                ),
            )
        }
    }

    override suspend fun promptSummary(promptId: String, versionId: String?): NsfwPromptSummary {
        require(promptId.isNotBlank()) { "prompt_id 不能为空" }
        return call { api().promptSummary(promptId, versionId) }
    }

    override suspend fun promptImages(
        promptId: String,
        versionId: String?,
        cursor: String?,
        limit: Int,
    ): NsfwPromptImagesPage {
        require(promptId.isNotBlank()) { "prompt_id 不能为空" }
        return call { api().promptImages(promptId, versionId, cursor, limit.coerceIn(1, 100)) }
    }

    override suspend fun createEvaluation(
        promptId: String,
        request: NsfwEvaluationRequest,
    ): NsfwEvaluationResult {
        require(promptId.isNotBlank()) { "prompt_id 不能为空" }
        validateEvaluation(request)
        return call { api().createEvaluation(promptId, request) }
    }

    override suspend fun evaluationReceipt(clientRequestId: String): NsfwEvaluationReceipt {
        require(clientRequestId.isNotBlank()) { "client_request_id 不能为空" }
        return call { api().evaluationReceipt(clientRequestId) }
    }

    private suspend fun api() = apiFactory.create(baseUrlPort.baseUrl(), authenticated = true)

    private suspend fun <T> call(block: suspend () -> NsfwEnvelope<T>): T {
        val envelope = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            throw apiFactory.toException(error)
        } catch (error: IOException) {
            throw NsfwApiException("NETWORK_ERROR", "无法连接 NSFW Bridge", 0)
        }
        return apiFactory.unwrap(envelope)
    }

    private fun validateEvaluation(request: NsfwEvaluationRequest) {
        if (request.overallScore !in 1..5) {
            throw NsfwApiException(NsfwErrorCodes.VALIDATION_ERROR, "总分必须是 1–5")
        }
        request.dimensions?.forEach { (key, value) ->
            if (key !in NSFW_SCORE_DIMENSIONS) {
                throw NsfwApiException(NsfwErrorCodes.VALIDATION_ERROR, "未知评分维度：$key")
            }
            if (value !in 1..5) {
                throw NsfwApiException(NsfwErrorCodes.VALIDATION_ERROR, "维度分数必须是 1–5")
            }
        }
        if (request.clientRequestId.isBlank()) {
            throw NsfwApiException(NsfwErrorCodes.VALIDATION_ERROR, "缺少幂等键")
        }
    }
}
