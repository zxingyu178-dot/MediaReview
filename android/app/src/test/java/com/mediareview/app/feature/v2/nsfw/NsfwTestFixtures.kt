package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.NsfwApiException
import com.mediareview.app.feature.v2.nsfw.data.NsfwConnectionSnapshot
import com.mediareview.app.feature.v2.nsfw.data.NsfwDecisionResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationReceipt
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationRequest
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairingGateway
import com.mediareview.app.feature.v2.nsfw.data.NsfwProfile
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptImagesPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptSummary
import com.mediareview.app.feature.v2.nsfw.data.NsfwReviewDataSource
import com.mediareview.app.feature.v2.nsfw.data.NsfwStorePort
import com.mediareview.app.feature.v2.nsfw.network.NsfwApiFactory
import java.net.InetAddress
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient

/** 与 docs/integration/fixtures 对应的合成 fixture；不包含任何真实凭据或生产数据。 */
object NsfwJson {
    const val HEALTH_COMPATIBLE = """
{"success":true,"data":{"status":"ok","product":"nsfw-studio","version":"0.40.0","api_contract":2,
"capabilities":["image_review","review_session","favorite","thumbnail","preview","original",
"mobile_feed_v2","mobile_image_details_v2","mobile_image_decision_v2","mobile_prompt_review_v2","mobile_prompt_evaluation_v2"]},
"request_id":"req_health"}
"""

    const val HEALTH_CURRENT_BRIDGE = """
{"success":true,"data":{"status":"ok","product":"nsfw-studio","version":"0.13.0","api_contract":1,
"capabilities":["image_review","review_session","favorite","thumbnail","preview","original"]},
"request_id":"req_health_old"}
"""

    const val HEALTH_WRONG_PRODUCT = """
{"success":true,"data":{"status":"ok","product":"jellyfin","version":"10.9.0","api_contract":2,
"capabilities":["mobile_feed_v2","mobile_image_details_v2","mobile_image_decision_v2","mobile_prompt_review_v2","mobile_prompt_evaluation_v2"]},
"request_id":"req_health_wrong"}
"""

    const val PAIRING_OK = """
{"success":true,"data":{"device_id":"dev_1","installation_id":"inst-1","name":"测试手机","token":"nsfw-token-1","paired_at":"2026-10-28T12:00:00Z"},"request_id":"req_pair"}
"""

    const val FEED_PAGE1 = """
{"success":true,"data":{"items":[
{"image_id":"img_a","revision":"1","status":"NEW","is_favorite":false,"kind":"original","job_id":"job_1","seed":1001,
 "width":1024,"height":1024,"created_at":"2026-10-28T12:00:00Z","updated_at":"2026-10-28T12:00:01Z",
 "prompt":{"prompt_id":"prm_1","prompt_version_id":"prmv_1","link_state":"EXACT"},
 "thumbnail_url":"/mobile/images/img_a/thumbnail","preview_url":"/mobile/images/img_a/preview","original_url":"/mobile/images/img_a/original"},
{"image_id":"img_b","revision":"1","status":"NEW","is_favorite":false,"kind":"original","job_id":"job_1","seed":1002,
 "width":1024,"height":1024,"created_at":"2026-10-28T12:00:00Z","updated_at":"2026-10-28T12:00:01Z",
 "prompt":{"prompt_id":"prm_1","prompt_version_id":"prmv_1","link_state":"EXACT"},
 "thumbnail_url":"/mobile/images/img_b/thumbnail","preview_url":"/mobile/images/img_b/preview","original_url":"/mobile/images/img_b/original"},
{"image_id":"img_c","revision":"1","status":"NEW","is_favorite":false,"kind":"original","job_id":null,"seed":null,
 "width":768,"height":1024,"created_at":"2026-10-28T12:00:00Z","updated_at":"2026-10-28T12:00:01Z",
 "prompt":{"prompt_id":null,"prompt_version_id":null,"link_state":"UNLINKED"},
 "thumbnail_url":"/mobile/images/img_c/thumbnail","preview_url":"/mobile/images/img_c/preview","original_url":"/mobile/images/img_c/original"}],
"next_cursor":"c_page2","has_more":true,"resumed":false},"request_id":"req_feed1"}
"""

    const val FEED_PAGE2 = """
{"success":true,"data":{"items":[
{"image_id":"img_b","revision":"2","status":"KEEP","is_favorite":true,"kind":"original","job_id":"job_1","seed":1002,
 "width":1024,"height":1024,"created_at":"2026-10-28T12:00:00Z","updated_at":"2026-10-28T12:05:00Z",
 "prompt":{"prompt_id":"prm_1","prompt_version_id":"prmv_1","link_state":"EXACT"},
 "thumbnail_url":"/mobile/images/img_b/thumbnail","preview_url":"/mobile/images/img_b/preview","original_url":"/mobile/images/img_b/original"},
{"image_id":"img_d","revision":"1","status":"NEW","is_favorite":false,"kind":"upscaled","job_id":"job_2","seed":1004,
 "width":2048,"height":2048,"created_at":"2026-10-28T12:03:00Z","updated_at":"2026-10-28T12:03:01Z",
 "prompt":{"prompt_id":null,"prompt_version_id":null,"link_state":"UNLINKED"},
 "thumbnail_url":"/mobile/images/img_d/thumbnail","preview_url":"/mobile/images/img_d/preview","original_url":"/mobile/images/img_d/original"}],
"next_cursor":"c_page3","has_more":false,"resumed":true},"request_id":"req_feed2"}
"""

    const val DETAILS_EXACT = """
{"success":true,"data":{"image_id":"img_a","created_at":"2026-10-28T12:00:00Z","generated_at":"2026-10-28T12:00:02Z",
"width":1024,"height":1024,"positive_prompt":"生成时快照 v1","negative_prompt":"","prompt_id":"prm_1","prompt_version_id":"prmv_1",
"link_state":"EXACT","job_id":"job_1","module_id":"basic_generate","seed":1001,"model":"qwen-image-2.1",
"size_bytes":1048576,"mime":"image/webp","updated_at":"2026-10-28T12:00:03Z","revision":"1"},"request_id":"req_details"}
"""

    const val DETAILS_UNLINKED = """
{"success":true,"data":{"image_id":"img_c","created_at":"2026-10-28T12:00:00Z","generated_at":null,
"width":768,"height":1024,"positive_prompt":null,"negative_prompt":null,"prompt_id":null,"prompt_version_id":null,
"link_state":"UNLINKED","job_id":null,"module_id":null,"seed":null,"model":null,"size_bytes":null,"mime":null,
"updated_at":"2026-10-28T12:00:01Z","revision":"1"},"request_id":"req_details_unlinked"}
"""

    const val DECISION_OK = """
{"success":true,"data":{"image_id":"img_a","status":"KEEP","is_favorite":false,"updated_at":"2026-10-28T12:06:00Z","revision":"3"},"request_id":"req_decision"}
"""

    const val ERROR_CONFLICT = """
{"success":false,"error":{"code":"REVIEW_CONFLICT","message":"图片状态已被他处修改",
"details":{"image_id":"img_a","status":"KEEP","updated_at":"2026-10-28T12:06:00Z","revision":"3"}},"request_id":"req_conflict"}
"""

    const val ERROR_CURSOR_EXPIRED = """
{"success":false,"error":{"code":"CURSOR_EXPIRED","message":"游标已过期，请从头同步"},"request_id":"req_cursor"}
"""

    const val ERROR_AUTH_REVOKED = """
{"success":false,"error":{"code":"AUTH_REVOKED","message":"配对凭据已被吊销"},"request_id":"req_auth"}
"""

    const val PROMPT_SUMMARY = """
{"success":true,"data":{"prompt_id":"prm_1","prompt_version_id":"prmv_1","link_state":"EXACT","evaluation_count":3,
"version_latest":{"evaluation_id":"pev_7","overall_score":4,"prompt_version_id":"prmv_1","created_at":"2026-10-28T12:10:00Z","notes":"第 3 批 seed 更稳"},
"prompt_latest":{"evaluation_id":"pev_8","overall_score":3,"prompt_version_id":null,"created_at":"2026-10-28T12:20:00Z","notes":""},
"images_total":12,"seen_count":8,"keep_count":6,"reject_count":2,"has_new_samples":true},"request_id":"req_summary"}
"""

    const val PROMPT_SUMMARY_UNRESOLVED = """
{"success":true,"data":{"prompt_id":"prm_1","prompt_version_id":null,"link_state":"UNRESOLVED","evaluation_count":1,
"version_latest":null,"prompt_latest":{"evaluation_id":"pev_8","overall_score":3,"prompt_version_id":null,"created_at":"2026-10-28T12:20:00Z","notes":""},
"images_total":12,"seen_count":8,"keep_count":6,"reject_count":2,"has_new_samples":false},"request_id":"req_summary_unresolved"}
"""

    const val PROMPT_IMAGES = """
{"success":true,"data":{"items":[{"image_id":"img_a","thumbnail_url":"/mobile/images/img_a/thumbnail","status":"KEEP","is_current":true},
{"image_id":"img_b","thumbnail_url":"/mobile/images/img_b/thumbnail","status":"NEW","is_current":false}],
"total":2,"current_index":0,"next_cursor":null,"has_more":false},"request_id":"req_prompt_images"}
"""

    const val EVALUATION_CREATED = """
{"success":true,"data":{"evaluation_id":"pev_1","prompt_id":"prm_1","prompt_version_id":"prmv_1","overall_score":4,
"created_at":"2026-10-28T12:10:00Z","client_request_id":"req-eval-1"},"request_id":"req_eval"}
"""

    const val EVALUATION_RECEIPT_FOUND = """
{"success":true,"data":{"found":true,"evaluation":{"evaluation_id":"pev_1","prompt_id":"prm_1","prompt_version_id":"prmv_1",
"overall_score":4,"created_at":"2026-10-28T12:10:00Z","client_request_id":"req-eval-1"}},"request_id":"req_receipt"}
"""

    const val EVALUATION_RECEIPT_MISSING = """
{"success":true,"data":{"found":false,"evaluation":null},"request_id":"req_receipt_missing"}
"""
}

/** MockWebServer 专用：把任意测试域名解析到 127.0.0.1。 */
fun nsfwTestHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName("127.0.0.1"))
    })
    .build()

fun nsfwTestApiFactory(http: OkHttpClient = nsfwTestHttpClient()): NsfwApiFactory {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    return NsfwApiFactory(http, http, json)
}

class FakeNsfwStore(
    var baseUrl: String = "",
    var token: String = "",
    var installationId: String = "inst-1",
    var rejected: Boolean = false,
) : NsfwStorePort {
    var invalidated = false
    var cleared = false
    var savedToken: String? = null

    override suspend fun current(): NsfwProfile = NsfwProfile(baseUrl, token, rejected)

    override suspend fun saveBaseUrl(baseUrl: String) {
        this.baseUrl = baseUrl
    }

    override suspend fun savePairing(token: String) {
        this.token = token
        savedToken = token
    }

    override suspend fun invalidateCredential() {
        invalidated = true
        token = ""
    }

    override suspend fun installationId(): String = installationId

    override suspend fun clear() {
        cleared = true
        baseUrl = ""
        token = ""
    }
}

class FakePairingGateway(
    var snapshot: NsfwConnectionSnapshot = NsfwConnectionSnapshot(),
    var pairResult: NsfwPairResult = NsfwPairResult.Failed("未设置"),
) : NsfwPairingGateway {
    var pairCalls = 0
    var authRejectedCalls = 0
    var clearCalls = 0
    var lastBaseUrl = ""
    var lastCode = ""

    override suspend fun load(): NsfwConnectionSnapshot = snapshot

    override suspend fun checkAndPair(baseUrlInput: String, code: String): NsfwPairResult {
        pairCalls += 1
        lastBaseUrl = baseUrlInput
        lastCode = code
        return pairResult
    }

    override suspend fun clear() {
        clearCalls += 1
        snapshot = NsfwConnectionSnapshot()
    }

    override suspend fun handleAuthRejected() {
        authRejectedCalls += 1
        snapshot = NsfwConnectionSnapshot(rejected = true)
    }
}

class FakeNsfwDataSource : NsfwReviewDataSource {
    var feedResponder: suspend (String?) -> NsfwFeedPage = { NsfwFeedPage() }
    var detailsResponder: suspend (String) -> NsfwImageDetails = { NsfwImageDetails(imageId = it) }
    var decisionResponder: suspend (String, String, Boolean?) -> NsfwDecisionResult = { id, status, favorite ->
        NsfwDecisionResult(imageId = id, status = status, isFavorite = favorite ?: false, updatedAt = "2026-10-28T12:06:00Z", revision = "2")
    }

    var summaryResponder: suspend (String, String?) -> NsfwPromptSummary = { id, version ->
        NsfwPromptSummary(promptId = id, promptVersionId = version)
    }
    var promptImagesResponder: suspend (String, String?) -> NsfwPromptImagesPage = { _, _ -> NsfwPromptImagesPage() }
    var createEvaluationResponder: suspend (String, NsfwEvaluationRequest) -> NsfwEvaluationResult =
        { id, request -> NsfwEvaluationResult(evaluationId = "pev_1", promptId = id, promptVersionId = request.promptVersionId, overallScore = request.overallScore, clientRequestId = request.clientRequestId) }
    var receiptResponder: suspend (String) -> NsfwEvaluationReceipt = { NsfwEvaluationReceipt(found = false) }

    var feedCalls = 0
    var detailCalls = 0
    var decisionCalls = 0
    var createEvaluationCalls = 0
    var receiptCalls = 0
    var lastDecisionAction = ""
    var lastDecisionRequestId = ""
    var lastEvaluationRequest: NsfwEvaluationRequest? = null
    var lastFeedCursor: String? = ""

    override suspend fun feed(cursor: String?, limit: Int): NsfwFeedPage {
        feedCalls += 1
        lastFeedCursor = cursor
        return feedResponder(cursor)
    }

    override suspend fun details(imageId: String): NsfwImageDetails {
        detailCalls += 1
        return detailsResponder(imageId)
    }

    override suspend fun decide(
        imageId: String,
        action: String,
        favorite: Boolean?,
        expectedUpdatedAt: String,
        clientRequestId: String,
    ): NsfwDecisionResult {
        decisionCalls += 1
        lastDecisionAction = action
        lastDecisionRequestId = clientRequestId
        return decisionResponder(imageId, action, favorite)
    }

    override suspend fun promptSummary(promptId: String, versionId: String?): NsfwPromptSummary =
        summaryResponder(promptId, versionId)

    override suspend fun promptImages(
        promptId: String,
        versionId: String?,
        cursor: String?,
        limit: Int,
    ): NsfwPromptImagesPage = promptImagesResponder(promptId, versionId)

    override suspend fun createEvaluation(
        promptId: String,
        request: NsfwEvaluationRequest,
    ): NsfwEvaluationResult {
        createEvaluationCalls += 1
        lastEvaluationRequest = request
        return createEvaluationResponder(promptId, request)
    }

    override suspend fun evaluationReceipt(clientRequestId: String): NsfwEvaluationReceipt {
        receiptCalls += 1
        return receiptResponder(clientRequestId)
    }
}

fun nsfwItem(
    id: String,
    revision: String = "1",
    status: String = "NEW",
    favorite: Boolean = false,
    promptId: String? = "prm_1",
    versionId: String? = "prmv_1",
    linkState: String = "EXACT",
    updatedAt: String = "2026-10-28T12:00:01Z",
) = com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem(
    imageId = id,
    revision = revision,
    status = status,
    isFavorite = favorite,
    createdAt = "2026-10-28T12:00:00Z",
    updatedAt = updatedAt,
    prompt = com.mediareview.app.feature.v2.nsfw.data.NsfwPromptLink(promptId, versionId, linkState),
    thumbnailUrl = "/mobile/images/$id/thumbnail",
    previewUrl = "/mobile/images/$id/preview",
    originalUrl = "/mobile/images/$id/original",
)

fun nsfwError(code: String, message: String = "错误", status: Int = 0) = NsfwApiException(code, message, status)

fun nsfwDetails(
    id: String,
    promptId: String? = "prm_1",
    versionId: String? = "prmv_1",
    linkState: String = "EXACT",
    updatedAt: String = "2026-10-28T12:00:03Z",
    positivePrompt: String? = "生成时快照",
) = NsfwImageDetails(
    imageId = id,
    createdAt = "2026-10-28T12:00:00Z",
    generatedAt = "2026-10-28T12:00:02Z",
    width = 1024,
    height = 1024,
    positivePrompt = positivePrompt,
    negativePrompt = "",
    promptId = promptId,
    promptVersionId = versionId,
    linkState = linkState,
    updatedAt = updatedAt,
    revision = "1",
)

fun nsfwPage(
    items: List<com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem>,
    nextCursor: String? = null,
    hasMore: Boolean = false,
) = NsfwFeedPage(items = items, nextCursor = nextCursor, hasMore = hasMore)

