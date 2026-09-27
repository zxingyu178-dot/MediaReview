package com.mediareview.app.feature.v2.player

import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2PlaybackStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放源控制器（Stage 8B §6~§9）纯逻辑测试：
 * retry 必须回 Direct、旧媒体迟到事件必须被忽略、Direct → 一次 HLS → Error、
 * P1 预取只保留一条。
 */
class V2PlaybackSourceControllerTest {

    private val controller = V2PlaybackSourceController()

    private fun source(
        mediaId: String,
        directUrl: String = "http://host/$mediaId/direct",
        hlsUrl: String? = "http://host/$mediaId/hls",
    ): V2PlaybackSource = V2PlaybackSource(
        mediaId = mediaId,
        title = "视频 $mediaId",
        direct = V2PlaybackEndpoint(directUrl, mapOf("X-Emby-Token" to "token-$mediaId")),
        fallbackHls = hlsUrl?.let { V2PlaybackEndpoint(it, mapOf("X-Emby-Token" to "token-$mediaId")) },
    )

    private fun play(decision: V2PlaybackDecision): V2PlaybackDecision.Play =
        decision as? V2PlaybackDecision.Play ?: error("期望 Play，实际 $decision")

    // ---------- §6 retry 必须真正回 Direct ----------

    @Test
    fun `HLS 回退失败后 retry 从 Direct 重新开始`() {
        val token = controller.moveTo("a")
        assertEquals(V2PlaybackStage.DIRECT, play(controller.onResolved(token, source("a"))).stage)

        // Direct 失败 → 一次 HLS 回退
        val hls = play(controller.onPlaybackFailed(controller.token, "a"))
        assertEquals(V2PlaybackStage.HLS_FALLBACK, hls.stage)
        assertEquals("http://host/a/hls", hls.endpoint.url)

        // HLS 再失败 → 终态 Error（不无限回退）
        val terminal = controller.onPlaybackFailed(controller.token, "a")
        assertTrue(terminal is V2PlaybackDecision.Fail)

        // 用户点重试：重新 moveTo（VM 的 retry 语义），必须回到 Direct 而不是继续 HLS
        val retryToken = controller.moveTo("a")
        val again = play(controller.onResolved(retryToken, source("a")))
        assertEquals(V2PlaybackStage.DIRECT, again.stage)
        assertEquals("http://host/a/direct", again.endpoint.url)
    }

    @Test
    fun `Direct 失败但无 HLS 回退时直接终态失败`() {
        val token = controller.moveTo("a")
        controller.onResolved(token, source("a", hlsUrl = null))
        assertTrue(controller.onPlaybackFailed(controller.token, "a") is V2PlaybackDecision.Fail)
    }

    @Test
    fun `retry 后旧令牌的迟到失败不再生效`() {
        val oldToken = controller.moveTo("a")
        controller.onResolved(oldToken, source("a"))
        // 用户在失败前重试：moveTo 作废旧令牌
        val newToken = controller.moveTo("a")
        assertTrue(controller.onResolveFailed(oldToken, "旧的超时") is V2PlaybackDecision.Ignore)
        assertEquals(V2PlaybackStage.DIRECT, play(controller.onResolved(newToken, source("a"))).stage)
    }

    // ---------- §7 旧媒体迟到错误不得污染新媒体 ----------

    @Test
    fun `切换媒体后旧媒体的迟到错误被忽略`() {
        val tokenA = controller.moveTo("a")
        controller.onResolved(tokenA, source("a"))
        assertEquals(V2PlaybackStage.DIRECT, controller.stage)

        // 切到 B（解析中：source 立即清空、stage 回到 DIRECT）
        controller.moveTo("b")

        // 旧 GSY A 发出迟到 Error：令牌过期 → 忽略
        assertTrue(controller.onPlaybackFailed(tokenA, "a") is V2PlaybackDecision.Ignore)
        // 即使令牌看似最新，只要 mediaId 不是当前媒体，也必须忽略（禁止串媒体）
        assertTrue(controller.onPlaybackFailed(controller.token, "a") is V2PlaybackDecision.Ignore)
        assertEquals("b", controller.activeMediaId)
        assertNull(controller.source)
        assertEquals(V2PlaybackStage.DIRECT, controller.stage)
    }

    @Test
    fun `迟到 resolve 结果被忽略且不改变当前媒体`() {
        val tokenA = controller.moveTo("a")
        val tokenB = controller.moveTo("b")
        assertTrue(controller.onResolved(tokenA, source("a")) is V2PlaybackDecision.Ignore)
        assertEquals("b", controller.activeMediaId)
        assertNull(controller.source)

        val decision = play(controller.onResolved(tokenB, source("b")))
        assertEquals("b", decision.mediaId)
    }

    // ---------- §8 moveTo 立即清空上一视频的 Source ----------

    @Test
    fun `moveTo 立即清空 source 并回到 Direct`() {
        val token = controller.moveTo("a")
        controller.onResolved(token, source("a"))
        assertEquals("http://host/a/direct", controller.source?.direct?.url)

        controller.moveTo("b")
        assertNull(controller.source)
        assertEquals(V2PlaybackStage.DIRECT, controller.stage)
    }

    @Test
    fun `endpoint 缺失时进入失败态`() {
        val token = controller.moveTo("a")
        val noDirect = V2PlaybackSource(
            mediaId = "a",
            title = "t",
            direct = V2PlaybackEndpoint(""),
            fallbackHls = null,
        )
        // 空 URL 仍然返回端点（由播放内核报错）；这里验证 stage 决策使用的是 Direct 端点
        val decision = play(controller.onResolved(token, noDirect))
        assertEquals(V2PlaybackStage.DIRECT, decision.stage)

        // 无 HLS 回退的源：内核报错 → 终态
        assertTrue(controller.onPlaybackFailed(controller.token, "a") is V2PlaybackDecision.Fail)
    }

    // ---------- §9 P1 预取只保留一条 ----------

    @Test
    fun `预取槽只保留最后一次且必须 mediaId 匹配`() {
        controller.prefetch("n1", source("n1"))
        controller.prefetch("n2", source("n2"))

        // 被后来者覆盖：旧预取不再可用（快速 N→N+1→N+2 时旧结果自然作废）
        assertNull(controller.takePrefetched("n1"))
        assertEquals("n2", controller.takePrefetched("n2")?.mediaId)
        // 命中一次即消费
        assertNull(controller.takePrefetched("n2"))
    }

    @Test
    fun `clearPrefetch 清空预取槽`() {
        controller.prefetch("n1", source("n1"))
        controller.clearPrefetch()
        assertNull(controller.takePrefetched("n1"))
    }
}