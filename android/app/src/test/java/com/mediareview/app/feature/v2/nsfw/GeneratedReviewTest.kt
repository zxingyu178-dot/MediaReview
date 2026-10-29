package com.mediareview.app.feature.v2.nsfw

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GeneratedReviewTest {
    private fun image(id: String, prompt: String? = null) = GeneratedImage(id, null, null, null, prompt)

    @Test fun `分页更新原位并追加 空增量保留当前图`() = runTest {
        val repository = FakeGeneratedRepository()
        val controller = GeneratedReviewController(repository)
        repository.pages += GeneratedPage(listOf(image("a"), image("b")), "p2", "delta1")
        controller.refresh()
        controller.select("b")
        repository.pages += GeneratedPage(listOf(image("b", "新版"), image("c")), null, "delta2")
        controller.loadMore()
        repository.pages += GeneratedPage(emptyList(), null, "delta3")
        controller.refresh()
        assertEquals(listOf("a", "b", "c"), controller.state.value.queue.items.map { it.id })
        assertEquals("b", controller.state.value.queue.currentImageId)
        assertEquals("新版", controller.state.value.queue.items[1].prompt)
        assertEquals(listOf(null to null, "p2" to null, null to "delta2"), repository.requests)
    }

    @Test fun `游标失效保留现有内容且下次从头合并`() = runTest {
        val repository = FakeGeneratedRepository()
        val controller = GeneratedReviewController(repository)
        repository.pages += GeneratedPage(listOf(image("a")), null, "expired")
        controller.refresh()
        repository.expired = true
        controller.refresh()
        assertEquals(GeneratedPhase.ERROR, controller.state.value.phase)
        assertEquals("a", controller.state.value.queue.currentImageId)
        repository.expired = false
        repository.pages += GeneratedPage(listOf(image("a"), image("b")), null, "fresh")
        controller.refresh()
        assertEquals(null to null, repository.requests.last())
        assertEquals(2, controller.state.value.queue.items.size)
    }

    @Test fun `能力缺失不查询列表也不伪装空库`() = runTest {
        val repository = FakeGeneratedRepository().apply { available = false }
        val controller = GeneratedReviewController(repository)
        controller.refresh()
        assertEquals(GeneratedPhase.UNAVAILABLE, controller.state.value.phase)
        assertTrue(repository.requests.isEmpty())
        assertTrue(runCatching { UnavailableGeneratedRepository().saveEvaluation(PromptEvaluation(PromptTarget("p", "v"), 5)) }.isFailure)
    }

    @Test fun `评分按提示词版本隔离 无版本必须明确选择整体评分`() {
        assertNotEquals(PromptTarget("p", "v1"), PromptTarget("p", "v2"))
        assertTrue(runCatching { PromptTarget("p", null) }.isFailure)
        assertTrue(runCatching { PromptTarget("", "v") }.isFailure)
        assertTrue(runCatching { PromptEvaluation(PromptTarget("p", "v"), 0) }.isFailure)
        assertTrue(runCatching { PromptEvaluation(PromptTarget("p", "v"), 5, mapOf(PromptDimension.QUALITY to 6)) }.isFailure)
        assertTrue(PromptEvaluation(PromptTarget("p", null, overallPrompt = true), 4).dimensions.isEmpty())
    }

    @Test fun `图片仅使用独立同源桥凭据且不跟随重定向`() {
        val profile = GeneratedBridgeProfile("https://bridge.example:8443", "bridge-only")
        val access = GeneratedImageAccess(profile)
        assertEquals("Bearer bridge-only", access.request("/images/a").header("Authorization"))
        listOf("https://other.example/a", "http://bridge.example:8443/a", "https://bridge.example/a", "https://user@bridge.example:8443/a", "//other.example/a").forEach {
            assertTrue(it, runCatching { access.request(it) }.isFailure)
        }
        assertFalse(access.client.followRedirects)
        assertFalse(access.client.followSslRedirects)
        assertTrue(runCatching { GeneratedImageAccess(GeneratedBridgeProfile("https://bridge.example", "")).request("/a") }.isFailure)
    }
}

private class FakeGeneratedRepository : GeneratedReviewRepository {
    var available = true
    var expired = false
    val pages = java.util.ArrayDeque<GeneratedPage>()
    val requests = mutableListOf<Pair<String?, String?>>()
    override suspend fun capabilities() = GeneratedCapabilities(incrementalFeed = available)
    override suspend fun feed(cursor: String?, after: String?): GeneratedPage {
        requests += cursor to after
        if (expired) throw GeneratedCursorExpired()
        return pages.removeFirst()
    }
    override suspend fun detail(id: String): GeneratedImage = error("未提供详情")
    override suspend fun evaluation(target: PromptTarget): PromptEvaluation? = error("未提供评分")
    override suspend fun saveEvaluation(evaluation: PromptEvaluation): Unit = error("未提供评分")
}
