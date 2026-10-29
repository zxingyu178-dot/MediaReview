package com.mediareview.app.feature.v2.nsfw.data

import com.mediareview.app.core.pairing.normalizeBaseUrl
import com.mediareview.app.feature.v2.nsfw.network.NsfwApiFactory
import com.mediareview.app.feature.v2.nsfw.network.NsfwTokenProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** 独立 NSFW 配对存储端口（测试可替换，不依赖 Android DataStore）。 */
internal interface NsfwStorePort {
    suspend fun current(): NsfwProfile
    suspend fun saveBaseUrl(baseUrl: String)
    suspend fun savePairing(token: String)
    suspend fun invalidateCredential()
    suspend fun installationId(): String
    suspend fun clear()
}

internal class DataStoreNsfwStorePort(private val delegate: NsfwProfileStore) : NsfwStorePort {
    override suspend fun current() = delegate.current()
    override suspend fun saveBaseUrl(baseUrl: String) = delegate.saveBaseUrl(baseUrl)
    override suspend fun savePairing(token: String) = delegate.savePairing(token)
    override suspend fun invalidateCredential() = delegate.invalidateCredential()
    override suspend fun installationId() = delegate.installationId()
    override suspend fun clear() = delegate.clear()
}

data class NsfwConnectionSnapshot(
    val baseUrl: String = "",
    val paired: Boolean = false,
    val rejected: Boolean = false,
    val message: String? = null,
    /** 恢复已保存配对后重新执行 capability gate 的结果；null 表示未能确认（不得当作 compatible）。 */
    val compatible: Boolean? = true,
    val product: String = "",
    val apiContract: Int = 0,
    val missingCapabilities: List<String> = emptyList(),
)

sealed interface NsfwPairResult {
    data class Paired(val baseUrl: String, val deviceId: String) : NsfwPairResult
    data class Blocked(
        val product: String,
        val apiContract: Int,
        val missingCapabilities: List<String>,
        val message: String,
    ) : NsfwPairResult
    data class Failed(val message: String, val unauthorized: Boolean = false) : NsfwPairResult
}

/** ViewModel 只依赖该接口，测试用假实现验证门禁、隔离与重配对。 */
interface NsfwPairingGateway {
    suspend fun load(): NsfwConnectionSnapshot
    suspend fun checkAndPair(baseUrlInput: String, code: String): NsfwPairResult
    suspend fun clear()
    suspend fun handleAuthRejected()
}

/**
 * NSFW Studio 独立配对流程：
 * 1) 先把用户输入规范化（默认端口 8767）；
 * 2) `GET /mobile/system/health` 校验 product / api_contract / capabilities；
 * 3) 只有全部满足才 POST /mobile/pairing/verify 换 token；
 * 4) token 只写 NSFW 自己的 DataStore 与内存 provider。
 *
 * 当前真实 Bridge 缺少 v2 能力 → 步骤 2 返回 [NsfwPairResult.Blocked]，
 * 这是刻意行为：客户端绝不把"能连旧 Bridge"当成"可审核"。
 */
class NsfwPairingRepository internal constructor(
    private val store: NsfwStorePort,
    private val tokenProvider: NsfwTokenProvider,
    private val apiFactory: NsfwApiFactory,
    private val deviceName: String = "MediaReview Android",
) : NsfwPairingGateway {

    constructor(
        store: NsfwProfileStore,
        tokenProvider: NsfwTokenProvider,
        apiFactory: NsfwApiFactory,
    ) : this(DataStoreNsfwStorePort(store), tokenProvider, apiFactory)

    override suspend fun load(): NsfwConnectionSnapshot {
        val profile = store.current()
        if (profile.credentialRejected) {
            tokenProvider.clear()
            return NsfwConnectionSnapshot(
                baseUrl = profile.baseUrl,
                paired = false,
                rejected = true,
                message = "NSFW 配对凭据已损坏，请重新配对",
            )
        }
        if (!profile.isPaired) {
            tokenProvider.clear()
            return NsfwConnectionSnapshot(baseUrl = profile.baseUrl, paired = false)
        }
        tokenProvider.set(profile.token, profile.baseUrl)
        // 恢复连接必须重新过 capability gate：旧 Bridge/降级服务端不能因为本地有 token 就当成可用。
        val health = try {
            apiFactory.unwrap(apiFactory.create(profile.baseUrl, authenticated = false).health())
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            return NsfwConnectionSnapshot(
                baseUrl = profile.baseUrl,
                paired = true,
                compatible = null,
                message = "暂时无法校验 Bridge 版本，请检查连接",
            )
        } catch (error: Exception) {
            return NsfwConnectionSnapshot(
                baseUrl = profile.baseUrl,
                paired = true,
                compatible = null,
                message = "Bridge 健康检查失败：${error.message ?: "未知错误"}",
            )
        }
        val missing = NSFW_REQUIRED_CAPABILITIES.filterNot { it in health.capabilities }
        val productOk = health.product.startsWith(NSFW_PRODUCT_PREFIX)
        val contractOk = health.apiContract >= NSFW_REQUIRED_API_CONTRACT
        if (productOk && contractOk && missing.isEmpty()) {
            return NsfwConnectionSnapshot(baseUrl = profile.baseUrl, paired = true, compatible = true)
        }
        val reasons = buildList {
            if (!productOk) add("产品标识不匹配（${health.product.ifBlank { "未知" }}）")
            if (!contractOk) add("api_contract=${health.apiContract}，要求 ≥ $NSFW_REQUIRED_API_CONTRACT")
            if (missing.isNotEmpty()) add("缺少能力：${missing.joinToString("、")}")
        }
        return NsfwConnectionSnapshot(
            baseUrl = profile.baseUrl,
            paired = true,
            compatible = false,
            product = health.product,
            apiContract = health.apiContract,
            missingCapabilities = missing,
            message = "已保存的配对仍然有效，但当前 Bridge 不支持生图审核 v2：" + reasons.joinToString("；"),
        )
    }

    override suspend fun checkAndPair(baseUrlInput: String, code: String): NsfwPairResult {
        val baseUrl = try {
            normalizeBaseUrl(baseUrlInput, defaultPort = 8767)
        } catch (error: IllegalArgumentException) {
            return NsfwPairResult.Failed(error.message ?: "服务器地址格式无效")
        }
        val health = try {
            val envelope = apiFactory.create(baseUrl, authenticated = false).health()
            apiFactory.unwrap(envelope)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            val mapped = apiFactory.toException(error)
            return NsfwPairResult.Failed("无法读取 Bridge 健康信息：${mapped.message}", mapped.isAuthRejected)
        } catch (error: IOException) {
            return NsfwPairResult.Failed("无法连接 NSFW Bridge（请确认同一局域网且已启动）")
        } catch (error: Exception) {
            return NsfwPairResult.Failed(error.message ?: "Bridge 健康检查失败")
        }

        val gate = capabilityGate(health)
        if (gate != null) return gate

        return try {
            val installationId = store.installationId()
            if (installationId.isBlank()) return NsfwPairResult.Failed("设备身份尚未准备完成")
            val result = apiFactory.unwrap(
                apiFactory.create(baseUrl, authenticated = false).verifyPairing(
                    NsfwPairingRequest(
                        installationId = installationId,
                        deviceName = deviceName,
                        code = code.trim(),
                    ),
                ),
            )
            if (result.token.isBlank()) return NsfwPairResult.Failed("配对响应缺少 token")
            try {
                store.saveBaseUrl(baseUrl)
                store.savePairing(result.token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                tokenProvider.clear()
                runCatching { store.invalidateCredential() }
                return NsfwPairResult.Failed("安全凭据无法保存，请重试")
            }
            tokenProvider.set(result.token, baseUrl)
            NsfwPairResult.Paired(baseUrl = baseUrl, deviceId = result.deviceId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            val mapped = apiFactory.toException(error)
            if (mapped.isAuthRejected) tokenProvider.clear()
            NsfwPairResult.Failed(mapped.message, mapped.isAuthRejected)
        } catch (error: IOException) {
            NsfwPairResult.Failed("配对请求未到达 NSFW Bridge")
        } catch (error: Exception) {
            NsfwPairResult.Failed(error.message ?: "配对失败")
        }
    }

    override suspend fun clear() {
        tokenProvider.clear()
        store.clear()
    }

    override suspend fun handleAuthRejected() {
        tokenProvider.clear()
        store.invalidateCredential()
    }

    private fun capabilityGate(health: NsfwHealth): NsfwPairResult.Blocked? {
        val missing = NSFW_REQUIRED_CAPABILITIES.filterNot { it in health.capabilities }
        val productOk = health.product.startsWith(NSFW_PRODUCT_PREFIX)
        val contractOk = health.apiContract >= NSFW_REQUIRED_API_CONTRACT
        if (productOk && contractOk && missing.isEmpty()) return null
        val reasons = buildList {
            if (!productOk) add("产品标识不匹配（${health.product.ifBlank { "未知" }}）")
            if (!contractOk) add("api_contract=${health.apiContract}，要求 ≥ $NSFW_REQUIRED_API_CONTRACT")
            if (missing.isNotEmpty()) add("缺少能力：${missing.joinToString("、")}")
        }
        return NsfwPairResult.Blocked(
            product = health.product,
            apiContract = health.apiContract,
            missingCapabilities = missing,
            message = "当前 NSFW Bridge 不支持生图审核 v2：" + reasons.joinToString("；"),
        )
    }
}
