package com.mediareview.app.feature.v2.nsfw.data

import com.mediareview.app.feature.v2.nsfw.NsfwJson
import com.mediareview.app.feature.v2.nsfw.nsfwTestApiFactory
import com.mediareview.app.feature.v2.nsfw.nsfwTestHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2 端点合同测试（MockWebServer 真实 HTTP）：
 * 路径/参数/字段解析/错误码全部按 docs/integration/NSFW_MOBILE_API_CONTRACT_V2.md；
 * 这里证明"客户端契约与模拟接口"可用，不代表真实 Bridge 已实现。
 */
class NsfwRepositoryContractTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var repository: NsfwRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = "http://nsfw.test:${server.port}"
        val factory = nsfwTestApiFactory(nsfwTestHttpClient())
        repository = NsfwRepository(NsfwBaseUrlPort { baseUrl }, factory)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun okay(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(status: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    // ---------- feed ----------

    @Test
    fun `feed首屏不带cursor并解析分页字段`() = runTest {
        server.enqueue(okay(NsfwJson.FEED_PAGE1))

        val page = repository.feed(null, 50)

        assertEquals(3, page.items.size)
        assertEquals("c_page2", page.nextCursor)
        assertTrue(page.hasMore)
        assertEquals("img_a", page.items.first().imageId)
        assertEquals("EXACT", page.items.first().prompt?.linkState)
        assertTrue(page.items[2].prompt?.isUnlinked == true)

        val request = server.takeRequest()
        assertEquals("/mobile/images/feed", request.requestUrl?.encodedPath)
        assertEquals("50", request.requestUrl?.queryParameter("limit"))
        assertNull(request.requestUrl?.queryParameter("cursor"))
    }

    @Test
    fun `feed携带稳定游标请求下一页`() = runTest {
        server.enqueue(okay(NsfwJson.FEED_PAGE2))

        val page = repository.feed("c_page2", 50)

        assertEquals(2, page.items.size)
        assertEquals("c_page3", page.nextCursor)
        assertFalse(page.hasMore)
        assertEquals("2", page.items.first().revision)
        assertEquals("KEEP", page.items.first().status)
        val request = server.takeRequest()
        assertEquals("c_page2", request.requestUrl?.queryParameter("cursor"))
    }

    @Test
    fun `feed解析REMOVE墓碑事件`() = runTest {
        server.enqueue(
            okay(
                """{"success":true,"data":{"items":[{"image_id":"img_a","revision":"2","event":"REMOVE","status":"NEW","is_favorite":false,"kind":"original","created_at":"2026-10-28T12:00:00Z","updated_at":"2026-10-28T12:05:00Z","thumbnail_url":"","preview_url":"","original_url":""}],"next_cursor":"c2","has_more":false,"resumed":true},"request_id":"r"}""",
            ),
        )
        val page = repository.feed("c1", 50)
        assertEquals(NsfwFeedEvents.REMOVE, page.items.single().event)
    }

    @Test
    fun `游标过期映射为CURSOR_EXPIRED且状态410`() = runTest {
        server.enqueue(error(410, NsfwJson.ERROR_CURSOR_EXPIRED))
        val failure = runCatching { repository.feed("old", 50) }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.CURSOR_EXPIRED, (failure as NsfwApiException).code)
        assertEquals(410, failure.httpStatus)
    }

    @Test
    fun `404按路由区分图片和提示词不存在而不是能力缺失`() = runTest {
        server.enqueue(error(404, """{"success":false,"error":{"code":"","message":"not found"},"request_id":"r"}"""))
        val imageFailure = runCatching { repository.details("img_missing") }.exceptionOrNull()
        assertTrue(imageFailure is NsfwApiException)
        assertEquals(NsfwErrorCodes.IMAGE_NOT_FOUND, (imageFailure as NsfwApiException).code)

        server.enqueue(error(404, """{"success":false,"error":{"code":"","message":"not found"},"request_id":"r"}"""))
        val promptFailure = runCatching { repository.promptSummary("prm_missing", null) }.exceptionOrNull()
        assertTrue(promptFailure is NsfwApiException)
        assertEquals(NsfwErrorCodes.PROMPT_NOT_FOUND, (promptFailure as NsfwApiException).code)

        server.enqueue(error(404, """{"success":false,"error":{"code":"","message":"not found"},"request_id":"r"}"""))
        val feedFailure = runCatching { repository.feed(null, 50) }.exceptionOrNull()
        assertTrue(feedFailure is NsfwApiException)
        assertEquals("HTTP_404", (feedFailure as NsfwApiException).code)
    }

    @Test
    fun `Bridge旧401码映射为AUTH_REQUIRED或AUTH_REVOKED`() = runTest {
        server.enqueue(error(401, """{"success":false,"error":{"code":"UNAUTHORIZED","message":"未授权"},"request_id":"r"}"""))
        val unauthorized = runCatching { repository.feed(null, 50) }.exceptionOrNull()
        assertTrue(unauthorized is NsfwApiException)
        assertEquals(NsfwErrorCodes.AUTH_REQUIRED, (unauthorized as NsfwApiException).code)
        assertTrue(unauthorized.isAuthRejected)

        server.enqueue(error(401, """{"success":false,"error":{"code":"DEVICE_REVOKED","message":"设备吊销"},"request_id":"r"}"""))
        val revoked = runCatching { repository.feed(null, 50) }.exceptionOrNull()
        assertTrue(revoked is NsfwApiException)
        assertEquals(NsfwErrorCodes.AUTH_REVOKED, (revoked as NsfwApiException).code)
        assertTrue((revoked as NsfwApiException).isAuthRejected)
    }

    @Test
    fun `409按路由区分审核冲突与评分幂等冲突`() = runTest {
        server.enqueue(error(409, """{"success":false,"error":{"code":"","message":"conflict"},"request_id":"r"}"""))
        val decision = runCatching {
            repository.decide("img_a", "KEEP", null, "2026-10-28T12:00:03Z", "req-3")
        }.exceptionOrNull()
        assertEquals(NsfwErrorCodes.REVIEW_CONFLICT, (decision as NsfwApiException).code)

        server.enqueue(error(409, """{"success":false,"error":{"code":"","message":"conflict"},"request_id":"r"}"""))
        val evaluation = runCatching {
            repository.createEvaluation(
                "prm_1",
                NsfwEvaluationRequest(
                    promptVersionId = "prmv_1",
                    overallScore = 4,
                    clientRequestId = "req-eval-9",
                ),
            )
        }.exceptionOrNull()
        assertEquals(NsfwErrorCodes.IDEMPOTENCY_CONFLICT, (evaluation as NsfwApiException).code)
    }

    @Test
    fun `401映射为凭据失效`() = runTest {
        server.enqueue(error(401, NsfwJson.ERROR_AUTH_REVOKED))
        val failure = runCatching { repository.feed(null, 50) }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertTrue((failure as NsfwApiException).isAuthRejected)
    }

    // ---------- details ----------

    @Test
    fun `图片详情返回生成快照与精确关联`() = runTest {
        server.enqueue(okay(NsfwJson.DETAILS_EXACT))
        val detail = repository.details("img_a")
        assertEquals("生成时快照 v1", detail.positivePrompt)
        assertEquals("", detail.negativePrompt)
        assertEquals("prm_1", detail.promptId)
        assertEquals("prmv_1", detail.promptVersionId)
        assertEquals("EXACT", detail.linkState)
        assertEquals("/mobile/images/img_a/details", server.takeRequest().path)
    }

    @Test
    fun `详情返回的image_id与请求不一致时拒绝`() = runTest {
        server.enqueue(okay(NsfwJson.DETAILS_EXACT)) // 返回 img_a
        val failure = runCatching { repository.details("img_expected_other") }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.VALIDATION_ERROR, (failure as NsfwApiException).code)
        assertTrue(failure.message.contains("身份不一致"))
    }

    @Test
    fun `未关联图片详情仍可查看但prompt为null`() = runTest {
        server.enqueue(okay(NsfwJson.DETAILS_UNLINKED))
        val detail = repository.details("img_c")
        assertNull(detail.promptId)
        assertNull(detail.promptVersionId)
        assertNull(detail.positivePrompt)
        assertTrue(detail.promptLink.isUnlinked)
    }

    // ---------- decision ----------

    @Test
    fun `审核请求携带动作权威时间与幂等键`() = runTest {
        server.enqueue(okay(NsfwJson.DECISION_OK))
        val result = repository.decide(
            imageId = "img_a",
            action = "KEEP",
            favorite = true,
            expectedUpdatedAt = "2026-10-28T12:00:03Z",
            clientRequestId = "req-decision-1",
        )
        assertEquals("KEEP", result.status)
        assertEquals("3", result.revision)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"action\":\"KEEP\""))
        assertTrue(body.contains("\"favorite\":true"))
        assertTrue(body.contains("\"expected_updated_at\":\"2026-10-28T12:00:03Z\""))
        assertTrue(body.contains("\"client_request_id\":\"req-decision-1\""))
        assertFalse(body.contains("DELETE"))
    }

    @Test
    fun `favorite-only请求不带action且保留审核状态`() = runTest {
        server.enqueue(okay("""{"success":true,"data":{"image_id":"img_a","status":"REJECT","is_favorite":true,"updated_at":"2026-10-28T12:06:00Z","revision":"4"},"request_id":"req"}"""))
        val result = repository.decide(
            imageId = "img_a",
            action = null,
            favorite = true,
            expectedUpdatedAt = "2026-10-28T12:00:03Z",
            clientRequestId = "req-fav-1",
        )
        assertEquals("REJECT", result.status)
        assertTrue(result.isFavorite)
        val body = server.takeRequest().body.readUtf8()
        assertFalse("body=$body", body.contains("\"action\""))
        assertTrue("body=$body", body.contains("\"favorite\":true"))
    }

    @Test
    fun `action和favorite同时为空时客户端拒绝`() = runTest {
        val failure = runCatching {
            repository.decide("img_a", null, null, "2026-10-28T12:00:03Z", "req-x")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `409冲突解析为REVIEW_CONFLICT不抛原始HTTP`() = runTest {
        server.enqueue(error(409, NsfwJson.ERROR_CONFLICT))
        val failure = runCatching {
            repository.decide("img_a", "REJECT", null, "2026-10-28T12:00:03Z", "req-2")
        }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.REVIEW_CONFLICT, (failure as NsfwApiException).code)
        assertEquals(409, failure.httpStatus)
    }

    // ---------- prompt review ----------

    @Test
    fun `提示词版本摘要区分版本评分与整体评分`() = runTest {
        server.enqueue(okay(NsfwJson.PROMPT_SUMMARY))
        val summary = repository.promptSummary("prm_1", "prmv_1")
        assertEquals(4, summary.versionLatest?.overallScore)
        assertEquals("prmv_1", summary.versionLatest?.promptVersionId)
        assertEquals(3, summary.promptLatest?.overallScore)
        assertNull(summary.promptLatest?.promptVersionId)
        assertEquals(12, summary.imagesTotal)
        assertEquals(8, summary.seenCount)
        assertTrue(summary.hasNewSamples)
        val request = server.takeRequest()
        assertEquals("/mobile/prompts/prm_1/review-summary", request.requestUrl?.encodedPath)
        assertEquals("prmv_1", request.requestUrl?.queryParameter("version_id"))
    }

    @Test
    fun `提示词图片列表只取该版本且有界`() = runTest {
        server.enqueue(okay(NsfwJson.PROMPT_IMAGES))
        val page = repository.promptImages("prm_1", "prmv_1", cursor = null, limit = 30)
        assertEquals(2, page.items.size)
        assertEquals(2, page.total)
        assertTrue(page.items.first().isCurrent)
        val request = server.takeRequest()
        assertEquals("/mobile/prompts/prm_1/images", request.requestUrl?.encodedPath)
        assertEquals("prmv_1", request.requestUrl?.queryParameter("version_id"))
        assertEquals("30", request.requestUrl?.queryParameter("limit"))
    }

    // ---------- evaluation ----------

    @Test
    fun `评分只提交用户填写的维度`() = runTest {
        server.enqueue(okay(NsfwJson.EVALUATION_CREATED))
        val result = repository.createEvaluation(
            "prm_1",
            NsfwEvaluationRequest(
                promptVersionId = "prmv_1",
                overallScore = 4,
                dimensions = mapOf("subject" to 4, "lighting" to 3),
                notes = "第 3 批更稳",
                clientRequestId = "req-eval-1",
            ),
        )
        assertEquals("pev_1", result.evaluationId)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"overall_score\":4"))
        assertTrue(body.contains("\"subject\":4"))
        assertTrue(body.contains("\"lighting\":3"))
        assertFalse(body.contains("clothing"))
        assertFalse(body.contains("\"scene\""))
        assertTrue(body.contains("\"client_request_id\":\"req-eval-1\""))
    }

    @Test
    fun `仅总分时dimensions为null`() = runTest {
        server.enqueue(
            okay(
                """{"success":true,"data":{"evaluation_id":"pev_2","prompt_id":"prm_1","prompt_version_id":null,"overall_score":5,"created_at":"2026-10-28T12:10:00Z","client_request_id":"req-eval-2"},"request_id":"req_eval"}""",
            ),
        )
        repository.createEvaluation(
            "prm_1",
            NsfwEvaluationRequest(
                promptVersionId = null,
                overallScore = 5,
                dimensions = null,
                notes = "",
                clientRequestId = "req-eval-2",
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        // DTO 的可选字段带默认值：未填写时序列化会省略键（等价于 contract 的 null），
        // 绝不能出现默认 3 分或白名单外的键。
        assertFalse("body=$body", body.contains("\"dimensions\""))
        assertFalse("body=$body", body.contains("\"prompt_version_id\""))
        assertFalse("body=$body", body.contains("\"notes\""))
        assertTrue("body=$body", body.contains("\"overall_score\":5"))
    }

    @Test
    fun `评分回执查询可用于超时核对`() = runTest {
        server.enqueue(okay(NsfwJson.EVALUATION_RECEIPT_FOUND))
        val found = repository.evaluationReceipt("req-eval-1")
        assertTrue(found.found)
        assertEquals("pev_1", found.evaluation?.evaluationId)
        assertEquals("/mobile/prompts/evaluations/req-eval-1", server.takeRequest().path)

        server.enqueue(okay(NsfwJson.EVALUATION_RECEIPT_MISSING))
        val missing = repository.evaluationReceipt("req-eval-404")
        assertFalse(missing.found)
        assertNull(missing.evaluation)
    }

    @Test
    fun `非法分数或未知维度在客户端提前拒绝`() = runTest {
        assertValidationError(0)
        assertValidationError(6)
        val unknown = runCatching {
            repository.createEvaluation(
                "prm_1",
                NsfwEvaluationRequest(
                    promptVersionId = "prmv_1",
                    overallScore = 4,
                    dimensions = mapOf("unknown_dimension" to 3),
                    clientRequestId = "req-3",
                ),
            )
        }.exceptionOrNull()
        assertTrue(unknown is NsfwApiException)
        assertEquals(NsfwErrorCodes.VALIDATION_ERROR, (unknown as NsfwApiException).code)
        assertEquals(0, server.requestCount)
    }

    private suspend fun assertValidationError(score: Int) {
        val failure = runCatching {
            repository.createEvaluation(
                "prm_1",
                NsfwEvaluationRequest(
                    promptVersionId = "prmv_1",
                    overallScore = score,
                    clientRequestId = "req-x",
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.VALIDATION_ERROR, (failure as NsfwApiException).code)
    }

    @Test
    fun `缺少image_id或prompt_id时不发请求`() = runTest {
        val failure = runCatching { repository.details("") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    // ---------- 响应身份校验 ----------

    @Test
    fun `审核响应image_id与请求不一致时拒绝`() = runTest {
        server.enqueue(
            okay(
                """{"success":true,"data":{"image_id":"img_b","status":"KEEP","is_favorite":false,"updated_at":"2026-10-28T12:06:00Z","revision":"3"},"request_id":"r"}""",
            ),
        )
        val failure = runCatching {
            repository.decide("img_a", "KEEP", null, "2026-10-28T12:00:03Z", "req-identity")
        }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.RESPONSE_MISMATCH, (failure as NsfwApiException).code)
        assertTrue(failure.message.contains("身份不一致"))
    }

    @Test
    fun `评分响应prompt_id不一致时拒绝`() = runTest {
        server.enqueue(
            okay(
                """{"success":true,"data":{"evaluation_id":"pev_1","prompt_id":"prm_other","prompt_version_id":"prmv_1","overall_score":4,"created_at":"2026-10-28T12:10:00Z","client_request_id":"req-eval-identity"},"request_id":"r"}""",
            ),
        )
        val failure = runCatching {
            repository.createEvaluation(
                "prm_1",
                NsfwEvaluationRequest(
                    promptVersionId = "prmv_1",
                    overallScore = 4,
                    clientRequestId = "req-eval-identity",
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.RESPONSE_MISMATCH, (failure as NsfwApiException).code)
    }

    @Test
    fun `评分响应client_request_id不一致时拒绝`() = runTest {
        server.enqueue(
            okay(
                """{"success":true,"data":{"evaluation_id":"pev_1","prompt_id":"prm_1","prompt_version_id":"prmv_1","overall_score":4,"created_at":"2026-10-28T12:10:00Z","client_request_id":"req-other"},"request_id":"r"}""",
            ),
        )
        val failure = runCatching {
            repository.createEvaluation(
                "prm_1",
                NsfwEvaluationRequest(
                    promptVersionId = "prmv_1",
                    overallScore = 4,
                    clientRequestId = "req-eval-identity",
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.RESPONSE_MISMATCH, (failure as NsfwApiException).code)
        assertTrue(failure.message.contains("请求号不一致"))
    }

    @Test
    fun `评分响应版本不一致时拒绝`() = runTest {
        server.enqueue(
            okay(
                """{"success":true,"data":{"evaluation_id":"pev_1","prompt_id":"prm_1","prompt_version_id":"prmv_2","overall_score":4,"created_at":"2026-10-28T12:10:00Z","client_request_id":"req-eval-identity"},"request_id":"r"}""",
            ),
        )
        val failure = runCatching {
            repository.createEvaluation(
                "prm_1",
                NsfwEvaluationRequest(
                    promptVersionId = "prmv_1",
                    overallScore = 4,
                    clientRequestId = "req-eval-identity",
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.RESPONSE_MISMATCH, (failure as NsfwApiException).code)
        assertTrue(failure.message.contains("版本不一致"))
    }

    @Test
    fun `回执client_request_id不一致时拒绝`() = runTest {
        server.enqueue(
            okay(
                """{"success":true,"data":{"found":true,"evaluation":{"evaluation_id":"pev_1","prompt_id":"prm_1","prompt_version_id":"prmv_1","overall_score":4,"created_at":"2026-10-28T12:10:00Z","client_request_id":"req-other"}},"request_id":"r"}""",
            ),
        )
        val failure = runCatching { repository.evaluationReceipt("req-eval-1") }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals(NsfwErrorCodes.RESPONSE_MISMATCH, (failure as NsfwApiException).code)
    }

    @Test
    fun `提示词汇总prompt_id或版本不一致时拒绝`() = runTest {
        server.enqueue(
            okay(
                """{"success":true,"data":{"prompt_id":"prm_other","prompt_version_id":"prmv_1","link_state":"EXACT","evaluation_count":0},"request_id":"r"}""",
            ),
        )
        val promptFailure = runCatching { repository.promptSummary("prm_1", "prmv_1") }.exceptionOrNull()
        assertTrue(promptFailure is NsfwApiException)
        assertEquals(NsfwErrorCodes.RESPONSE_MISMATCH, (promptFailure as NsfwApiException).code)

        server.enqueue(okay(NsfwJson.PROMPT_SUMMARY))
        val versionFailure = runCatching { repository.promptSummary("prm_1", null) }.exceptionOrNull()
        assertTrue(versionFailure is NsfwApiException)
        assertEquals(NsfwErrorCodes.RESPONSE_MISMATCH, (versionFailure as NsfwApiException).code)
        assertTrue(versionFailure.message.contains("版本不一致"))
    }

    @Test
    fun `网络不可达映射为NETWORK_ERROR`() = runTest {
        server.shutdown()
        val failure = runCatching { repository.feed(null, 50) }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals("NETWORK_ERROR", (failure as NsfwApiException).code)
        assertFalse((failure as NsfwApiException).isAuthRejected)
    }

    @Test
    fun `success为false的200响应也映射业务错误`() = runTest {
        server.enqueue(okay("""{"success":false,"error":{"code":"PROMPT_UNLINKED","message":"未关联"},"request_id":"r"}"""))
        val failure = runCatching { repository.promptSummary("prm_1", null) }.exceptionOrNull()
        assertTrue(failure is NsfwApiException)
        assertEquals("PROMPT_UNLINKED", (failure as NsfwApiException).code)
    }
}
