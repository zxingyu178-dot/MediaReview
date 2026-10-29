package com.mediareview.app.feature.v2.nsfw

import androidx.lifecycle.SavedStateHandle
import com.mediareview.app.feature.v2.nsfw.data.NsfwApiException
import com.mediareview.app.feature.v2.nsfw.data.NsfwConnectionSnapshot
import com.mediareview.app.feature.v2.nsfw.data.NsfwDecisionResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwErrorCodes
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationReceipt
import com.mediareview.app.feature.v2.nsfw.data.NsfwEvaluationRequest
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptImagesPage
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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

    private fun vm(savedState: SavedStateHandle = SavedStateHandle()): NsfwReviewViewModel =
        NsfwReviewViewModel(data, gateway, savedState, Json { ignoreUnknownKeys = true })

    private fun bootstrapPaired(
        page: com.mediareview.app.feature.v2.nsfw.data.NsfwFeedPage =
            nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))),
        details: com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails = nsfwDetails("img_a"),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): NsfwReviewViewModel {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { page }
        data.detailsResponder = { id -> details.copy(imageId = id) }
        return vm(savedState)
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
    fun `收藏只更新favorite且不改变NEW_REJECT_KEEP状态`() = runTest {
        listOf("NEW", "KEEP", "REJECT").forEach { originalStatus ->
            data = FakeNsfwDataSource()
            gateway = FakePairingGateway()
            data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a", status = originalStatus))) }
            data.detailsResponder = { id -> nsfwDetails(id) }
            data.decisionResponder = { id, _, favorite ->
                NsfwDecisionResult(
                    imageId = id,
                    status = originalStatus,
                    isFavorite = favorite ?: false,
                    updatedAt = "2026-10-28T12:06:00Z",
                    revision = "2",
                )
            }
            val vm = bootstrapPaired(
                page = nsfwPage(listOf(nsfwItem("img_a", status = originalStatus))),
            )
            vm.toggleFavorite()

            assertNull(data.lastDecisionActionNullable)
            assertEquals(true, vm.state.value.items.first { it.imageId == "img_a" }.isFavorite)
            assertEquals(originalStatus, vm.state.value.items.first { it.imageId == "img_a" }.status)
        }
    }

    @Test
    fun `审核成功后的旧feed页不覆盖新状态`() = runTest {
        val vm = bootstrapPaired(page = nsfwPage(listOf(nsfwItem("img_a", revision = "1"))))
        data.decisionResponder = { id, action, favorite ->
            NsfwDecisionResult(id, action ?: "NEW", favorite ?: false, "2026-10-28T12:06:00Z", "3")
        }
        vm.decision("KEEP")
        assertEquals("KEEP", vm.state.value.items.first().status)

        data.feedResponder = {
            nsfwPage(listOf(nsfwItem("img_a", revision = "2", status = "NEW")))
        }
        vm.refresh()

        assertEquals("KEEP", vm.state.value.items.first().status)
        assertEquals("3", vm.state.value.items.first().revision)
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
    fun `启动恢复发现旧Bridge时不进入审核页但保留已保存token`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(
            baseUrl = "http://nsfw.test:8767",
            paired = true,
            compatible = false,
            missingCapabilities = listOf("mobile_feed_v2"),
            message = "已保存的配对仍然有效，但当前 Bridge 不支持生图审核 v2",
        )
        val vm = vm()
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertEquals(listOf("mobile_feed_v2"), vm.state.value.missingCapabilities)
        assertEquals(0, data.feedCalls)
        assertEquals(0, gateway.clearCalls)
    }

    @Test
    fun `完成评分后清除持久化的未确认请求`() = runTest {
        val saved = SavedStateHandle()
        val vm = bootstrapPaired(savedState = saved)
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")
        assertNull(saved.get<String>("nsfw_pending_evaluation"))
        assertNotNull(vm.state.value.panel!!.submitted)
    }

    @Test
    fun `断开配对清空页面状态`() = runTest {
        val vm = bootstrapPaired()
        vm.clearPairing()
        assertEquals(1, gateway.clearCalls)
        assertEquals(NsfwPhase.UNPAIRED, vm.state.value.phase)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `页面可见时周期增量同步离开页面立即停止`() = runTest {
        val vm = bootstrapPaired()
        vm.pollIntervalMs = 1_000
        val before = data.feedCalls

        vm.setScreenVisible(true)
        dispatcher.scheduler.advanceTimeBy(1_100)
        dispatcher.scheduler.runCurrent()
        assertTrue(data.feedCalls > before)

        vm.setScreenVisible(false)
        val afterStop = data.feedCalls
        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(afterStop, data.feedCalls)
    }

    @Test
    fun `持续410只重置一次后明确报错不无限循环`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { throw NsfwApiException(NsfwErrorCodes.CURSOR_EXPIRED, "过期", 410) }
        val vm = vm()
        assertEquals(2, data.feedCalls)
        assertTrue(vm.state.value.message!!.contains("从头同步失败"))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `游标不推进时停止本轮同步并报错`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { cursor ->
            nsfwPage(listOf(nsfwItem("img_a")), nextCursor = cursor ?: "same", hasMore = true)
        }
        val vm = vm()
        assertEquals(2, data.feedCalls)
        assertTrue(vm.state.value.message!!.contains("游标未推进"))
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `超过250张跨页追平且尾态hasMore准确`() = runTest {
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { cursor ->
            val pageIndex = cursor?.removePrefix("c")?.toIntOrNull() ?: 0
            val start = pageIndex * 50
            val items = (start until start + 50).map { nsfwItem("img_$it") }
            val next = "c${pageIndex + 1}"
            nsfwPage(items, nextCursor = next, hasMore = pageIndex < 5)
        }
        data.detailsResponder = { id -> nsfwDetails(id) }
        val vm = vm()
        assertEquals(300, vm.state.value.items.size)
        assertEquals(6, data.feedCalls)
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `REMOVE当前图片后自动选择下一条`() = runTest {
        val vm = bootstrapPaired(page = nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))))
        data.feedResponder = {
            nsfwPage(listOf(nsfwItem("img_a", revision = "2").copy(event = "REMOVE")))
        }
        vm.refresh()
        assertEquals(listOf("img_b"), vm.state.value.items.map { it.imageId })
        assertEquals("img_b", vm.state.value.currentId)
        assertEquals("img_b", vm.state.value.detail?.imageId)
    }

    @Test
    fun `未确认评分修改内容必须换新请求号`() = runTest {
        val vm = bootstrapPaired()
        data.promptImagesResponder = { _, _ -> NsfwPromptImagesPage() }
        data.createEvaluationResponder = { _, _ -> throw NsfwApiException("NETWORK_ERROR", "超时") }
        data.receiptResponder = { NsfwEvaluationReceipt(found = false) }
        vm.openPromptPanel()
        vm.submitEvaluation(4, emptyMap(), "")
        val firstId = data.lastEvaluationRequest!!.clientRequestId
        assertTrue(vm.state.value.panel!!.unconfirmed)

        vm.submitEvaluation(2, emptyMap(), "")
        assertEquals(1, data.createEvaluationCalls)
        assertTrue(vm.state.value.panel!!.message!!.contains("新建评分请求"))

        vm.resetEvaluationDraft()
        val newId = vm.state.value.panel!!.requestId
        assertTrue(newId != firstId)
        vm.submitEvaluation(2, emptyMap(), "")
        assertEquals(2, data.createEvaluationCalls)
        assertEquals(newId, data.lastEvaluationRequest!!.clientRequestId)
    }

    @Test
    fun `进程重启后未确认评分只提示不自动重发`() = runTest {
        val pending = NsfwPendingEvaluation(
            promptId = "prm_1",
            versionId = "prmv_1",
            request = NsfwEvaluationRequest(
                promptVersionId = "prmv_1",
                overallScore = 4,
                dimensions = mapOf("subject" to 4),
                notes = "",
                clientRequestId = "req-pending",
            ),
        )
        val pendingJson = Json { ignoreUnknownKeys = true }
            .encodeToString(NsfwPendingEvaluation.serializer(), pending)
        val saved = SavedStateHandle(mapOf("nsfw_pending_evaluation" to pendingJson))
        val vm = bootstrapPaired(savedState = saved)
        assertTrue(vm.state.value.evaluationRecoveryHint!!.contains("req-pending"))
        assertEquals(0, data.createEvaluationCalls)
        assertEquals(0, data.receiptCalls)
    }

    @Test
    fun `快速切图时旧详情回包不覆盖新图`() = runTest {
        val firstDetails = CompletableDeferred<com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails>()
        var detailCall = 0
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"), nsfwItem("img_b"))) }
        data.detailsResponder = { id ->
            detailCall += 1
            if (detailCall == 1) firstDetails.await() else nsfwDetails("img_b")
        }
        val vm = vm()
        assertEquals("img_a", vm.state.value.currentId)
        assertTrue(vm.state.value.detailLoading)

        vm.select("img_b")
        assertEquals("img_b", vm.state.value.detail?.imageId)
        firstDetails.complete(nsfwDetails("img_a"))
        assertEquals("img_b", vm.state.value.detail?.imageId)
        assertFalse(vm.state.value.detailLoading)
    }

    @Test
    fun `关闭评分面板后旧面板回包被丢弃`() = runTest {
        val summaryDeferred = CompletableDeferred<NsfwPromptSummary>()
        gateway.snapshot = NsfwConnectionSnapshot(baseUrl = "http://nsfw.test:8767", paired = true)
        data.feedResponder = { nsfwPage(listOf(nsfwItem("img_a"))) }
        data.detailsResponder = { id -> nsfwDetails(id) }
        data.summaryResponder = { _, _ -> summaryDeferred.await() }
        val vm = vm()
        vm.openPromptPanel()
        assertNotNull(vm.state.value.panel)
        vm.closePromptPanel()
        summaryDeferred.complete(NsfwPromptSummary(promptId = "prm_1", promptVersionId = "prmv_1"))
        assertNull(vm.state.value.panel)
    }
}
