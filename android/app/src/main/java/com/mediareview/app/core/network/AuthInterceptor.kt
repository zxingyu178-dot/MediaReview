package com.mediareview.app.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 注入配对 token:仅对已配对 origin 的受保护 API 附加 Authorization。
 * token 每次从本地 DataStore 最新值读取(在 HttpClient 构造后仍可使用 lambda 注入)。
 */
class AuthInterceptor(
    private val tokenProvider: TokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider.tokenFor(chain.request().url.toString())
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
