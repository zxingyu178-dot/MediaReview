package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.pairing.REQUIRED_SERVER_API_CONTRACT
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

/**
 * Server 连接状态（Stage 8A §30；Stage 8D.1 §15 新增 [Incompatible]）。
 *
 * - [Unconfigured] 未配置：还没连接过任何服务器；
 * - [Probing] 重新连接中：后台探测进行中；
 * - [Online] 在线；
 * - [Offline] 离线：网络不可达 / 超时；
 * - [AuthRejected] 认证失效：服务端返回 401（需要重新配对）；
 * - [Incompatible] **服务器版本过旧**：可达但不是认证/网络问题（§13），
 *   不得清 token、不得要求重新配对（§14）。
 */
enum class V2ServerStatus { Unconfigured, Probing, Online, Offline, AuthRejected, Incompatible }

/**
 * 已知 Server 版本过旧时的 fail-fast 异常（Stage 8D.1 §17）。
 *
 * 即使 UI 兼容 Gate 有 Bug，Server 数据仓储也不会在已知不兼容状态下继续大量
 * 请求业务 API（避免首页出现一串 404 才发现电脑端过旧）。
 */
class ServerIncompatibleException(
    val serverVersion: String = "",
    message: String = "服务器版本过旧，请升级 MediaReview Server",
) : IllegalStateException(message)

/** 全局 Server 状态单例：健康探测与真实请求失败都会更新它，UI 只读。 */
@Singleton
class V2ServerStatusStore @Inject constructor() {

    private val _status = MutableStateFlow(V2ServerStatus.Unconfigured)
    val status: StateFlow<V2ServerStatus> = _status.asStateFlow()

    private val _serverVersion = MutableStateFlow("")
    /** Server 人类可读版本（如 "1.2.0"），仅用于展示/排查（§36）。 */
    val serverVersion: StateFlow<String> = _serverVersion.asStateFlow()

    private val _serverApiContract = MutableStateFlow(0)
    /** Server 返回的 api_contract（旧 Server 缺失时为 1）。 */
    val serverApiContract: StateFlow<Int> = _serverApiContract.asStateFlow()

    fun update(status: V2ServerStatus) {
        _status.value = status
    }

    /** 探测到兼容：记录版本 + contract 并标记在线（**唯一**进入 Online 的入口）。 */
    fun recordCompatible(version: String, contract: Int) {
        _serverVersion.value = version
        _serverApiContract.value = contract
        _status.value = V2ServerStatus.Online
    }

    /** 探测到旧 Server：记录版本 + contract 并标记"版本过旧"（不是离线 / 认证失败）。 */
    fun recordIncompatible(version: String, contract: Int) {
        _serverVersion.value = version
        _serverApiContract.value = contract
        _status.value = V2ServerStatus.Incompatible
    }

    /**
     * 业务请求成功（Stage 8D.2 §20~§22）：**不得**把状态提升为 Online。
     *
     * Online 只能由兼容的 health probe（[recordCompatible]）建立；普通请求成功
     * 不能推翻 Incompatible / Probing / Unconfigured / AuthRejected。
     * 正常情况下 Guard 已保证"非 Online 不会发出业务请求"，所以这里保持现状即可。
     */
    fun onRequestSuccess() {
        // 故意 no-op：不改变 status（Online 保持 Online，其它状态不被"碰巧成功"污染）。
    }

    /** 请求失败 → 按错误类型推导：401 = 认证失效；IO 异常 = 离线（仅从 Online 迁移）。 */
    fun onRequestFailure(error: Throwable) {
        // §22/§23：只有 Online 才可能正在发业务请求；其它状态由 Guard 阻止，不得被覆盖。
        if (_status.value != V2ServerStatus.Online) return
        when {
            error is HttpException && error.code() == 401 -> _status.value = V2ServerStatus.AuthRejected
            error is IOException -> _status.value = V2ServerStatus.Offline
            else -> Unit
        }
    }

    /**
     * 切换服务器 / 断开 / 重新配对时调用（Stage 8D.2 §25）：
     * 清空旧 Server 的版本 / contract / 兼容性元数据，避免残留。
     */
    fun reset() {
        _serverVersion.value = ""
        _serverApiContract.value = 0
        _status.value = V2ServerStatus.Unconfigured
    }
}

/**
 * 后台健康探测（V2 启动后执行，不参与启动路径）。
 *
 * 步骤刻意保持轻量：health（公开接口）→ 兼容性 Gate（api_contract）→
 * 已配对时用 1 条媒体确认凭据有效。绝不串联旧流程的四段检查。
 */
@Singleton
class V2ServerHealthMonitor @Inject constructor(
    private val store: ServerProfileStore,
    private val apiFactory: ApiFactory,
    private val statusStore: V2ServerStatusStore,
) {

    suspend fun probe() {
        val profile = store.current()
        if (profile.baseUrl.isBlank()) {
            statusStore.update(V2ServerStatus.Unconfigured)
            return
        }
        statusStore.update(V2ServerStatus.Probing)
        try {
            val api = apiFactory.create(profile.baseUrl, authenticated = true)
            val health = api.health()
            val data = health.data
            if (!health.success || data == null) {
                statusStore.update(V2ServerStatus.Offline)
                return
            }
            // Stage 8D.1 §12：先判 contract；旧 Server 立即 INCOMPATIBLE 且不再请求业务 API。
            if (data.api_contract < REQUIRED_SERVER_API_CONTRACT) {
                statusStore.recordIncompatible(data.version, data.api_contract)
                return
            }
            if (profile.token.isBlank()) {
                // 服务器可达且兼容但尚未配对：在线（数据源 Sheet 会提示去配对）
                statusStore.recordCompatible(data.version, data.api_contract)
                return
            }
            try {
                // 1 条媒体即可确认 token 仍然有效（401 = 认证失效）
                api.media(page = 1, pageSize = 1)
                statusStore.recordCompatible(data.version, data.api_contract)
            } catch (error: HttpException) {
                if (error.code() == 401) {
                    statusStore.update(V2ServerStatus.AuthRejected)
                } else {
                    statusStore.recordCompatible(data.version, data.api_contract)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            statusStore.update(V2ServerStatus.Offline)
        } catch (_: Exception) {
            statusStore.update(V2ServerStatus.Offline)
        }
    }
}
