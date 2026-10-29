package com.mediareview.app.feature.v2.nsfw.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * NSFW Studio Mobile API v2 客户端 DTO（冻结契约见 docs/integration/NSFW_MOBILE_API_CONTRACT_V2.md）。
 *
 * 约束：
 * - 只在 [com.mediareview.app.feature.v2.nsfw.network.NsfwStudioApi] 与本文件定义端点/DTO；
 * - UI 与 ViewModel 不拼接路径，也不解析游标（游标是服务端 opaque 字符串）。
 */

/** 客户端要求的最低 Bridge API contract。 */
const val NSFW_REQUIRED_API_CONTRACT: Int = 2

/** 产品标识前缀（当前 Bridge 为 nsfw-studio；不得按完整版本字符串硬判断）。 */
const val NSFW_PRODUCT_PREFIX: String = "nsfw-studio"

/** v2 必需能力；缺一即视为 Bridge 未实现 v2，禁止配对与虚报可用。 */
val NSFW_REQUIRED_CAPABILITIES: List<String> = listOf(
    "mobile_feed_v2",
    "mobile_image_details_v2",
    "mobile_image_decision_v2",
    "mobile_prompt_review_v2",
    "mobile_prompt_evaluation_v2",
)

/** 七个可选评分维度（白名单）。 */
val NSFW_SCORE_DIMENSIONS: List<String> = listOf(
    "subject", "clothing", "scene", "composition", "lighting", "style", "quality",
)

object NsfwErrorCodes {
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val AUTH_REVOKED = "AUTH_REVOKED"
    const val CURSOR_EXPIRED = "CURSOR_EXPIRED"
    const val REVIEW_CONFLICT = "REVIEW_CONFLICT"
    const val PROMPT_UNLINKED = "PROMPT_UNLINKED"
    const val PROMPT_VERSION_UNKNOWN = "PROMPT_VERSION_UNKNOWN"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val CAPABILITY_MISSING = "CAPABILITY_MISSING"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val IMAGE_NOT_FOUND = "IMAGE_NOT_FOUND"
    const val PROMPT_NOT_FOUND = "PROMPT_NOT_FOUND"

    /** 客户端校验：响应中的身份字段与请求不一致，响应被拒绝，不得静默修正。 */
    const val RESPONSE_MISMATCH = "RESPONSE_MISMATCH"

    /** Bridge v1 旧码映射（双端兼容）：不能把有效旧配对误判为已吊销。 */
    const val LEGACY_UNAUTHORIZED = "UNAUTHORIZED"
    const val LEGACY_DEVICE_REVOKED = "DEVICE_REVOKED"

    fun canonical(raw: String): String = when (raw) {
        LEGACY_UNAUTHORIZED -> AUTH_REQUIRED
        LEGACY_DEVICE_REVOKED -> AUTH_REVOKED
        else -> raw
    }
}

object NsfwFeedEvents {
    const val UPSERT = "UPSERT"
    const val REMOVE = "REMOVE"
}

@Serializable
data class NsfwError(
    val code: String = "UNKNOWN",
    val message: String = "",
    val details: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class NsfwEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val error: NsfwError? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class NsfwErrorEnvelope(
    val success: Boolean = false,
    val error: NsfwError? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class NsfwHealth(
    val status: String = "",
    val product: String = "",
    val version: String = "",
    @SerialName("api_contract") val apiContract: Int = 0,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class NsfwPairingStatus(
    @SerialName("pairing_required") val pairingRequired: Boolean = false,
)

@Serializable
data class NsfwPairingRequest(
    @SerialName("installation_id") val installationId: String,
    @SerialName("device_name") val deviceName: String,
    val code: String,
)

@Serializable
data class NsfwPairingResult(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("installation_id") val installationId: String = "",
    val name: String = "",
    val token: String = "",
    @SerialName("paired_at") val pairedAt: String = "",
)

@Serializable
data class NsfwPromptLink(
    @SerialName("prompt_id") val promptId: String? = null,
    @SerialName("prompt_version_id") val promptVersionId: String? = null,
    @SerialName("link_state") val linkState: String = LINK_UNLINKED,
) {
    val isExact: Boolean get() = linkState == LINK_EXACT && promptId != null && promptVersionId != null
    val isUnresolved: Boolean get() = linkState == LINK_UNRESOLVED && promptId != null
    val isUnlinked: Boolean get() = promptId == null || linkState == LINK_UNLINKED

    companion object {
        const val LINK_EXACT = "EXACT"
        const val LINK_UNRESOLVED = "UNRESOLVED"
        const val LINK_UNLINKED = "UNLINKED"
    }
}

@Serializable
data class NsfwFeedItem(
    @SerialName("image_id") val imageId: String = "",
    val revision: String = "",
    /** UPSERT（默认）或 REMOVE tombstone；REMOVE 只表示从队列移除，不删除文件。 */
    val event: String = NsfwFeedEvents.UPSERT,
    val status: String = "NEW",
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    val kind: String = "original",
    @SerialName("job_id") val jobId: String? = null,
    val seed: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val prompt: NsfwPromptLink? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("preview_url") val previewUrl: String = "",
    @SerialName("original_url") val originalUrl: String = "",
)

@Serializable
data class NsfwFeedPage(
    val items: List<NsfwFeedItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    val resumed: Boolean = false,
)

@Serializable
data class NsfwImageDetails(
    @SerialName("image_id") val imageId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("generated_at") val generatedAt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("positive_prompt") val positivePrompt: String? = null,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    @SerialName("prompt_id") val promptId: String? = null,
    @SerialName("prompt_version_id") val promptVersionId: String? = null,
    @SerialName("link_state") val linkState: String = NsfwPromptLink.LINK_UNLINKED,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("module_id") val moduleId: String? = null,
    val seed: Long? = null,
    val model: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val mime: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val revision: String = "",
) {
    val promptLink: NsfwPromptLink
        get() = NsfwPromptLink(promptId, promptVersionId, linkState)

    /** 详情快照是生成时的；UI 只展示，不得用当前 Prompt 正文替换。 */
    val positiveSnapshot: String? get() = positivePrompt
}

@Serializable
data class NsfwDecisionRequest(
    /** null 表示 favorite-only：服务端必须保留原审核状态。 */
    val action: String? = null,
    val favorite: Boolean? = null,
    @SerialName("expected_updated_at") val expectedUpdatedAt: String,
    @SerialName("client_request_id") val clientRequestId: String,
)

@Serializable
data class NsfwDecisionResult(
    @SerialName("image_id") val imageId: String = "",
    val status: String = "",
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    val revision: String = "",
)

@Serializable
data class NsfwPromptEvaluation(
    @SerialName("evaluation_id") val evaluationId: String = "",
    @SerialName("overall_score") val overallScore: Int = 0,
    @SerialName("prompt_version_id") val promptVersionId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val notes: String = "",
)

@Serializable
data class NsfwPromptSummary(
    @SerialName("prompt_id") val promptId: String = "",
    @SerialName("prompt_version_id") val promptVersionId: String? = null,
    @SerialName("link_state") val linkState: String = NsfwPromptLink.LINK_UNLINKED,
    @SerialName("evaluation_count") val evaluationCount: Int = 0,
    @SerialName("version_latest") val versionLatest: NsfwPromptEvaluation? = null,
    @SerialName("prompt_latest") val promptLatest: NsfwPromptEvaluation? = null,
    @SerialName("images_total") val imagesTotal: Int = 0,
    @SerialName("seen_count") val seenCount: Int = 0,
    @SerialName("keep_count") val keepCount: Int = 0,
    @SerialName("reject_count") val rejectCount: Int = 0,
    @SerialName("has_new_samples") val hasNewSamples: Boolean = false,
)

@Serializable
data class NsfwPromptImageItem(
    @SerialName("image_id") val imageId: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    val status: String = "NEW",
    @SerialName("is_current") val isCurrent: Boolean = false,
)

@Serializable
data class NsfwPromptImagesPage(
    val items: List<NsfwPromptImageItem> = emptyList(),
    val total: Int = 0,
    @SerialName("current_index") val currentIndex: Int = 0,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class NsfwEvaluationRequest(
    @SerialName("prompt_version_id") val promptVersionId: String? = null,
    @SerialName("overall_score") val overallScore: Int,
    val dimensions: Map<String, Int>? = null,
    val notes: String = "",
    @SerialName("client_request_id") val clientRequestId: String,
)

@Serializable
data class NsfwEvaluationResult(
    @SerialName("evaluation_id") val evaluationId: String = "",
    @SerialName("prompt_id") val promptId: String = "",
    @SerialName("prompt_version_id") val promptVersionId: String? = null,
    @SerialName("overall_score") val overallScore: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("client_request_id") val clientRequestId: String = "",
)

@Serializable
data class NsfwEvaluationReceipt(
    val found: Boolean = false,
    val evaluation: NsfwEvaluationResult? = null,
)

/** 已发出但结果未确认的评分请求；按服务器来源隔离，重启后不自动重发。 */
@Serializable
data class NsfwPendingEvaluation(
    val serverOrigin: String = "",
    val promptId: String,
    val versionId: String? = null,
    val request: NsfwEvaluationRequest,
)

/** 未确认评分记录的持久化端口（DataStore 实现；测试用内存假实现）。 */
interface NsfwEvaluationJournal {
    suspend fun loadPendingEvaluations(): List<NsfwPendingEvaluation>
    suspend fun savePendingEvaluations(items: List<NsfwPendingEvaluation>)
}

/** 401 / 409 / 410 等业务错误在客户端统一成该异常，UI 不解析原始 JSON。 */
class NsfwApiException(
    val code: String,
    override val message: String,
    val httpStatus: Int = 0,
) : Exception(message) {
    val isAuthRejected: Boolean
        get() = httpStatus == 401 ||
            code == NsfwErrorCodes.AUTH_REQUIRED ||
            code == NsfwErrorCodes.AUTH_REVOKED ||
            code == NsfwErrorCodes.LEGACY_UNAUTHORIZED ||
            code == NsfwErrorCodes.LEGACY_DEVICE_REVOKED
}
