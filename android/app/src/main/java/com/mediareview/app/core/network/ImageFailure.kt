package com.mediareview.app.core.network

import coil.network.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/** Only trusted status/opaque request ID are displayed; never expose URLs, bodies or exception messages. */
internal fun imageFailureDescription(error: Throwable): String = when (error) {
    is HttpException -> {
        val response = error.response
        val reason = when (response.code) {
            401, 403 -> "图片访问未授权，请检查配对"
            404 -> "图片资源不存在"
            502 -> "上游图片读取失败"
            else -> "图片请求失败"
        }
        val requestId = response.header("X-Request-ID")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        "$reason（HTTP ${response.code}）" + (requestId?.let { "\n请求号：$it" } ?: "")
    }
    is SocketTimeoutException -> "图片请求超时，请重试"
    is IOException -> "图片网络连接失败，请检查服务器连接"
    else -> "图片解码失败或格式不受支持"
}
