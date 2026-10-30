package com.mediareview.app.feature.v2.nsfw.data

import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.core.pairing.normalizeBaseUrl
import com.mediareview.app.feature.v2.nsfw.FakeNsfwStore
import com.mediareview.app.feature.v2.nsfw.NsfwJson
import com.mediareview.app.feature.v2.nsfw.network.NsfwTokenProvider
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
import org.junit.Test

class NsfwPairingRepositoryTest {

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun server(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }

    private fun okay(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(status: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun repository(
        store: FakeNsfwStore = FakeNsfwStore(),
        tokenProvider: NsfwTokenProvider = NsfwTokenProvider(),
    ) = NsfwPairingRepository(store, tokenProvider, nsfwTestApiFactory(nsfwTestHttpClient()))

    @Test
    fun `当前 Bridge 缺少v2能力时阻断配对并列出缺失项`() = runTest {
        val server = server()
        server.enqueue(okay(NsfwJson.HEALTH_CURRENT_BRIDGE))
        val store = FakeNsfwStore()
        val tokenProvider = NsfwTokenProvider()
        val repo = repository(store, tokenProvider)
        val baseUrl = "http://nsfw.test:${server.port}"

        val result = repo.checkAndPair(baseUrl, "123456")

        assertTrue(result is NsfwPairResult.Blocked)
        val blocked = result as NsfwPairResult.Blocked
        assertEquals(1, blocked.apiContract)
        assertEquals(NSFW_REQUIRED_CAPABILITIES, blocked.missingCapabilities)
        assertTrue(blocked.message.contains("缺少能力"))
        assertEquals(1, server.requestCount)
        assertEquals("", tokenProvider.token)
        assertNull(store.savedToken)
    }

    @Test
    fun `产品标识不匹配时阻断且不发起配对`() = runTest {
        val server = server()
        server.enqueue(okay(NsfwJson.HEALTH_WRONG_PRODUCT))
        val store = FakeNsfwStore()
        val repo = repository(store)
        val result = repo.checkAndPair("http://nsfw.test:${server.port}", "123456")
        assertTrue(result is NsfwPairResult.Blocked)
        assertTrue((result as NsfwPairResult.Blocked).message.contains("产品标识"))
        assertEquals(1, server.requestCount)
        assertTrue(store.savedToken == null)
    }

    @Test
    fun `能力齐全时完成配对并保存独立凭据`() = runTest {
        val server = server()
        server.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        server.enqueue(okay(NsfwJson.PAIRING_OK))
        val store = FakeNsfwStore()
        val tokenProvider = NsfwTokenProvider()
        val repo = repository(store, tokenProvider)
        val baseUrl = "http://nsfw.test:${server.port}"

        val result = repo.checkAndPair(baseUrl, "123456")

        assertTrue(result is NsfwPairResult.Paired)
        assertEquals("nsfw-token-1", store.savedToken)
        assertEquals("nsfw-token-1", tokenProvider.token)
        assertEquals("dev_1", (result as NsfwPairResult.Paired).deviceId)

        val health = server.takeRequest()
        assertEquals("/mobile/system/health", health.path)
        val verify = server.takeRequest()
        assertEquals("/mobile/pairing/verify", verify.path)
        val body = verify.body.readUtf8()
        assertTrue(body.contains("\"installation_id\":\"inst-1\""))
        assertTrue(body.contains("\"code\":\"123456\""))
        assertFalse(body.contains("nsfw-token-1"))
    }

    @Test
    fun `两个 Bridge 可以分别连接互不干扰`() = runTest {
        val serverA = server()
        serverA.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        serverA.enqueue(okay(NsfwJson.PAIRING_OK))
        val storeA = FakeNsfwStore()
        val tokenA = NsfwTokenProvider()
        val repoA = repository(storeA, tokenA)

        val serverB = server()
        serverB.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        serverB.enqueue(okay("""{"success":true,"data":{"device_id":"dev_2","installation_id":"inst-2","name":"B","token":"nsfw-token-2","paired_at":"2026-10-28T12:00:00Z"},"request_id":"req"}"""))
        val storeB = FakeNsfwStore(installationId = "inst-2")
        val tokenB = NsfwTokenProvider()
        val repoB = repository(storeB, tokenB)

        val urlA = "http://nsfw-a.test:${serverA.port}"
        val urlB = "http://nsfw-b.test:${serverB.port}"
        assertTrue(repoA.checkAndPair(urlA, "111111") is NsfwPairResult.Paired)
        assertTrue(repoB.checkAndPair(urlB, "222222") is NsfwPairResult.Paired)

        assertEquals("nsfw-token-1", tokenA.tokenFor("$urlA/mobile/images/feed"))
        assertEquals("nsfw-token-2", tokenB.tokenFor("$urlB/mobile/images/feed"))
        assertNull(tokenA.tokenFor("$urlB/mobile/images/feed"))
        assertNull(tokenB.tokenFor("$urlA/mobile/images/feed"))
        assertEquals("/mobile/system/health", serverB.takeRequest().path)
        assertEquals("/mobile/pairing/verify", serverB.takeRequest().path)
    }

    @Test
    fun `一个Bridge掉线时另一个仍保持已配对且可重配对`() = runTest {
        val serverA = server()
        serverA.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        serverA.enqueue(okay(NsfwJson.PAIRING_OK))
        val storeA = FakeNsfwStore()
        val tokenA = NsfwTokenProvider()
        val repoA = repository(storeA, tokenA)
        val urlA = "http://nsfw-a.test:${serverA.port}"
        assertTrue(repoA.checkAndPair(urlA, "111111") is NsfwPairResult.Paired)

        val serverB = server()
        serverB.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        serverB.enqueue(okay("""{"success":true,"data":{"device_id":"dev_2","installation_id":"inst-1","name":"B","token":"nsfw-token-2","paired_at":"2026-10-28T12:00:00Z"},"request_id":"req"}"""))
        val storeB = FakeNsfwStore()
        val tokenB = NsfwTokenProvider()
        val repoB = repository(storeB, tokenB)
        val urlB = "http://nsfw-b.test:${serverB.port}"
        assertTrue(repoB.checkAndPair(urlB, "222222") is NsfwPairResult.Paired)

        // A 掉线：配对失败但不清除已有凭据；B 不受影响
        serverA.shutdown()
        val offline = repoA.checkAndPair(urlA, "111111")
        assertTrue(offline is NsfwPairResult.Failed)
        assertTrue(!tokenA.token.isBlank())
        assertEquals("nsfw-token-2", tokenB.token)

        // A 恢复后用新地址重新配对（模拟用户重配对）
        val serverA2 = server()
        serverA2.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        serverA2.enqueue(okay("""{"success":true,"data":{"device_id":"dev_3","installation_id":"inst-1","name":"A2","token":"nsfw-token-3","paired_at":"2026-10-28T13:00:00Z"},"request_id":"req"}"""))
        val urlA2 = "http://nsfw-a2.test:${serverA2.port}"
        val repaired = repoA.checkAndPair(urlA2, "333333")
        assertTrue(repaired is NsfwPairResult.Paired)
        assertEquals("nsfw-token-3", storeA.token)
        assertEquals("nsfw-token-3", tokenA.token)
        assertNull(tokenA.tokenFor("$urlA/mobile/images/feed"))
        assertTrue(!tokenB.token.isBlank())
    }

    @Test
    fun `401只清除NSFW凭据不影响MediaReview`() = runTest {
        val nsfwToken = NsfwTokenProvider().apply { set("nsfw-token", "http://nsfw.test:8767") }
        val mediaToken = TokenProvider().apply { set("media-token", "http://media.test:8766") }
        val store = FakeNsfwStore(baseUrl = "http://nsfw.test:8767", token = "nsfw-token")
        val repo = repository(store, nsfwToken)

        repo.handleAuthRejected()

        assertEquals("", nsfwToken.token)
        assertTrue(store.invalidated)
        assertEquals("media-token", mediaToken.token)
        assertEquals("http://media.test:8766", mediaToken.pairedOrigin)
    }

    @Test
    fun `非法地址直接失败且不触网`() = runTest {
        val repo = repository(FakeNsfwStore())
        val result = repo.checkAndPair("http://host:8767/path", "123456")
        assertTrue(result is NsfwPairResult.Failed)
        assertTrue((result as NsfwPairResult.Failed).message.contains("路径"))
    }

    @Test
    fun `健康检查断网时给出可读失败`() = runTest {
        val repo = repository(FakeNsfwStore())
        val result = repo.checkAndPair("http://127.0.0.1:1", "123456")
        assertTrue(result is NsfwPairResult.Failed)
        assertTrue(!(result as NsfwPairResult.Failed).message.isBlank())
    }

    @Test
    fun `load恢复已保存配对并重新校验能力`() = runTest {
        val server = server()
        server.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        val baseUrl = "http://nsfw.test:${server.port}"
        val store = FakeNsfwStore(baseUrl = baseUrl, token = "persisted-token")
        val tokenProvider = NsfwTokenProvider()
        val repo = repository(store, tokenProvider)
        val snapshot = repo.load()
        assertTrue(snapshot.paired)
        assertEquals(true, snapshot.compatible)
        assertEquals("persisted-token", tokenProvider.token)
        assertEquals(baseUrl, tokenProvider.pairedOrigin)
        assertEquals("/mobile/system/health", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `load发现旧Bridge缺能力时保留token但标记不可用`() = runTest {
        val server = server()
        server.enqueue(okay(NsfwJson.HEALTH_CURRENT_BRIDGE))
        val baseUrl = "http://nsfw.test:${server.port}"
        val store = FakeNsfwStore(baseUrl = baseUrl, token = "persisted-token")
        val tokenProvider = NsfwTokenProvider()
        val repo = repository(store, tokenProvider)

        val snapshot = repo.load()

        assertTrue(snapshot.paired)
        assertEquals(false, snapshot.compatible)
        assertEquals(NSFW_REQUIRED_CAPABILITIES, snapshot.missingCapabilities)
        assertEquals(1, snapshot.apiContract)
        // 只标记不兼容，绝不因版本旧而清 token
        assertEquals("persisted-token", store.token)
        assertEquals("persisted-token", tokenProvider.token)
    }

    @Test
    fun `load时Bridge不可达保留token并提示而不清凭据`() = runTest {
        val store = FakeNsfwStore(baseUrl = "http://127.0.0.1:1", token = "persisted-token")
        val tokenProvider = NsfwTokenProvider()
        val repo = repository(store, tokenProvider)
        val snapshot = repo.load()
        assertTrue(snapshot.paired)
        assertNull(snapshot.compatible)
        assertTrue(!snapshot.message.isNullOrBlank())
        assertEquals("persisted-token", store.token)
    }

    @Test
    fun `凭据损坏时load返回未配对并清除内存token`() = runTest {
        val store = FakeNsfwStore(baseUrl = "http://nsfw.test:8767", token = "", rejected = true)
        val tokenProvider = NsfwTokenProvider().apply { set("stale", "http://nsfw.test:8767") }
        val repo = repository(store, tokenProvider)
        val snapshot = repo.load()
        assertFalse(snapshot.paired)
        assertTrue(snapshot.rejected)
        assertEquals("", tokenProvider.token)
    }

    @Test
    fun `verify返回401时标记未授权`() = runTest {
        val server = server()
        server.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        server.enqueue(error(401, NsfwJson.ERROR_AUTH_REVOKED))
        val repo = repository(FakeNsfwStore())
        val result = repo.checkAndPair("http://nsfw.test:${server.port}", "123456")
        assertTrue(result is NsfwPairResult.Failed)
        assertTrue((result as NsfwPairResult.Failed).unauthorized)
        assertTrue(result.message.contains("吊销") || result.message.contains("失效"))
    }

    @Test
    fun `IOException包装为可读失败而不是崩溃`() = runTest {
        val broken = FakeNsfwStore()
        val repo = NsfwPairingRepository(broken, NsfwTokenProvider(), nsfwTestApiFactory(nsfwTestHttpClient()))
        val result = repo.checkAndPair("http://127.0.0.1:9", "123456")
        assertTrue(result is NsfwPairResult.Failed)
        // IOException 路径必须可读；不能把异常原文直接抛给 UI
        assertTrue((result as NsfwPairResult.Failed).message.isNotBlank())
        assertFalse(result.message.contains("Connection refused"))
    }

    @Test
    fun `配对前不发送Bearer`() = runTest {
        val server = server()
        server.enqueue(okay(NsfwJson.HEALTH_COMPATIBLE))
        server.enqueue(okay(NsfwJson.PAIRING_OK))
        val tokenProvider = NsfwTokenProvider().apply { set("should-not-be-sent", "http://nsfw.test:${server.port}") }
        val repo = repository(FakeNsfwStore(), tokenProvider)
        repo.checkAndPair("http://nsfw.test:${server.port}", "123456")
        val health = server.takeRequest()
        assertNull(health.getHeader("Authorization"))
        val verify = server.takeRequest()
        assertNull(verify.getHeader("Authorization"))
    }

    @Test
    fun `本地端口默认8877`() {
        assertEquals("http://192.168.1.20:8877", normalizeBaseUrl("192.168.1.20", defaultPort = 8877))
        assertEquals("http://192.168.1.20:9000", normalizeBaseUrl("192.168.1.20:9000", defaultPort = 8877))
    }
}
