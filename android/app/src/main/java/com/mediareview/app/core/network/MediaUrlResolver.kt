package com.mediareview.app.core.network

import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** Coil/Media3 前的唯一媒体 URL 安全边界。 */
@Singleton
class MediaUrlResolver @Inject constructor() {
    fun resolve(
        rawUrl: String,
        pairedServerBaseUrl: String,
        serverOnlyHosts: Set<String> = emptySet(),
    ): String {
        val raw = rawUrl.trim()
        require(raw.isNotEmpty() && '\\' !in raw) { "媒体地址格式无效" }
        val paired = parseHttpUrl(pairedServerBaseUrl)
        val input = if (raw.startsWith("/")) {
            require(!raw.startsWith("//")) { "媒体地址不允许省略协议主机" }
            paired.resolve(raw)
        } else {
            parseHttpUrl(raw)
        }
        rejectCredentials(input)
        val inputHost = input.host ?: throw IllegalArgumentException("媒体地址缺少主机")
        val replaceHost = isLoopback(inputHost) || isReservedServerOnlyHost(inputHost) || serverOnlyHosts.any {
            it.equals(inputHost, ignoreCase = true)
        }
        if (!replaceHost) return input.toASCIIString()
        val pairedHost = paired.host?.trim('[', ']')
            ?: throw IllegalArgumentException("配对服务器地址缺少主机")
        return URI(
            input.scheme,
            null,
            pairedHost,
            input.port,
            input.rawPath,
            input.rawQuery,
            null,
        ).toASCIIString()
    }

    private fun parseHttpUrl(value: String): URI {
        val uri = runCatching { URI(value) }.getOrElse {
            throw IllegalArgumentException("媒体地址无法解析")
        }
        require(uri.scheme?.lowercase() in setOf("http", "https")) { "媒体地址只允许 HTTP(S)" }
        require(uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null) {
            "媒体地址包含不安全组件"
        }
        return uri
    }

    private fun rejectCredentials(uri: URI) {
        val query = uri.rawQuery.orEmpty().lowercase()
        require(
            listOf("api_key", "apikey", "token", "authorization", "x-emby-token").none {
                Regex("(^|&)$it=").containsMatchIn(query)
            },
        ) { "媒体地址不得携带凭据" }
    }

    private fun isLoopback(host: String): Boolean {
        val normalized = host.trim('[', ']').lowercase()
        return normalized == "localhost" || normalized == "::1" ||
            normalized.startsWith("127.") || normalized == "0.0.0.0" || normalized == "::"
    }

    private fun isReservedServerOnlyHost(host: String): Boolean {
        val normalized = host.trim('[', ']').lowercase().trimEnd('.')
        return '.' !in normalized || listOf(".internal", ".local", ".lan").any {
            normalized.endsWith(it)
        }
    }
}
