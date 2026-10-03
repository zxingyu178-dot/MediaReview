package com.mediareview.app.feature.v2.data.server

import javax.inject.Inject
import javax.inject.Singleton

/** Server 尚未完成兼容性探测（Probing）：业务 API 此时不得发出。 */
class ServerNotReadyException(
    message: String = "服务器仍在检测中，请稍候",
) : IllegalStateException(message)

/** Server 不可达（Offline）。 */
class ServerOfflineException(
    message: String = "服务器当前不可用，请检查网络或服务器状态",
) : IllegalStateException(message)

/** 认证失效（AuthRejected）：需要重新配对。 */
class ServerAuthRejectedException(
    message: String = "配对凭据已失效，请在数据源设置中重新配对",
) : IllegalStateException(message)

/** 尚未配置任何 Server（Unconfigured）。 */
class ServerNotConfiguredException(
    message: String = "尚未连接服务器",
) : IllegalStateException(message)

/**
 * 统一的 Server 业务访问 Guard（Stage 8D.2 §13~§16）。
 *
 * **硬规则**：Server 业务 API 只有在 [V2ServerStatus.Online] 时才允许发出。
 *
 * 这是最后一道防线：不再依赖 UI / Router 各自判断，所有 Server Repository 的
 * 网络 `call()` 都在开头调用 [requireBusinessAccess]。
 *
 * 例外（§16）：`/system/health`、`/pairing/status`、`/pairing/verify`
 * 不属于业务 API，它们用于**建立**兼容性/连接，不经过本 Guard。
 */
@Singleton
class V2ServerAccessGuard @Inject constructor(
    private val statusStore: V2ServerStatusStore,
) {

    /** 允许业务调用则直接返回，否则抛出对应的明确异常。 */
    fun requireBusinessAccess() {
        when (statusStore.status.value) {
            V2ServerStatus.Online -> Unit
            V2ServerStatus.Incompatible ->
                throw ServerIncompatibleException(statusStore.serverVersion.value)
            V2ServerStatus.Probing -> throw ServerNotReadyException()
            V2ServerStatus.Offline -> throw ServerOfflineException()
            V2ServerStatus.AuthRejected -> throw ServerAuthRejectedException()
            V2ServerStatus.Unconfigured -> throw ServerNotConfiguredException()
        }
    }

    /** 便捷判断：当前是否允许发出业务请求。 */
    fun canAccessBusiness(): Boolean = statusStore.status.value == V2ServerStatus.Online
}

/**
 * Stage 8D.2 §8/§11：只有兼容([V2ServerStatus.Online])才允许加载 Server 业务数据。
 *
 * 抽成纯函数，作为 UI（ViewModel 何时 reload）与 Repository（Guard）**唯一**的判定口径，
 * 便于 JVM 合同测试直接锁定"哪些状态不得 reload"。
 */
fun shouldLoadServerBusinessData(status: V2ServerStatus): Boolean =
    status == V2ServerStatus.Online
