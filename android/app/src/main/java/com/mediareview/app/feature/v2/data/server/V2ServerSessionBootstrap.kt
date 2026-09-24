package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.datastore.ServerProfile
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.TokenProvider
import javax.inject.Inject
import javax.inject.Singleton

/** 启动时恢复的 Server 会话状态（只读本地，不做网络探测）。 */
data class V2ServerSession(
    val configured: Boolean = false,
    val baseUrl: String = "",
    val paired: Boolean = false,
    val credentialRejected: Boolean = false,
)

/**
 * Server 模式启动引导（Stage 8A §29）。
 *
 * 只做两件事：
 * 1. 读取 [ServerProfileStore.current]（baseUrl / token）；
 * 2. 恢复 [TokenProvider]（后续请求自动带 Bearer）。
 *
 * 明确禁止：不调用 PairingRepository.load()、不做 health / pairing status / Jellyfin /
 * media 的串行检查——启动流程绝不能因为 Server 不可达而变慢或卡住（V2 启动必须直接进首页）。
 */
@Singleton
class V2ServerSessionBootstrap @Inject constructor(
    private val store: ServerProfileStore,
    private val tokenProvider: TokenProvider,
) {

    /** 恢复会话；凭据已失效时不装载 token（等待用户在数据源设置里重新配对）。 */
    suspend fun restore(): V2ServerSession {
        val profile: ServerProfile = store.current()
        tokenProvider.clear()
        val rejected = profile.credentialRejected
        if (profile.token.isNotBlank() && !rejected) {
            tokenProvider.set(profile.token, profile.baseUrl)
        }
        return V2ServerSession(
            configured = profile.baseUrl.isNotBlank(),
            baseUrl = profile.baseUrl,
            paired = profile.token.isNotBlank() && !rejected,
            credentialRejected = rejected,
        )
    }

    /** 配对成功后（PairingRepository 已写入 store 与 TokenProvider）刷新内存会话。 */
    suspend fun refresh(): V2ServerSession = restore()
}