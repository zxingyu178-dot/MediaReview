package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.datastore.ServerProfile
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.model.HealthOut
import com.mediareview.app.core.model.JellyfinStatusOut
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.PairingStatusOut
import com.mediareview.app.core.model.VerifyOut
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.core.network.VerifyRequest
import com.mediareview.app.core.pairing.PairingApi
import com.mediareview.app.core.pairing.PairingApiFactory
import com.mediareview.app.core.pairing.PairingRepository
import com.mediareview.app.core.pairing.PairingStore
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Stage 8D.2 §13~§30：统一 Server 业务访问 Guard 的合同测试（JVM）。
 *
 * 核心不变量：**Server 业务 API 只有 Online 才允许发出**；非 Online 状态一律
 * 在"发请求之前"失败，且**不产生任何 HTTP 请求**。
 */
class V2ServerAccessGuardTest {

    private lateinit var server: MockWebServer
    private lateinit var statusStore: V2ServerStatusStore
    private lateinit var repository: V2ServerMediaRepository
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"data":{}}"""))
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        baseUrl = "http://mediareview.test:${server.port}"
        statusStore = V2ServerStatusStore()
        repository = V2ServerMediaRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true; coerceInputValues = true }),
            mapper = V2MediaMapper(MediaUrlResolver()),
            playbackResolver = V2PlaybackResolver(MediaUrlResolver()),
            albumCoverPort = object : V2ServerAlbumCoverPort {
                override suspend fun allCovers(): Map<String, String> = emptyMap()
                override suspend fun setCover(folderId: String, mediaId: String?) = Unit
            },
            statusStore = statusStore,
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private inline fun assertFailsWith(block: () -> Unit): Throwable {
        return try {
            block()
            throw AssertionError("期望抛出异常，但调用成功")
        } catch (error: AssertionError) {
            throw error
        } catch (error: Throwable) {
            error
        }
    }

    // ---------- §8/§11：什么时候允许加载业务数据 ----------

    @Test
    fun `只有 Online 允许加载 Server 业务数据`() {
        assertTrue(shouldLoadServerBusinessData(V2ServerStatus.Online))
        listOf(
            V2ServerStatus.Unconfigured,
            V2ServerStatus.Probing,
            V2ServerStatus.Offline,
            V2ServerStatus.AuthRejected,
            V2ServerStatus.Incompatible,
        ).forEach { status ->
            assertFalse("$status 不得触加载", shouldLoadServerBusinessData(status))
        }
    }

    // ---------- §15：所有业务调用在 Guard 处失败，且 0 个网络请求 ----------

    @Test
    fun `非 Online 状态发出 0 个业务网络请求`() {
        val expectations = mapOf(
            V2ServerStatus.Unconfigured to ServerNotConfiguredException::class.java,
            V2ServerStatus.Probing to ServerNotReadyException::class.java,
            V2ServerStatus.Offline to ServerOfflineException::class.java,
            V2ServerStatus.AuthRejected to ServerAuthRejectedException::class.java,
            V2ServerStatus.Incompatible to ServerIncompatibleException::class.java,
        )
        expectations.forEach { (status, expected) ->
            statusStore.update(status)
            val error = assertFailsWith { runBlocking { repository.folders() } }
            assertTrue("$status 应抛 ${expected.simpleName}，实际 $error", expected.isInstance(error))
        }
        assertEquals("非 Online 期间不得发出任何 HTTP 请求", 0, server.requestCount)
    }

    @Test
    fun `不兼容时收藏 待删除 播放解析 都被 Guard 拦住且 0 请求`() {
        statusStore.recordIncompatible("1.1.0", 1)

        // 写操作按既有合同"失败返回 false"（UI 不变），但同样必须在 Guard 处失败、不发请求。
        assertFalse(runBlocking { repository.setFavorite("m1", true) })
        assertFalse(runBlocking { repository.setPendingDelete("m1", true) })

        // 读操作 fail-fast 上抛，绝不 catch→空数据。
        assertTrue(assertFailsWith { runBlocking { repository.pendingDeleteIds() } } is ServerIncompatibleException)
        assertTrue(assertFailsWith { runBlocking { repository.resolvePlayback("m1") } } is ServerIncompatibleException)
        assertTrue(assertFailsWith { runBlocking { repository.favorites() } } is ServerIncompatibleException)

        assertEquals(0, server.requestCount)
    }

    // ---------- §20~§22：Online 只能由兼容的 health probe 授予 ----------

    @Test
    fun `业务请求成功不能把非 Online 状态提升为 Online`() {
        listOf(
            V2ServerStatus.Incompatible,
            V2ServerStatus.Probing,
            V2ServerStatus.Unconfigured,
            V2ServerStatus.AuthRejected,
            V2ServerStatus.Offline,
        ).forEach { status ->
            statusStore.update(status)
            statusStore.onRequestSuccess()
            assertEquals("$status 不得被'碰巧成功'升级为 Online", status, statusStore.status.value)
        }
    }

    @Test
    fun `Online 只能由兼容的 health probe 建立`() {
        statusStore.update(V2ServerStatus.Unconfigured)
        statusStore.recordCompatible("1.2.1", 2)
        assertEquals(V2ServerStatus.Online, statusStore.status.value)
        assertEquals("1.2.1", statusStore.serverVersion.value)
        assertEquals(2, statusStore.serverApiContract.value)
    }

    // ---------- §23：失败迁移只在 Online 发生 ----------

    @Test
    fun `网络失败只在 Online 迁移为 Offline`() {
        statusStore.recordCompatible("1.2.1", 2)
        statusStore.onRequestFailure(IOException("boom"))
        assertEquals(V2ServerStatus.Offline, statusStore.status.value)

        statusStore.recordIncompatible("1.1.0", 1)
        statusStore.onRequestFailure(IOException("boom"))
        assertEquals("不兼容不得被网络错误覆盖", V2ServerStatus.Incompatible, statusStore.status.value)
    }

    @Test
    fun `401 只在 Online 迁移为 AuthRejected`() {
        statusStore.recordCompatible("1.2.1", 2)
        statusStore.onRequestFailure(unauthorized())
        assertEquals(V2ServerStatus.AuthRejected, statusStore.status.value)
    }

    // ---------- §25/§26：切换服务器不残留旧元数据 ----------

    @Test
    fun `reset 清空版本 contract 与兼容性`() {
        statusStore.recordCompatible("1.2.0", 2)
        statusStore.reset()
        assertEquals(V2ServerStatus.Unconfigured, statusStore.status.value)
        assertEquals("", statusStore.serverVersion.value)
        assertEquals(0, statusStore.serverApiContract.value)
    }

    @Test
    fun `Server A 到 Server B 不残留旧版本状态`() {
        statusStore.recordCompatible("1.2.0", 2) // Server A
        statusStore.reset()                       // 断开 / 切换
        statusStore.recordIncompatible("1.1.0", 1) // Server B（旧）

        assertEquals(V2ServerStatus.Incompatible, statusStore.status.value)
        assertEquals("1.1.0", statusStore.serverVersion.value)
        assertEquals(1, statusStore.serverApiContract.value)
    }

    private fun unauthorized(): HttpException = HttpException(
        Response.error<Unit>(401, "{}".toResponseBody("application/json".toMediaType())),
    )
}

/**
 * Stage 8D.2 §6/§7/§55：首次连接旧 Server 必须**在 verify 之前**停止。
 */
class PairingConnectIncompatibleContractTest {

    private class CountingApi(private val contract: Int) : PairingApi {
        var verifyCalls = 0
        override suspend fun health(): Envelope<HealthOut> = Envelope(
            success = true,
            data = HealthOut(status = "ok", version = "1.1.0", api_contract = contract),
        )
        override suspend fun pairingStatus(): Envelope<PairingStatusOut> =
            Envelope(success = true, data = PairingStatusOut(pairing_required = true))
        override suspend fun jellyfinStatus(): Envelope<JellyfinStatusOut> =
            Envelope(success = true, data = JellyfinStatusOut(server_name = "j"))
        override suspend fun media(): Envelope<MediaPage> = Envelope(success = true, data = MediaPage())
        override suspend fun verify(request: VerifyRequest): Envelope<VerifyOut> {
            verifyCalls += 1
            return Envelope(success = true, data = VerifyOut(paired = true, token = "tok"))
        }
    }

    private class Factory(private val api: PairingApi) : PairingApiFactory {
        override fun create(baseUrl: String, authenticated: Boolean): PairingApi = api
    }

    private class Store(var profile: ServerProfile = ServerProfile("http://s:1")) : PairingStore {
        override suspend fun current(): ServerProfile = profile
        override suspend fun saveBaseUrl(baseUrl: String) { profile = profile.copy(baseUrl = baseUrl) }
        override suspend fun savePairing(token: String) { profile = profile.copy(token = token) }
        override suspend fun invalidateCredential() { profile = profile.copy(token = "") }
        override suspend fun deviceId(): String = "dev-1"
        override suspend fun clear() { profile = ServerProfile() }
    }

    @Test
    fun `首次连接 contract1 停在 health 不进入 verify`() = runBlocking {
        val api = CountingApi(contract = 1)
        val repository = PairingRepository(Store(), TokenProvider(), Factory(api))

        val result = repository.checkHealthy("http://s:1")

        assertTrue(result is PairingRepository.Result.Incompatible)
        assertEquals("旧 Server 绝不允许 verify", 0, api.verifyCalls)
    }

    @Test
    fun `首次连接 contract2 可在 health 之后进入 verify`() = runBlocking {
        val api = CountingApi(contract = 2)
        val repository = PairingRepository(Store(), TokenProvider(), Factory(api))

        val health = repository.checkHealthy("http://s:1")
        assertTrue(health is PairingRepository.Result.HealthOk)
        assertEquals("checkHealthy 本身不调用 verify", 0, api.verifyCalls)

        val paired = repository.verifyAndPair("http://s:1", "123456")
        assertTrue(paired is PairingRepository.Result.Paired)
        assertEquals(1, api.verifyCalls)
    }
}
