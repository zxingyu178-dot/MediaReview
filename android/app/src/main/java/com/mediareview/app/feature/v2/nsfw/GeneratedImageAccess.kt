package com.mediareview.app.feature.v2.nsfw

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 独立桥配置。故意不实现data class，避免toString把凭据写入日志。 */
class GeneratedBridgeProfile(val baseUrl: String = "", val token: String = "")

/** 不复用MediaReview的API客户端、认证拦截器或图片缓存。 */
class GeneratedImageAccess(private val profile: GeneratedBridgeProfile) {
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    fun request(imageUrl: String): Request {
        require(profile.token.isNotBlank()) { "请先配置生图服务凭据" }
        val base = profile.baseUrl.toHttpUrl()
        val target = base.resolve(imageUrl) ?: error("图片地址无效")
        require(base.username.isEmpty() && base.password.isEmpty() &&
            target.username.isEmpty() && target.password.isEmpty() &&
            target.scheme == base.scheme && target.host == base.host && target.port == base.port &&
            target.fragment == null) { "图片地址不属于已配置的生图服务" }
        return Request.Builder().url(target).header("Authorization", "Bearer ${profile.token}").build()
    }
}
