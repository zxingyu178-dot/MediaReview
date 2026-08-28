package com.mediareview.app.core.network

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/** Coil/Media3 前的唯一媒体 URL 安全边界。 */
@Singleton
class MediaUrlResolver @Inject constructor() {
    fun resolve(
        rawUrl: String,
        pairedServerBaseUrl: String,
        serverOnlyHosts: Set<String> = emptySet(),
        authoritative: Boolean = false,
        legacyServerOnlyHeuristics: Boolean = true,
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
        val replaceHost = isUnsafeClientDestination(inputHost) || (
            !authoritative && (
                (legacyServerOnlyHeuristics && isReservedServerOnlyHost(inputHost)) ||
                    serverOnlyHosts.any {
                        it.equals(inputHost, ignoreCase = true)
                    }
            )
        )
        if (!replaceHost) return input.toASCIIString()
        val pairedHost = paired.host?.trim('[', ']')
            ?: throw IllegalArgumentException("配对服务器地址缺少主机")
        require(!isUnsafeClientDestination(pairedHost)) { "配对服务器地址不能指向本机或组播地址" }
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
        val query = uri.rawQuery.orEmpty()
        require(query.length <= 8192) { "媒体地址 query 过长" }
        require(';' !in query) { "媒体地址 query 分隔表达不明确" }
        val credentialNames = setOf("api_key", "apikey", "token", "authorization", "x-emby-token")
        for (parameter in query.split('&').filter { it.isNotEmpty() }) {
            var name = parameter.substringBefore('=')
            repeat(3) {
                name = strictQueryDecode(name)
                require(name.lowercase() !in credentialNames) { "媒体地址不得携带凭据" }
            }
        }
    }

    private fun strictQueryDecode(value: String): String {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            when (val char = value[index]) {
                '%' -> {
                    require(index + 2 < value.length) { "媒体地址 query 编码无效" }
                    val octet = value.substring(index + 1, index + 3).toIntOrNull(16)
                        ?: throw IllegalArgumentException("媒体地址 query 编码无效")
                    bytes.write(octet)
                    index += 3
                }
                '+' -> {
                    bytes.write(' '.code)
                    index += 1
                }
                else -> {
                    bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                    index += 1
                }
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("媒体地址 query 编码无效")
        }
    }

    private fun isUnsafeClientDestination(host: String): Boolean {
        val normalized = host.trim('[', ']').lowercase().trimEnd('.')
        if (normalized == "localhost") return true
        val isIpLiteral = ':' in normalized || normalized.all { it.isDigit() || it == '.' }
        if (!isIpLiteral) return false
        val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return true
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress
    }

    private fun isReservedServerOnlyHost(host: String): Boolean {
        val normalized = host.trim('[', ']').lowercase().trimEnd('.')
        if (':' in normalized) return false
        return '.' !in normalized || listOf(".internal", ".local", ".lan").any {
            normalized.endsWith(it)
        }
    }
}
