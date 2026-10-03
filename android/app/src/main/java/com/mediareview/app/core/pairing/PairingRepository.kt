package com.mediareview.app.core.pairing

import com.mediareview.app.core.datastore.ServerProfile
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.model.HealthOut
import com.mediareview.app.core.model.JellyfinStatusOut
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.PairingStatusOut
import com.mediareview.app.core.model.VerifyOut
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaReviewApi
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.core.network.VerifyRequest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

/**
 * Android 要求 Server 至少支持的 API Contract（Stage 8D.1 §6）。
 *
 * 兼容性判断**只用这个数字**，绝不做 `version == "1.2.0"` 之类的字符串硬判断：
 * Server 版本给人看 / 部署 / 回滚，API Contract 给 App 判断兼容性（§9）。
 */
const val REQUIRED_SERVER_API_CONTRACT: Int = 2

internal interface PairingStore {
    suspend fun current(): ServerProfile
    suspend fun saveBaseUrl(baseUrl: String)
    suspend fun savePairing(token: String)
    suspend fun invalidateCredential()
    suspend fun deviceId(): String
    suspend fun clear()
}

internal interface PairingApi {
    suspend fun health(): Envelope<HealthOut>
    suspend fun pairingStatus(): Envelope<PairingStatusOut>
    suspend fun jellyfinStatus(): Envelope<JellyfinStatusOut>
    suspend fun media(): Envelope<MediaPage>
    suspend fun verify(request: VerifyRequest): Envelope<VerifyOut>
}

internal interface PairingApiFactory {
    fun create(baseUrl: String, authenticated: Boolean): PairingApi
}

private class ServerPairingStore(private val delegate: ServerProfileStore) : PairingStore {
    override suspend fun current() = delegate.current()
    override suspend fun saveBaseUrl(baseUrl: String) = delegate.saveBaseUrl(baseUrl)
    override suspend fun savePairing(token: String) = delegate.savePairing(token)
    override suspend fun invalidateCredential() = delegate.invalidateCredential()
    override suspend fun deviceId() = delegate.deviceId()
    override suspend fun clear() = delegate.clear()
}

private class RetrofitPairingApi(private val delegate: MediaReviewApi) : PairingApi {
    override suspend fun health() = delegate.health()
    override suspend fun pairingStatus() = delegate.pairingStatus()
    override suspend fun jellyfinStatus() = delegate.jellyfinStatus()
    override suspend fun media() = delegate.media(pageSize = 1)
    override suspend fun verify(request: VerifyRequest) = delegate.verify(request)
}

private class RetrofitPairingApiFactory(private val delegate: ApiFactory) : PairingApiFactory {
    override fun create(baseUrl: String, authenticated: Boolean): PairingApi =
        RetrofitPairingApi(delegate.create(baseUrl, authenticated))
}

internal suspend fun clearCredentialState(
    clearPersistent: suspend () -> Unit,
    clearMemory: () -> Unit,
) {
    clearPersistent()
    clearMemory()
}

/**
 * 负责"健康检查 -> 输入配对码 -> 签发 token -> 持久化"的连接与配对流程。
 * 阶段 8 提供纯逻辑(不含 UI),可做本地/仪器测试。
 */
@Singleton
class PairingRepository internal constructor(
    private val store: PairingStore,
    private val tokenProvider: TokenProvider,
    private val apiFactory: PairingApiFactory,
) {
    @Inject
    constructor(
        store: ServerProfileStore,
        tokenProvider: TokenProvider,
        apiFactory: ApiFactory,
    ) : this(ServerPairingStore(store), tokenProvider, RetrofitPairingApiFactory(apiFactory))

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private var credentialRejectedInSession = false
    private var credentialCleanupMessageInSession: String? = null

    sealed interface Result {
        data class HealthOk(
            val version: String,
            val pairingRequired: Boolean?,
            val connection: ConnectionState,
        ) : Result

        /**
         * Stage 8D.2 §5：Server 可达但 **API Contract 低于要求**（版本过旧）。
         *
         * 必须与 [HealthOk] 区分开 —— 绝不伪装成"健康"，否则 [V2HomeViewModel.connectServer]
         * 会继续走 pairing / verify / 落库 / 切 SERVER。此时**不清 token、不重新配对**。
         */
        data class Incompatible(
            val version: String,
            val apiContract: Int,
            val connection: ConnectionState,
        ) : Result

        data class Paired(val deviceId: String, val token: String) : Result
        data class Failure(
            val message: String,
            val connection: ConnectionState,
        ) : Result
    }

    private fun apiFor(baseUrl: String, authenticated: Boolean = true): PairingApi =
        apiFactory.create(baseUrl, authenticated)

    private fun failure(message: String, state: ConnectionState): Result.Failure {
        _connection.value = state
        return Result.Failure(message, state)
    }

    /** 校验服务器可达且返回健康信息。 */
    suspend fun checkHealthy(baseUrl: String): Result {
        return try {
            val resp = apiFor(baseUrl, authenticated = false).health()
            val health = resp.data
            if (resp.success && health != null) {
                if (health.api_contract < REQUIRED_SERVER_API_CONTRACT) {
                    // Stage 8D.1 §12/§14：旧 Server 可达但版本过旧 → 立即 INCOMPATIBLE，
                    // 不再继续请求 pairing / jellyfin / media 等业务 API；且
                    // **绝不清 token、绝不标记 AuthRejected / 要求重新配对**
                    //（版本不兼容 ≠ 401，Server 升级后原 Token 应继续可用）。
                    val incompatible = _connection.value.copy(
                        mediaReview = OnlineState.Online,
                        compatibility = CompatibilityState.Incompatible,
                        serverVersion = health.version,
                        serverApiContract = health.api_contract,
                    )
                    _connection.value = incompatible
                    return Result.Incompatible(
                        version = health.version,
                        apiContract = health.api_contract,
                        connection = incompatible,
                    )
                }
                val publicApi = apiFor(baseUrl, authenticated = false)
                val pairingRequired = runCatching {
                    publicApi.pairingStatus().data?.pairing_required
                }.getOrNull()
                val paired = tokenProvider.tokenFor(baseUrl) != null
                val api = apiFor(baseUrl, authenticated = true)
                var authenticationRejected = credentialRejectedInSession
                val jellyfinOnline = if (paired || pairingRequired == false) {
                    try {
                        val status = api.jellyfinStatus()
                        status.success && status.data != null
                    } catch (error: Exception) {
                        authenticationRejected = error is HttpException && error.code() == 401
                        false
                    }
                } else {
                    null
                }
                val syncState = if (paired && !authenticationRejected) {
                    try {
                        when (api.media().data?.sync?.state) {
                            "pending", "running" -> SyncState.Syncing
                            "failed", "cancelled" -> SyncState.Failed
                            "idle", "succeeded" -> SyncState.Idle
                            else -> SyncState.Unknown
                        }
                    } catch (error: Exception) {
                        if (error is HttpException && error.code() == 401) {
                            authenticationRejected = true
                        }
                        SyncState.Unknown
                    }
                } else {
                    SyncState.Unknown
                }
                val connection = buildConnectionState(
                    mediaReviewOnline = true,
                    jellyfinOnline = jellyfinOnline,
                    pairingRequired = pairingRequired,
                    paired = paired,
                    syncState = syncState,
                    authenticationRejected = authenticationRejected,
                ).copy(
                    compatibility = CompatibilityState.Compatible,
                    serverVersion = health.version,
                    serverApiContract = health.api_contract,
                )
                if (authenticationRejected && paired) {
                    tokenProvider.clear()
                    credentialRejectedInSession = true
                    try {
                        store.invalidateCredential()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        credentialCleanupMessageInSession = CREDENTIAL_CLEANUP_MESSAGE
                        return failure(CREDENTIAL_CLEANUP_MESSAGE, connection)
                    }
                    credentialCleanupMessageInSession = null
                }
                _connection.value = connection
                Result.HealthOk(
                    version = resp.data.version,
                    pairingRequired = pairingRequired,
                    connection = connection,
                )
            } else {
                failure(
                    "服务器响应异常",
                    _connection.value.copy(mediaReview = OnlineState.Online),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val connection = if (error is IOException) {
                _connection.value.copy(mediaReview = OnlineState.Offline)
            } else {
                _connection.value.copy(mediaReview = OnlineState.Online)
            }
            failure("无法连接服务器", connection)
        }
    }

    /** 用配对码完成配对,并持久化 token。 */
    suspend fun verifyAndPair(baseUrl: String, code: String): Result {
        return try {
            val deviceId = store.deviceId()
            if (deviceId.isBlank()) {
                return failure("设备身份尚未准备完成", _connection.value)
            }
            val resp = apiFor(baseUrl, authenticated = false).verify(
                com.mediareview.app.core.network.VerifyRequest(
                    device_id = deviceId,
                    code = code.trim(),
                ),
            )
            val data = resp.data
            if (resp.success && data != null && data.paired && data.token.isNotBlank()) {
                try {
                    store.saveBaseUrl(baseUrl)
                    store.savePairing(data.token)
                } catch (error: Exception) {
                    tokenProvider.clear()
                    credentialRejectedInSession = true
                    val rejected = _connection.value.copy(
                        mediaReview = OnlineState.Online,
                        authentication = AuthenticationState.Rejected,
                    )
                    if (error is CancellationException) throw error
                    try {
                        store.invalidateCredential()
                    } catch (cleanupError: CancellationException) {
                        throw cleanupError
                    } catch (_: Exception) {
                        credentialCleanupMessageInSession = CREDENTIAL_SAVE_CLEANUP_MESSAGE
                        return failure(CREDENTIAL_SAVE_CLEANUP_MESSAGE, rejected)
                    }
                    credentialCleanupMessageInSession = null
                    return failure(
                        "安全凭据无法保存",
                        rejected,
                    )
                }
                tokenProvider.set(data.token, baseUrl)
                credentialRejectedInSession = false
                credentialCleanupMessageInSession = null
                _connection.value = _connection.value.copy(
                    mediaReview = OnlineState.Online,
                    authentication = AuthenticationState.Paired,
                )
                Result.Paired(deviceId = deviceId, token = data.token)
            } else {
                val msg = resp.error?.message ?: "配对码无效或已过期"
                failure(
                    msg,
                    _connection.value.copy(
                        mediaReview = OnlineState.Online,
                        authentication = if (credentialRejectedInSession) {
                            AuthenticationState.Rejected
                        } else {
                            AuthenticationState.Unpaired
                        },
                    ),
                )
            }
        } catch (error: Exception) {
            val state = if (error is IOException) {
                _connection.value.copy(mediaReview = OnlineState.Offline)
            } else {
                _connection.value.copy(
                    mediaReview = OnlineState.Online,
                    authentication = if (
                        credentialRejectedInSession ||
                        error is HttpException && error.code() == 401
                    ) {
                        AuthenticationState.Rejected
                    } else {
                        AuthenticationState.Unpaired
                    },
                )
            }
            failure("配对失败", state)
        }
    }

    /** 载入已保存的服务器配置(启动时恢复)。 */
    suspend fun load(): ServerProfileView {
        val profile = store.current()
        credentialRejectedInSession = credentialRejectedInSession || profile.credentialRejected
        tokenProvider.clear()
        if (profile.token.isNotBlank() && !credentialRejectedInSession) {
            tokenProvider.set(profile.token, profile.baseUrl)
        }
        var restored = restoredConnectionState(profile.isPaired, credentialRejectedInSession)
        _connection.value = restored
        var message = credentialCleanupMessageInSession
        if (profile.baseUrl.isNotBlank()) {
            restored = when (val probe = checkHealthy(profile.baseUrl)) {
                is Result.HealthOk -> probe.connection
                // 旧 Server：保留 Incompatible 连接状态（含版本/contract），
                // 不清 token、不改成认证失效（Stage 8D.2 §5/§14）。
                is Result.Incompatible -> probe.connection
                is Result.Failure -> {
                    message = credentialCleanupMessageInSession ?: probe.message
                    probe.connection
                }
                else -> restored
            }
            if (credentialRejectedInSession) {
                restored = restored.copy(authentication = AuthenticationState.Rejected)
            }
        }
        _connection.value = restored
        return ServerProfileView(
            baseUrl = profile.baseUrl,
            paired = restored.authentication == AuthenticationState.Paired,
            deviceId = store.deviceId(),
            connection = restored,
            message = message,
        )
    }

    /** 清除 base URL 与持久化/内存 credential；installation ID 由 store 保留。 */
    suspend fun clear() {
        clearCredentialState(store::clear, tokenProvider::clear)
        credentialRejectedInSession = false
        credentialCleanupMessageInSession = null
        _connection.value = ConnectionState()
    }

    private companion object {
        const val CREDENTIAL_CLEANUP_MESSAGE =
            "配对凭据已失效，但无法清除本地凭据；请在连接设置中清除配置后重新配对"
        const val CREDENTIAL_SAVE_CLEANUP_MESSAGE =
            "安全凭据无法保存，且无法清除旧凭据；请在连接设置中清除配置后重新配对"
    }
}

data class ServerProfileView(
    val baseUrl: String,
    val paired: Boolean,
    val deviceId: String = "",
    val connection: ConnectionState = ConnectionState(),
    val message: String? = null,
)

enum class OnlineState { Unknown, Online, Offline }
enum class SyncState { Unknown, Idle, Syncing, Failed }
enum class AuthenticationState { Unknown, Unpaired, Paired, Rejected, NotRequired }

/**
 * Server 版本兼容性（Stage 8D.1 §11）：
 * - [Unknown]         尚未探测（或未配置）；
 * - [Compatible]      `api_contract >=` [REQUIRED_SERVER_API_CONTRACT]；
 * - [Incompatible]    旧 Server：可达且可能在线，但版本过旧**不是**认证/网络问题。
 */
enum class CompatibilityState { Unknown, Compatible, Incompatible }

data class ConnectionState(
    val mediaReview: OnlineState = OnlineState.Unknown,
    val jellyfin: OnlineState = OnlineState.Unknown,
    val sync: SyncState = SyncState.Unknown,
    val authentication: AuthenticationState = AuthenticationState.Unpaired,
    val compatibility: CompatibilityState = CompatibilityState.Unknown,
    /** Server 人类可读版本（如 "1.2.0"），仅用于展示/排查。 */
    val serverVersion: String = "",
    /** Server 返回的 `api_contract`；旧 Server 缺失时为 1。 */
    val serverApiContract: Int = 0,
)

internal fun restoredConnectionState(
    credentialPresent: Boolean,
    credentialRejected: Boolean,
): ConnectionState = ConnectionState(
    authentication = when {
        credentialRejected -> AuthenticationState.Rejected
        credentialPresent -> AuthenticationState.Unknown
        else -> AuthenticationState.Unpaired
    },
)

internal fun buildConnectionState(
    mediaReviewOnline: Boolean,
    jellyfinOnline: Boolean?,
    pairingRequired: Boolean?,
    paired: Boolean,
    syncState: SyncState,
    authenticationRejected: Boolean,
): ConnectionState = ConnectionState(
    mediaReview = if (mediaReviewOnline) OnlineState.Online else OnlineState.Offline,
    jellyfin = when (jellyfinOnline) {
        true -> OnlineState.Online
        false -> OnlineState.Offline
        null -> OnlineState.Unknown
    },
    sync = syncState,
    authentication = when {
        authenticationRejected -> AuthenticationState.Rejected
        paired -> AuthenticationState.Paired
        pairingRequired == true -> AuthenticationState.Unpaired
        pairingRequired == false -> AuthenticationState.NotRequired
        else -> AuthenticationState.Unknown
    },
)
