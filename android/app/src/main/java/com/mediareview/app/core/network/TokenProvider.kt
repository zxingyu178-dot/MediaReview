package com.mediareview.app.core.network

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 当前已配对 token 的内存单例。
 * 由 [com.mediareview.app.core.pairing.PairingRepository] 在配对成功后更新;
 * [AuthInterceptor] 每次请求读取最新值,避免为每个 token 重建 HttpClient。
 */
@Singleton
class TokenProvider @Inject constructor() {
    @Volatile
    var token: String = ""
        private set
    @Volatile
    var pairedOrigin: String = ""
        private set

    fun set(newToken: String, baseUrl: String) {
        token = newToken
        pairedOrigin = normalizedOrigin(baseUrl).orEmpty()
    }

    fun tokenFor(requestUrl: String): String? =
        token.takeIf { it.isNotBlank() && normalizedOrigin(requestUrl) == pairedOrigin }

    fun clear() {
        token = ""
        pairedOrigin = ""
    }

    private fun normalizedOrigin(value: String): String? = value.toHttpUrlOrNull()?.let {
        "${it.scheme}://${it.host}:${it.port}"
    }
}
