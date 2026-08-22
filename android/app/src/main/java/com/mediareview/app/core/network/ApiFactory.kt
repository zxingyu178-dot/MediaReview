package com.mediareview.app.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 根据服务器地址重建 Retrofit 的 MediaReviewApi 工厂。
 * Retrofit baseUrl 不可变,连接切换服务器时用本工厂以新地址创建实例。
 */
@Singleton
class ApiFactory @Inject constructor(
    private val okHttp: OkHttpClient,
    private val json: Json,
) {
    fun create(baseUrl: String): MediaReviewApi {
        val clean = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(clean)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MediaReviewApi::class.java)
    }
}