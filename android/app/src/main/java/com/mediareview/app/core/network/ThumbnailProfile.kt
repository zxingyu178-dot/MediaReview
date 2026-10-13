package com.mediareview.app.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 唯一允许的缩略图变体；尺寸同时作为客户端有界解码目标。 */
enum class ThumbnailProfile(val width: Int, val height: Int) {
    GRID_SMALL(256, 144), GRID_LARGE(480, 270), ALBUM(320, 427);
}

/** 只改MediaReview媒体缩略图路径，保留origin、v和其余查询；Demo/Jellyfin原样。 */
fun thumbnailProfileUrl(raw: String, profile: ThumbnailProfile): String {
    val url = raw.toHttpUrlOrNull() ?: return raw
    if (!Regex("/api/v1/media/[^/]+/(thumbnail|cover)").matches(url.encodedPath)) return raw
    return url.newBuilder()
        .encodedPath(url.encodedPath.removeSuffix("/cover") + if (url.encodedPath.endsWith("/cover")) "/thumbnail" else "")
        .setQueryParameter("profile", profile.name).build().toString()
}

/** 生产original URL未带v；继承同origin、同媒体thumbnail的内容指纹。 */
fun originalSourceVersionUrl(raw: String, coverRaw: String): String {
    val original = raw.toHttpUrlOrNull() ?: return raw
    val cover = coverRaw.toHttpUrlOrNull() ?: return raw
    if (original.queryParameter("v") != null || !Regex("/api/v1/media/[^/]+/original").matches(original.encodedPath)) return raw
    if (original.scheme != cover.scheme || original.host != cover.host || original.port != cover.port) return raw
    val coverMediaPath = when {
        cover.encodedPath.endsWith("/thumbnail") -> cover.encodedPath.removeSuffix("/thumbnail")
        cover.encodedPath.endsWith("/cover") -> cover.encodedPath.removeSuffix("/cover")
        else -> return raw
    }
    if (original.encodedPath.removeSuffix("/original") != coverMediaPath) return raw
    val version = cover.queryParameter("v")?.takeIf { it.isNotBlank() } ?: return raw
    return original.newBuilder().setQueryParameter("v", version).build().toString()
}
