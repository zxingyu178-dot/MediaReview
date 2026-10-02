package com.mediareview.app.core.pairing

import com.mediareview.app.core.datastore.ServerProfile
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.model.HealthOut
import com.mediareview.app.core.model.JellyfinStatusOut
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.PairingStatusOut
import com.mediareview.app.core.model.VerifyOut
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.core.network.VerifyRequest
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PairingRepositoryLifecycleTest {
    @Test
    fun `无效配对码保持 server online unpaired 且唯一状态流一致`() = runBlocking {
        val store = FakePairingStore()
        val api = FakePairingApi(verifyResult = Envelope(success = true, data = VerifyOut()))
        val repository = PairingRepository(store, TokenProvider(), FakePairingApiFactory(api))

        val result = repository.verifyAndPair(store.profile.baseUrl, "bad") as PairingRepository.Result.Failure

        assertEquals(OnlineState.Online, result.connection.mediaReview)
        assertEquals(AuthenticationState.Unpaired, result.connection.authentication)
        assertEquals(result.connection, repository.connection.value)
    }

    @Test
    fun `只有网络异常产生 offline 且同步到唯一状态流`() = runBlocking {
        val store = FakePairingStore()
        val api = FakePairingApi(verifyFailure = IOException("offline"))
        val repository = PairingRepository(store, TokenProvider(), FakePairingApiFactory(api))

        val result = repository.verifyAndPair(store.profile.baseUrl, "123456") as PairingRepository.Result.Failure

        assertEquals(OnlineState.Offline, result.connection.mediaReview)
        assertEquals(result.connection, repository.connection.value)
    }

    @Test
    fun `fresh repository 持久 token 离线恢复为 offline unknown`() = runBlocking {
        val store = FakePairingStore(profile = ServerProfile("http://server.example:8766", "persisted"))
        val repository = PairingRepository(
            store,
            TokenProvider(),
            FakePairingApiFactory(FakePairingApi(healthFailure = IOException("offline"))),
        )

        val restored = repository.load()

        assertEquals(OnlineState.Offline, restored.connection.mediaReview)
        assertEquals(AuthenticationState.Unknown, restored.connection.authentication)
        assertEquals(restored.connection, repository.connection.value)
        assertFalse(restored.paired)
    }

    @Test
    fun `probe 401 持久清除 credential 本次 rejected 且重启不重发`() = runBlocking {
        val store = FakePairingStore(profile = ServerProfile("http://server.example:8766", "stale"))
        val api = FakePairingApi(protectedFailure = unauthorized())
        val tokenProvider = TokenProvider()
        val repository = PairingRepository(store, tokenProvider, FakePairingApiFactory(api))
        val sharedConnection = repository.connection

        val rejected = repository.load()

        assertEquals(AuthenticationState.Rejected, rejected.connection.authentication)
        assertEquals(AuthenticationState.Rejected, repository.connection.value.authentication)
        assertEquals(1, store.invalidations)
        assertEquals("", store.profile.token)
        assertEquals("", tokenProvider.token)
        assertEquals("http://server.example:8766", store.profile.baseUrl)
        assertEquals("installation-1", store.deviceId())
        assertEquals(1, api.protectedCalls)

        val sameSessionReload = repository.load()
        assertSame(sharedConnection, repository.connection)
        assertEquals(AuthenticationState.Rejected, sameSessionReload.connection.authentication)
        assertEquals(AuthenticationState.Rejected, sharedConnection.value.authentication)
        assertEquals(1, api.protectedCalls)

        val restarted = PairingRepository(store, TokenProvider(), FakePairingApiFactory(api)).load()
        assertEquals(AuthenticationState.Unpaired, restarted.connection.authentication)
        assertEquals(1, api.protectedCalls)

        repository.clear()
        assertEquals(AuthenticationState.Unpaired, sharedConnection.value.authentication)
    }

    @Test
    fun `401 后无效重配不能清 session rejected 而成功重配可以`() = runBlocking {
        val store = FakePairingStore(profile = ServerProfile("http://server.example:8766", "stale"))
        val api = FakePairingApi(protectedFailure = unauthorized())
        val repository = PairingRepository(store, TokenProvider(), FakePairingApiFactory(api))

        repository.load()
        val invalid = repository.verifyAndPair(store.profile.baseUrl, "bad") as PairingRepository.Result.Failure
        assertEquals(AuthenticationState.Rejected, invalid.connection.authentication)

        api.verifyResult = Envelope(
            success = true,
            data = VerifyOut(paired = true, token = "replacement"),
        )
        val paired = repository.verifyAndPair(store.profile.baseUrl, "123456")
        assertTrue(paired is PairingRepository.Result.Paired)
        assertEquals(AuthenticationState.Paired, repository.connection.value.authentication)
    }

    @Test
    fun `401 持久清除失败返回可操作错误且同实例不重发旧 token`() = runBlocking {
        val store = FakePairingStore(
            profile = ServerProfile("http://server.example:8766", "stale"),
            invalidationFailure = IOException("disk unavailable"),
        )
        val api = FakePairingApi(protectedFailure = unauthorized())
        val tokenProvider = TokenProvider()
        val repository = PairingRepository(store, tokenProvider, FakePairingApiFactory(api))

        val rejected = repository.load()

        assertEquals(AuthenticationState.Rejected, rejected.connection.authentication)
        assertTrue(rejected.message.orEmpty().contains("清除"))
        assertEquals("stale", store.profile.token)
        assertEquals("", tokenProvider.token)
        assertEquals(1, api.protectedCalls)

        val reloaded = repository.load()
        assertEquals(AuthenticationState.Rejected, reloaded.connection.authentication)
        assertTrue(reloaded.message.orEmpty().contains("清除"))
        assertEquals("", tokenProvider.token)
        assertEquals(1, api.protectedCalls)
    }

    @Test
    fun `save pairing 与 invalidate 双失败先 fail closed 且不重发旧 token`() = runBlocking {
        val baseUrl = "http://server.example:8766"
        val store = FakePairingStore(
            profile = ServerProfile(baseUrl, "stale"),
            savePairingFailure = IOException("write failed"),
            invalidationFailure = IOException("delete failed"),
        )
        val api = FakePairingApi(
            verifyResult = Envelope(
                success = true,
                data = VerifyOut(paired = true, token = "replacement"),
            ),
        )
        val tokenProvider = TokenProvider().also { it.set("stale", baseUrl) }
        val repository = PairingRepository(store, tokenProvider, FakePairingApiFactory(api))

        val failed = repository.verifyAndPair(baseUrl, "123456") as PairingRepository.Result.Failure

        assertEquals(OnlineState.Online, failed.connection.mediaReview)
        assertEquals(AuthenticationState.Rejected, failed.connection.authentication)
        assertTrue(failed.message.contains("清除"))
        assertEquals("", tokenProvider.token)
        assertEquals("stale", store.profile.token)
        assertEquals(1, store.invalidations)

        val reloaded = repository.load()
        assertEquals(AuthenticationState.Rejected, reloaded.connection.authentication)
        assertTrue(reloaded.message.orEmpty().contains("清除"))
        assertEquals("", tokenProvider.token)
        assertEquals(0, api.protectedCalls)

        val probed = repository.checkHealthy(baseUrl)
        assertTrue(probed is PairingRepository.Result.HealthOk)
        assertEquals(AuthenticationState.Rejected, repository.connection.value.authentication)
        assertEquals(0, api.protectedCalls)
    }

    @Test
    fun `save base url 失败纳入 fail closed 边界`() = runBlocking {
        val baseUrl = "http://server.example:8766"
        val store = FakePairingStore(
            profile = ServerProfile(baseUrl, "stale"),
            saveBaseUrlFailure = IOException("base write failed"),
        )
        val api = successfulPairingApi()
        val tokenProvider = TokenProvider().also { it.set("stale", baseUrl) }
        val repository = PairingRepository(store, tokenProvider, FakePairingApiFactory(api))

        val failed = repository.verifyAndPair(baseUrl, "123456") as PairingRepository.Result.Failure

        assertEquals(OnlineState.Online, failed.connection.mediaReview)
        assertEquals(AuthenticationState.Rejected, failed.connection.authentication)
        assertEquals("安全凭据无法保存", failed.message)
        assertEquals("", tokenProvider.token)
        assertEquals("", store.profile.token)
        assertEquals(1, store.invalidations)

        repository.load()
        assertEquals(AuthenticationState.Rejected, repository.connection.value.authentication)
        assertEquals(0, api.protectedCalls)
    }

    @Test
    fun `save base url 与 invalidate 双失败固定报错且不重发旧 token`() = runBlocking {
        val baseUrl = "http://server.example:8766"
        val store = FakePairingStore(
            profile = ServerProfile(baseUrl, "stale"),
            saveBaseUrlFailure = IOException("base write failed"),
            invalidationFailure = IOException("delete failed"),
        )
        val api = successfulPairingApi()
        val tokenProvider = TokenProvider().also { it.set("stale", baseUrl) }
        val repository = PairingRepository(store, tokenProvider, FakePairingApiFactory(api))

        val failed = repository.verifyAndPair(baseUrl, "123456") as PairingRepository.Result.Failure

        assertEquals(OnlineState.Online, failed.connection.mediaReview)
        assertEquals(AuthenticationState.Rejected, failed.connection.authentication)
        assertTrue(failed.message.contains("清除"))
        assertEquals("", tokenProvider.token)
        assertEquals("stale", store.profile.token)
        assertEquals(1, store.invalidations)

        repository.load()
        repository.checkHealthy(baseUrl)
        assertEquals(AuthenticationState.Rejected, repository.connection.value.authentication)
        assertEquals("", tokenProvider.token)
        assertEquals(0, api.protectedCalls)
    }

    private fun successfulPairingApi() = FakePairingApi(
        verifyResult = Envelope(
            success = true,
            data = VerifyOut(paired = true, token = "replacement"),
        ),
    )

    private fun unauthorized(): HttpException = HttpException(
        Response.error<Unit>(
            401,
            "unauthorized".toResponseBody("text/plain".toMediaType()),
        ),
    )
}

private class FakePairingStore(
    var profile: ServerProfile = ServerProfile("http://server.example:8766"),
    private val saveBaseUrlFailure: Exception? = null,
    private val savePairingFailure: Exception? = null,
    private val invalidationFailure: Exception? = null,
) : PairingStore {
    var invalidations = 0
    override suspend fun current(): ServerProfile = profile
    override suspend fun saveBaseUrl(baseUrl: String) {
        saveBaseUrlFailure?.let { throw it }
        profile = profile.copy(baseUrl = baseUrl)
    }
    override suspend fun savePairing(token: String) {
        savePairingFailure?.let { throw it }
        profile = profile.copy(token = token)
    }
    override suspend fun invalidateCredential() {
        invalidations += 1
        invalidationFailure?.let { throw it }
        profile = profile.copy(token = "")
    }
    override suspend fun deviceId(): String = "installation-1"
    override suspend fun clear() { profile = ServerProfile() }
}

private class FakePairingApiFactory(private val api: PairingApi) : PairingApiFactory {
    override fun create(baseUrl: String, authenticated: Boolean): PairingApi = api
}

private class FakePairingApi(
    var verifyResult: Envelope<VerifyOut> = Envelope(success = true, data = VerifyOut()),
    private val healthFailure: Exception? = null,
    private val verifyFailure: Exception? = null,
    private val protectedFailure: Exception? = null,
) : PairingApi {
    var protectedCalls = 0
    override suspend fun health(): Envelope<HealthOut> {
        healthFailure?.let { throw it }
        return Envelope(
            success = true,
            data = HealthOut(status = "ok", version = "1.1", components = mapOf("database" to "ok")),
        )
    }
    override suspend fun pairingStatus() = Envelope(
        success = true,
        data = PairingStatusOut(pairing_required = true),
    )
    override suspend fun jellyfinStatus(): Envelope<JellyfinStatusOut> {
        protectedCalls += 1
        protectedFailure?.let { throw it }
        return Envelope(success = true, data = JellyfinStatusOut(server_name = "Jellyfin"))
    }
    override suspend fun media(): Envelope<MediaPage> = Envelope(success = true, data = MediaPage())
    override suspend fun verify(request: VerifyRequest): Envelope<VerifyOut> {
        verifyFailure?.let { throw it }
        return verifyResult
    }
}
