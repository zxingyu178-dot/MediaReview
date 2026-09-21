package com.mediareview.app.feature.v2.player.gsy

/**
 * 统一播放请求（未来接 Jellyfin 的最小合同）。
 *
 * @param headers 网络请求头；本阶段 Demo 为 emptyMap()。
 *                以后接 Jellyfin Direct Play / HLS 时直接传 X-Emby-Token 等头，
 *                GSY 通过 GSYVideoOptionBuilder.setMapHeadData 生效。
 */
data class GsyPlaybackRequest(
    val mediaId: String,
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)
