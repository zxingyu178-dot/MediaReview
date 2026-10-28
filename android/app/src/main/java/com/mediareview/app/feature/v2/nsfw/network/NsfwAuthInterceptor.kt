package com.mediareview.app.feature.v2.nsfw.network

import okhttp3.Interceptor
import okhttp3.Response

/** 只对已配对的 NSFW origin 注入 Bearer；不读取 MediaReview 的 token。 */
class NsfwAuthInterceptor(
    private val tokenProvider: NsfwTokenProvider,
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
