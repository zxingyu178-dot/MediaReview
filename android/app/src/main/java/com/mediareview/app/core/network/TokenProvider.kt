package com.mediareview.app.core.network

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

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

    @Synchronized fun set(newToken: String, baseUrl: String) {
        token = newToken
        pairedOrigin = normalizedOrigin(baseUrl).orEmpty()
    }

    @Synchronized fun tokenFor(requestUrl: String): String? =
        token.takeIf { it.isNotBlank() && normalizedOrigin(requestUrl) == pairedOrigin }

    /** 可跨进程重启复用，但不同配对凭据/服务绝不共享图片缓存。 */
    @Synchronized internal fun imageCacheNamespaceFor(requestUrl: String): String? =
        tokenFor(requestUrl)?.let { "$pairedOrigin\u0000$it".encodeUtf8().sha256().hex() }

    @Synchronized internal fun scopedImageTokenFor(requestUrl: String, namespace: String?): String? {
        if (namespace != imageCacheNamespaceFor(requestUrl)) throw IOException("图片请求的配对状态已变化")
        return tokenFor(requestUrl)
    }

    @Synchronized fun clear() {
        token = ""
        pairedOrigin = ""
    }

    private fun normalizedOrigin(value: String): String? = value.toHttpUrlOrNull()?.let {
        "${it.scheme}://${it.host}:${it.port}"
    }
}
