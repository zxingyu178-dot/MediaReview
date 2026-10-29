package com.mediareview.app.feature.v2.nsfw

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * NSFW 图片 URL 安全规则（C-10）：
 * - 只接受 Bridge 同源相对路径，路径必须以 `/mobile/images/` 开头；
 * - 拒绝绝对 URL、协议相对 URL、其它 scheme、反斜杠、`..`、query/fragment 与控制字符；
 * - 返回空串即表示不可加载，UI 不得退化为加载外部资源。
 */
fun safeNsfwImageUrl(baseUrl: String, path: String): String {
    val origin = baseUrl.toHttpUrlOrNull() ?: return ""
    if (origin.scheme != "http" && origin.scheme != "https") return ""
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return ""
    if (!trimmed.startsWith("/mobile/images/")) return ""
    if (trimmed.startsWith("//")) return ""
    if (trimmed.contains("://")) return ""
    if (trimmed.contains('\\') || trimmed.contains("..")) return ""
    if (trimmed.contains('?') || trimmed.contains('#')) return ""
    if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return ""
    return "${origin.scheme}://${origin.host}:${origin.port}$trimmed"
}
