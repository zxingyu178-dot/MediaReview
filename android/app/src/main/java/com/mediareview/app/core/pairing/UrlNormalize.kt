package com.mediareview.app.core.pairing

/** 默认 MediaReview 端口:无显式端口时补全。 */
private const val DEFAULT_PORT = 8766

/**
 * 把用户输入规范化为 base url:
 * - 已带 http:// 则保持;
 * - 否则补 http://;
 * - 无显式端口(host:port)时补 :8766,有端口则尊重用户输入。
 *
 * Stage 8D：从旧 ConnectViewModel 迁移到 core/pairing，供 V2 数据源设置页复用
 * (旧 1.x 连接页已删除，但地址规范化仍是正式能力)。
 */
internal fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    require(trimmed.isNotBlank() && '\\' !in trimmed) { "服务器地址格式无效" }
    val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
        trimmed
    } else {
        "http://$trimmed"
    }
    val uri = runCatching { java.net.URI(withScheme) }
        .getOrElse { throw IllegalArgumentException("服务器地址无法解析") }
    require(uri.scheme.lowercase() in setOf("http", "https")) { "仅支持 HTTP 或 HTTPS" }
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "服务器地址包含不支持的内容"
    }
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "服务器地址不能包含路径" }
    val normalizedHost = uri.host.trim('[', ']')
    val host = if (':' in normalizedHost) "[$normalizedHost]" else normalizedHost
    val port = if (uri.port == -1) DEFAULT_PORT else uri.port
    require(port in 1..65535) { "服务器端口无效" }
    return "${uri.scheme.lowercase()}://$host:$port"
}
