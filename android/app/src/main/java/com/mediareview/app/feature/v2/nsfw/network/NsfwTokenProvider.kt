package com.mediareview.app.feature.v2.nsfw.network

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * NSFW Studio Bridge 的 Bearer token（内存单例）。
 *
 * 与 [com.mediareview.app.core.network.TokenProvider] 完全独立：
 * - 只注入到 NSFW 自己的 OkHttpClient；
 * - 401/吊销时只清这里，绝不触碰 MediaReview Server 凭据。
 */
@Singleton
class NsfwTokenProvider @Inject constructor() {
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
