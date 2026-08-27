package com.mediareview.app.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 供 Coil 图片加载使用的认证拦截器:只对 MediaReview 服务器接口路径
 * (`/api/v1/...`,如雪碧图文件)附加 Bearer token;Jellyfin 直连封面/原图 URL
 * 不附加；同时要求 scheme/host/port 与已配对 origin 精确匹配。
 */
class CacheAuthInterceptor(
    private val tokenProvider: TokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.startsWith("/api/v1/")) {
            val token = tokenProvider.tokenFor(request.url.toString())
            if (!token.isNullOrBlank()) {
                return chain.proceed(
                    request.newBuilder().header("Authorization", "Bearer $token").build()
                )
            }
        }
        return chain.proceed(request)
    }
}
