package com.mediareview.app.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 注入配对 token:对所有受保护 API 附加 `Authorization: Bearer <token>`。
 * token 每次从本地 DataStore 最新值读取(在 HttpClient 构造后仍可使用 lambda 注入)。
 */
class AuthInterceptor(
    private val tokenProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = if (token.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}