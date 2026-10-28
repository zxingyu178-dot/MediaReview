package com.mediareview.app.feature.v2.nsfw.network

import com.mediareview.app.feature.v2.nsfw.data.NsfwApiException
import com.mediareview.app.feature.v2.nsfw.data.NsfwEnvelope
import com.mediareview.app.feature.v2.nsfw.data.NsfwError
import com.mediareview.app.feature.v2.nsfw.data.NsfwErrorEnvelope
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** 独立 Retrofit 工厂：只为 NSFW Bridge 构建客户端，不复用 MediaReview 的 API 实例。 */
class NsfwApiFactory(
    private val authenticatedHttp: OkHttpClient,
    private val publicHttp: OkHttpClient,
    private val json: Json,
) {
    fun create(baseUrl: String, authenticated: Boolean = true): NsfwStudioApi {
        val clean = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(clean)
            .client(if (authenticated) authenticatedHttp else publicHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NsfwStudioApi::class.java)
    }

    /** 把 HTTP 错误体里的 envelope 解成统一异常；401 优先保留 HTTP 语义。 */
    fun toException(error: HttpException): NsfwApiException {
        val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
        val parsed = body?.let { runCatching { json.decodeFromString<NsfwErrorEnvelope>(it) }.getOrNull() }
        val code = parsed?.error?.code?.takeIf { it.isNotBlank() } ?: defaultCode(error.code())
        val message = parsed?.error?.message?.takeIf { it.isNotBlank() }
            ?: when (error.code()) {
                401 -> "配对凭据已失效，请重新配对"
                410 -> "游标已过期"
                409 -> "状态已被他处修改"
                else -> "请求失败（HTTP ${error.code()}）"
            }
        return NsfwApiException(code, message, error.code())
    }

    /** 200 但 success=false 的业务错误。 */
    fun <T> unwrap(envelope: NsfwEnvelope<T>): T {
        val data = envelope.data
        if (envelope.success && data != null) return data
        val err = envelope.error ?: NsfwError("EMPTY_DATA", "服务端返回空数据")
        throw NsfwApiException(err.code, err.message.ifBlank { "请求失败" })
    }

    private fun defaultCode(status: Int): String = when (status) {
        401 -> "AUTH_REQUIRED"
        409 -> "REVIEW_CONFLICT"
        410 -> "CURSOR_EXPIRED"
        422 -> "VALIDATION_ERROR"
        404 -> "CAPABILITY_MISSING"
        else -> if (status >= 500) "INTERNAL_ERROR" else "HTTP_$status"
    }
}
