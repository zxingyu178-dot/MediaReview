package com.mediareview.app.feature.connect.data

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaReviewApi
import com.mediareview.app.core.network.TokenProvider
import javax.inject.Inject
import javax.inject.Singleton

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

    sealed interface Result {
        data class HealthOk(val version: String, val pairingRequired: Boolean) : Result
        data class Paired(val deviceId: String, val token: String) : Result
        data class Failure(val message: String) : Result
    }

    private fun apiFor(baseUrl: String): MediaReviewApi = apiFactory.create(baseUrl)

    /** 校验服务器可达且返回健康信息。 */
    suspend fun checkHealthy(baseUrl: String): Result {
        return try {
            val resp = apiFor(baseUrl).health()
            if (resp.success && resp.data != null) {
                Result.HealthOk(
                    version = resp.data.version,
                    pairingRequired = try {
                        apiFor(baseUrl).pairingStatus().data?.pairing_required ?: false
                    } catch (_: Exception) {
                        false
                    },
                )
            } else {
                Result.Failure("服务器响应异常:${resp.error?.message}")
            }
        } catch (e: Exception) {
            Result.Failure("无法连接服务器:${e.message}")
        }
    }

    /** 用配对码完成配对,并持久化 token。 */
    suspend fun verifyAndPair(baseUrl: String, deviceId: String, code: String): Result {
        return try {
            val resp = apiFor(baseUrl).verify(
                com.mediareview.app.core.network.VerifyRequest(
                    device_id = deviceId,
                    code = code.trim(),
                ),
            )
            val data = resp.data
            if (resp.success && data != null && data.paired && data.token.isNotBlank()) {
                store.saveBaseUrl(baseUrl)
                store.savePairing(data.token, deviceId)
                tokenProvider.set(data.token)
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
        if (profile.token.isNotBlank()) {
            tokenProvider.set(profile.token)
        }
        return ServerProfileView(
            baseUrl = profile.baseUrl,
            paired = profile.isPaired,
            deviceId = store.deviceId(),
        )
    }
}

data class ServerProfileView(
    val baseUrl: String,
    val paired: Boolean,
    val deviceId: String = "",
)