package com.mediareview.app.feature.connect.data

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaReviewApi
import com.mediareview.app.core.network.TokenProvider
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
class PairingRepository @Inject constructor(
    private val store: ServerProfileStore,
    private val tokenProvider: TokenProvider,
    private val apiFactory: ApiFactory,
) {
    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    sealed interface Result {
        data class HealthOk(
            val version: String,
            val pairingRequired: Boolean?,
            val connection: ConnectionState,
        ) : Result
        data class Paired(val deviceId: String, val token: String) : Result
        data class Failure(
            val message: String,
            val connection: ConnectionState = ConnectionState(mediaReview = OnlineState.Offline),
        ) : Result
    }

    private fun apiFor(baseUrl: String, authenticated: Boolean = true): MediaReviewApi =
        apiFactory.create(baseUrl, authenticated)

    /** 校验服务器可达且返回健康信息。 */
    suspend fun checkHealthy(baseUrl: String): Result {
        return try {
            val resp = apiFor(baseUrl, authenticated = false).health()
            if (resp.success && resp.data != null) {
                val publicApi = apiFor(baseUrl, authenticated = false)
                val pairingRequired = runCatching {
                    publicApi.pairingStatus().data?.pairing_required
                }.getOrNull()
                val paired = tokenProvider.tokenFor(baseUrl) != null
                val api = apiFor(baseUrl, authenticated = true)
                var authenticationRejected = false
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
                        when (api.media(pageSize = 1).data?.sync?.state) {
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
                )
                if (authenticationRejected) tokenProvider.clear()
                _connection.value = connection
                Result.HealthOk(
                    version = resp.data.version,
                    pairingRequired = pairingRequired,
                    connection = connection,
                )
            } else {
                Result.Failure("服务器响应异常")
            }
        } catch (_: Exception) {
            val connection = ConnectionState(mediaReview = OnlineState.Offline)
            _connection.value = connection
            Result.Failure("无法连接服务器", connection)
        }
    }

    /** 用配对码完成配对,并持久化 token。 */
    suspend fun verifyAndPair(baseUrl: String, code: String): Result {
        return try {
            val deviceId = store.deviceId()
            if (deviceId.isBlank()) return Result.Failure("设备身份尚未准备完成")
            val resp = apiFor(baseUrl, authenticated = false).verify(
                com.mediareview.app.core.network.VerifyRequest(
                    device_id = deviceId,
                    code = code.trim(),
                ),
            )
            val data = resp.data
            if (resp.success && data != null && data.paired && data.token.isNotBlank()) {
                store.saveBaseUrl(baseUrl)
                try {
                    store.savePairing(data.token)
                } catch (_: Exception) {
                    store.invalidateCredential()
                    tokenProvider.clear()
                    _connection.value = ConnectionState(authentication = AuthenticationState.Rejected)
                    return Result.Failure("安全凭据无法保存", _connection.value)
                }
                tokenProvider.set(data.token, baseUrl)
                _connection.value = _connection.value.copy(authentication = AuthenticationState.Paired)
                Result.Paired(deviceId = deviceId, token = data.token)
            } else {
                val msg = resp.error?.message ?: "配对码无效或已过期"
                Result.Failure(msg)
            }
        } catch (e: Exception) {
            Result.Failure("配对失败:${e.message}")
        }
    }

    /** 载入已保存的服务器配置(启动时恢复)。 */
    suspend fun load(): ServerProfileView {
        val profile = store.current()
        tokenProvider.clear()
        if (profile.token.isNotBlank()) {
            tokenProvider.set(profile.token, profile.baseUrl)
        }
        var restored = restoredConnectionState(profile.isPaired, profile.credentialRejected)
        if (profile.baseUrl.isNotBlank()) {
            restored = when (val probe = checkHealthy(profile.baseUrl)) {
                is Result.HealthOk -> probe.connection
                is Result.Failure -> probe.connection
                else -> restored
            }
            if (profile.credentialRejected) {
                restored = restored.copy(authentication = AuthenticationState.Rejected)
            }
        }
        _connection.value = restored
        return ServerProfileView(
            baseUrl = profile.baseUrl,
            paired = restored.authentication == AuthenticationState.Paired,
            deviceId = store.deviceId(),
            connection = restored,
        )
    }

    /** 清除 base URL 与持久化/内存 credential；installation ID 由 store 保留。 */
    suspend fun clear() {
        clearCredentialState(store::clear, tokenProvider::clear)
    }
}

data class ServerProfileView(
    val baseUrl: String,
    val paired: Boolean,
    val deviceId: String = "",
    val connection: ConnectionState = ConnectionState(),
)

enum class OnlineState { Unknown, Online, Offline }
enum class SyncState { Unknown, Idle, Syncing, Failed }
enum class AuthenticationState { Unknown, Unpaired, Paired, Rejected, NotRequired }

data class ConnectionState(
    val mediaReview: OnlineState = OnlineState.Unknown,
    val jellyfin: OnlineState = OnlineState.Unknown,
    val sync: SyncState = SyncState.Unknown,
    val authentication: AuthenticationState = AuthenticationState.Unpaired,
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
