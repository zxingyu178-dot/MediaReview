package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.ApiFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

/**
 * Server 连接状态（Stage 8A §30）：用轻量顶部提示表达，绝不 Blocking 整个页面。
 *
 * - [Unconfigured] 未配置：还没连接过任何服务器；
 * - [Probing] 重新连接中：后台探测进行中；
 * - [Online] 在线；
 * - [Offline] 离线：网络不可达 / 超时；
 * - [AuthRejected] 认证失效：服务端返回 401（需要重新配对）。
 */
enum class V2ServerStatus { Unconfigured, Probing, Online, Offline, AuthRejected }

/** 全局 Server 状态单例：健康探测与真实请求失败都会更新它，UI 只读。 */
@Singleton
class V2ServerStatusStore @Inject constructor() {

    private val _status = MutableStateFlow(V2ServerStatus.Unconfigured)
    val status: StateFlow<V2ServerStatus> = _status.asStateFlow()

    fun update(status: V2ServerStatus) {
        _status.value = status
    }

    /** 请求成功 → 在线（探测中的瞬时态不覆盖为在线之外的值）。 */
    fun onRequestSuccess() {
        _status.value = V2ServerStatus.Online
    }

    /** 请求失败 → 按错误类型推导：401 = 认证失效；IO 异常 = 离线；其余保持现状。 */
    fun onRequestFailure(error: Throwable) {
        when {
            error is HttpException && error.code() == 401 -> _status.value = V2ServerStatus.AuthRejected
            error is IOException -> _status.value = V2ServerStatus.Offline
            else -> Unit
        }
    }
}

/**
 * 后台健康探测（V2 启动后执行，不参与启动路径）。
 *
 * 步骤刻意保持轻量：health（公开接口）→ 已配对时用 1 条媒体确认凭据有效。
 * 绝不串联旧流程的 health + pairing status + Jellyfin + media 四段检查。
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
            if (!health.success) {
                statusStore.update(V2ServerStatus.Offline)
                return
            }
            if (profile.token.isBlank()) {
                // 服务器可达但尚未配对：在线（数据源 Sheet 会提示去配对）
                statusStore.update(V2ServerStatus.Online)
                return
            }
            try {
                // 1 条媒体即可确认 token 仍然有效（401 = 认证失效）
                api.media(page = 1, pageSize = 1)
                statusStore.update(V2ServerStatus.Online)
            } catch (error: HttpException) {
                statusStore.update(
                    if (error.code() == 401) V2ServerStatus.AuthRejected else V2ServerStatus.Online,
                )
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