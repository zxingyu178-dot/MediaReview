package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.model.PlaybackEndpointDto
import com.mediareview.app.core.model.PlaybackInfoDto
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Server 播放信息（`GET /api/v1/media/{id}/playback`）→ [V2PlaybackSource]。
 *
 * 合同（沿用 1.1 设计原则，禁止无限切换）：
 * - Direct Play 必须存在，否则视为错误（fail-closed，不静默降级）；
 * - `fallback_hls` 是最多一次的 HLS 回退，缺失表示服务端不提供回退；
 * - headers（设备级 X-Emby-Token）与 URL 一起下发，URL 内绝不携带凭据；
 * - URL 一律经 [MediaUrlResolver] 解析（回归已配对主机 / 拒绝凭据进 URL），
 *   UI 不自行拼接 Server 地址。
 */
@Singleton
class V2PlaybackResolver @Inject constructor(
    private val urlResolver: MediaUrlResolver,
) {

    fun resolve(
        dto: PlaybackInfoDto,
        baseUrl: String,
        serverOnlyHosts: Set<String> = V2MediaMapper.CONTROLLED_SERVER_ONLY_HOSTS,
    ): V2PlaybackSource {
        val direct = dto.direct
        require(direct != null && direct.url.isNotBlank()) {
            "服务器未返回可直连的播放地址"
        }
        if (dto.source_type == "local") {
            require(dto.fallback_hls == null) { "本地来源不支持 Jellyfin 播放回退" }
        }
        return V2PlaybackSource(
            mediaId = dto.media_id,
            title = dto.title,
            direct = resolveEndpoint(direct, dto, baseUrl, serverOnlyHosts),
            fallbackHls = dto.fallback_hls
                ?.takeIf { it.url.isNotBlank() }
                ?.let { resolveEndpoint(it, dto, baseUrl, serverOnlyHosts) },
            resumePositionMs = dto.resume_position_ms.coerceAtLeast(0L),
            durationMs = dto.duration_ms,
            width = dto.width,
            height = dto.height,
            sourceType = dto.source_type,
        )
    }

    private fun resolveEndpoint(
        endpoint: PlaybackEndpointDto,
        dto: PlaybackInfoDto,
        baseUrl: String,
        serverOnlyHosts: Set<String>,
    ): V2PlaybackEndpoint {
        val resolved = V2PlaybackEndpoint(
            url = urlResolver.resolve(
                endpoint.url,
                baseUrl,
                dto.stream_url_rewrite_hosts.toSet() + serverOnlyHosts,
                authoritative = dto.stream_url_authoritative,
                legacyServerOnlyHeuristics = dto.stream_url_source == "legacy",
            ),
            headers = endpoint.headers.filterValues { it.isNotBlank() },
        )
        if (dto.source_type == "local") {
            val paired = baseUrl.toHttpUrl()
            val local = resolved.url.toHttpUrl()
            require(local.scheme == paired.scheme && local.host == paired.host && local.port == paired.port &&
                local.encodedPath == "/api/v1/media/${dto.media_id}/file" && local.encodedQuery == null) {
                "本地播放地址必须属于已配对服务器"
            }
        }
        return resolved
    }
}
