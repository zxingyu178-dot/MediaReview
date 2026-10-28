package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.NsfwApiException
import com.mediareview.app.feature.v2.nsfw.data.NsfwConnectionSnapshot
import com.mediareview.app.feature.v2.nsfw.data.NsfwErrorCodes
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationReceipt
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptImagesPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NsfwReviewViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var data: FakeNsfwDataSource
    private lateinit var gateway: FakePairingGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        data = FakeNsfwDataSource()
        gateway = FakePairingGateway()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = NsfwReviewViewModel(data, gateway)

    private fun bootstrapPaired(
        page: com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage =
            nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))),
        details: com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails = nsfwDetails("img_a"),
    ): NsfwReviewViewModel {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { page }
        data.detailsResponder = { id -> details.copy(imageId = id) }
        return vm()
    }

    @Test
    fun `未配对时进入配对页`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot()
        val vm = vm()
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertEquals(0, data.feedCalls)
    }

    @Test
    fun `已配对时加载feed并选中第一张`() = runTest {
        val vm = bootstrapPaired()
        val state = vm.state.value
        assertEquals(NsfwPhase.READY, state.phase)
        assertEquals(listOf("img_a", "img_b"), state.items.map { it.imageId })
        assertEquals("img_a", state.currentId)
        assertEquals("img_a", state.detail?.imageId)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `配对成功后加载数据且地址写入状态`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot()
        gateway.pairResult = NsfwPairResult.Paired("http://nsfw.test:8767", "dev_1")
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"))) }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        vm.pair("nsfw.test:8767", "123456")
        assertEquals(NsfwPhase.READY, vm.state.value.phase)
        assertEquals(1, gateway.pairCalls)
        assertEquals("http://nsfw.test:8767", vm.state.value.baseUrl)
        assertEquals("img_a", vm.state.value.currentId)
    }

    @Test
    fun `能力不足时停留在配对页并保留缺失项`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot()
        gateway.pairResult = NsfwPairResult.Blocked(
            product = "nsfw-studio",
            apiContract = 1,
            missingCapabilities = listOf("mobile_feed_v2"),
            message = "当前 NSFW Bridge 不支持生图审核 v2",
        )
        val vm = vm()
        vm.pair("nsfw.test:8767", "123456")
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertEquals(listOf("mobile_feed_v2"), vm.state.value.missingCapabilities)
        assertTrue(vm.state.value.message!!.contains("不支持"))
    }

    @Test
    fun `多页增量自动追平且保持当前图片`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { cursor ->
            if (cursor == null) {
                nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b")), nextCursor = "c2", hasMore = true)
            } else {
                nsfwPage(listOf(nsfwItem("img_b", revision = "2", status = "KEEP"), nsfwItem("img_c")), hasMore = false)
            }
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertEquals(listOf("img_a", "img_b", "img_c"), vm.state.value.items.map { it.imageId })
        assertEquals("img_a", vm.state.value.currentId)
        assertEquals("KEEP", vm.state.value.items.first { it.imageId == "img_b" }.status)
        assertEquals(2, data.feedCalls)
    }

    @Test
    fun `刷新新图只追加到队尾且当前图片不变`() = runTest {
        val vm = bootstrapPaired()
        val beforeIndex = vm.state.value.currentIndex

        data.feedResponder = {
            nsfwPage(listOf(nsfwItem("img_a", revision = "2", status = "REJECT"), nsfwItem("img_c")))
        }
        vm.refresh()

        assertEquals(listOf("img_a", "img_b", "img_c"), vm.state.value.items.map { it.imageId })
        assertEquals(beforeIndex, vm.state.value.currentIndex)
        assertEquals("img_a", vm.state.value.currentId)
        assertEquals("REJECT", vm.state.value.items.first { it.imageId == "img_a" }.status)
        assertTrue(vm.state.value.newImageCount >= 1)
    }

    @Test
    fun `游标过期后从头同步且不打断当前浏览`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        var calls = 0
        data.feedResponder = {
            calls += 1
            if (calls == 1) {
                throw NsfwApiException(NsfwErrorCodes.CURSOR_EXPIRED, "游标已过期", 410)
            }
            nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b")))
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertTrue(vm.state.value.cursorExpired)
        assertEquals(listOf("img_a", "img_b"), vm.state.value.items.map { it.imageId })
        assertEquals("img_a", vm.state.value.currentId)
        assertEquals(2, data.feedCalls)
    }

    @Test
    fun `审核409只刷新权威状态不自动重提`() = runTest {
        data.decisionResponder = { _, _, _ ->
            throw NsfwApiException(NsfwErrorCodes.REVIEW_CONFLICT, "冲突", 409)
        }
        val vm = bootstrapPaired()
        val detailCallsBefore = data.detailCalls

        vm.decision("KEEP")

        assertEquals(1, data.decisionCalls)
        assertNotNull(vm.state.value.conflictMessage)
        assertEquals(detailCallsBefore + 1, data.detailCalls)
        assertEquals("NEW", vm.state.value.items.first { it.imageId == "img_a" }.status)
    }

    @Test
    fun `审核成功后原位更新当前图片`() = runTest {
        val vm = bootstrapPaired()
        vm.decision("KEEP")
        val item = vm.state.value.items.first { it.imageId == "img_a" }
        assertEquals("KEEP", item.status)
        assertEquals("2", item.revision)
    }

    @Test
    fun `未关联图片不允许打开评分面板`() = runTest {
        val vm = bootstrapPaired(details = nsfwDetails("img_a", promptId = null, versionId = null, linkState = "UNLINKED"))
        vm.openPromptPanel()
        assertNull(vm.state.value.panel)
        assertTrue(vm.state.value.message!!.contains("没有可靠的提示词关联"))
    }

    @Test
    fun `版本未知时必须显式选择整体评分才能提交`() = runTest {
        val vm = bootstrapPaired(
            details = nsfwDetails("img_a", promptId = "prm_1", versionId = null, linkState = "UNRESOLVED"),
        )
        data.summaryResponder = { id, version -> NsfwPromptSummary(promptId = id, promptVersionId = version) }
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }

        vm.openPromptPanel()
        val panel = vm.state.value.panel!!
        assertNull(panel.versionId)
        assertFalse(panel.scoringEnabled)

        vm.submitEvaluation(4, emptyMap(), "")
        assertEquals(0, data.createEvaluationCalls)
        assertTrue(vm.state.value.panel!!.message!!.contains("请先选择评分范围"))

        vm.confirmWholePromptScope()
        assertTrue(vm.state.value.panel!!.scoringEnabled)
        vm.submitEvaluation(4, mapOf("subject" to 4), "ok")
        assertEquals(1, data.createEvaluationCalls)
        assertNull(data.lastEvaluationRequest!!.promptVersionId)
        assertEquals(mapOf("subject" to 4), data.lastEvaluationRequest!!.dimensions)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `精确版本评分使用版本ID且只传填写维度`() = runTest {
        val vm = bootstrapPaired()
        data.summaryResponder = { id, version -> NsfwPromptSummary(promptId = id, promptVersionId = version) }
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        assertEquals("prmv_1", vm.state.value.panel!!.versionId)
        assertTrue(vm.state.value.panel!!.scoringEnabled)

        vm.submitEvaluation(5, mapOf("lighting" to 3), "备注")

        assertEquals("prmv_1", data.lastEvaluationRequest!!.promptVersionId)
        assertEquals(mapOf("lighting" to 3), data.lastEvaluationRequest!!.dimensions)
        assertEquals("备注", data.lastEvaluationRequest!!.notes)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `评分超时先用同一请求号查回执不重复提交`() = runTest {
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        data.createEvaluationResponder = { _, _ -> throw NsfwApiException("NETWORK_ERROR", "超时") }
        data.receiptResponder = { requestId ->
            NsfwEvaluationReceipt(
                found = true,
                evaluation = com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult(
                    evaluationId = "pev_9",
                    promptId = "prm_1",
                    promptVersionId = "prmv_1",
                    overallScore = 4,
                    clientRequestId = requestId,
                ),
            )
        }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        assertEquals(1, data.createEvaluationCalls)
        assertEquals(1, data.receiptCalls)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `评分结果未知时标记未确认并复用同一请求号重试`() = runTest {
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        var attempt = 0
        data.createEvaluationResponder = { id, request ->
            attempt += 1
            if (attempt == 1) throw NsfwApiException("NETWORK_ERROR", "超时")
            com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationResult(
                evaluationId = "pev_1", promptId = id, promptVersionId = request.promptVersionId,
                overallScore = request.overallScore, clientRequestId = request.clientRequestId,
            )
        }
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")

        val firstId = data.lastEvaluationRequest!!.clientRequestId
        assertTrue(vm.state.value.panel!!.unconfirmed)
        assertNull(vm.state.value.panel!!.submitted)

        vm.submitEvaluation(4, emptyMap(), "")
        assertEquals(firstId, data.lastEvaluationRequest!!.clientRequestId)
        assertEquals(2, data.createEvaluationCalls)
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `详情401只清NSFW并回到配对页`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"))) }
        data.detailsResponder = { throw NsfwApiException(NsfwErrorCodes.AUTH_REVOKED, "吊销", 401) }
        val vm = vm()
        assertEquals(1, gateway.authRejectedCalls)
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertTrue(vm.state.value.message!!.contains("MediaReview 服务器不受影响"))
    }

    @Test
    fun `断开配对清空页面状态`() = runTest {
        val vm = bootstrapPaired()
        vm.clearPairing()
        assertEquals(1, gateway.clearCalls)
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertTrue(vm.state.value.items.isEmpty())
    }
}
