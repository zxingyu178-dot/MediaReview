package com.mediareview.app.feature.v2.model

/**
 * 播放端点：URL + 请求头。
 *
 * 认证凭据只允许出现在 [headers]（如 Jellyfin 设备级 X-Emby-Token），
 * 绝不进入 URL 的 query —— URL 本身只表达资源位置。
 */
data class V2PlaybackEndpoint(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 播放源（Stage 8A 正式路径的唯一播放输入）——异步解析结果：
 *
 * Demo：`android.resource://...` + 空 headers；
 * Server：`GET /api/v1/media/{id}/playback` 的 direct / fallback_hls + 设备级 headers。
 *
 * UI / Player 只认识本模型，不感知 Demo 与 Server 的差异，也不自行拼 Server 地址。
 */
data class V2PlaybackSource(
    val mediaId: String,
    val title: String,
    /** 首选端点（Direct Play）；真实 Server 必须返回，缺失即视为错误（fail-closed）。 */
    val direct: V2PlaybackEndpoint,
    /** 唯一一次 HLS 回退端点；为 null 表示服务端未提供回退。 */
    val fallbackHls: V2PlaybackEndpoint? = null,
    /** 服务端续播位置（Demo 恒为 0）。 */
    val resumePositionMs: Long = 0L,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    /** 指定阶段的端点；该阶段不存在时返回 null。 */
    fun endpointFor(stage: V2PlaybackStage): V2PlaybackEndpoint? = when (stage) {
        V2PlaybackStage.DIRECT -> direct
        V2PlaybackStage.HLS_FALLBACK -> fallbackHls
    }

    /**
     * 失败后的下一阶段：Direct 失败最多允许一次 HLS 回退；
     * HLS 再失败必须进入 Error，禁止无限切换（沿用 1.1 设计原则）。
     */
    fun nextStageAfterFailure(stage: V2PlaybackStage): V2PlaybackStage? = when (stage) {
        V2PlaybackStage.DIRECT -> V2PlaybackStage.HLS_FALLBACK.takeIf { fallbackHls != null }
        V2PlaybackStage.HLS_FALLBACK -> null
    }
}

/** 播放尝试阶段：Direct Play → 一次 HLS fallback → Error。 */
enum class V2PlaybackStage { DIRECT, HLS_FALLBACK }