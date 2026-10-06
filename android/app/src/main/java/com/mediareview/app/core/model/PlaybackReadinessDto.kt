package com.mediareview.app.core.model

import kotlinx.serialization.Serializable

/** 无凭据的电脑端播放就绪检查；不代表手机已能连接 Jellyfin。 */
@Serializable
data class PlaybackReadinessDto(
    val jellyfin_server_reachable: Boolean = false,
    val client_stream_base: String = "",
    val client_stream_source: String = "",
    val client_address_status: String = "valid",
    val client_stream_authoritative: Boolean = false,
    val device_playback_key_status: String = "",
    val media_id: String? = null,
    val media_valid: Boolean = false,
    val direct_stream: DirectStreamReadinessDto = DirectStreamReadinessDto(),
    val phone_reachability: String = "not_tested",
)

@Serializable
data class DirectStreamReadinessDto(
    val status: String = "not_tested",
    val http_status: Int? = null,
    val content_type: String? = null,
    val content_length: Long? = null,
    val content_range: String? = null,
    val bytes_read: Int = 0,
)
