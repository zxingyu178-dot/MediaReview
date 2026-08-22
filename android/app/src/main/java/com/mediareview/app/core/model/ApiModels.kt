package com.mediareview.app.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 服务端统一响应包:{ success, data, error, request_id }。
 * data 类型不确定,由各仓库解析时再转为具体 DTO。
 */
@Serializable
data class Envelope<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorBody? = null,
    val request_id: String? = null,
)

@Serializable
data class ErrorBody(
    val code: String = "",
    val message: String = "",
    val details: JsonElement? = null,
)

/** 服务端健康检查。 */
@Serializable
data class HealthOut(
    val status: String = "",
    val database: String = "",
    val version: String = "",
)

/** 配对要求与已配对设备概览(GET /pairing/status)。 */
@Serializable
data class PairingStatusOut(
    val pairing_required: Boolean = false,
    val device_count: Int = 0,
)

/** 配对签名(POST /pairing/verify)。 */
@Serializable
data class VerifyOut(
    val paired: Boolean = false,
    val token: String = "",
)

/** 配对码生成结果(POST /pairing/code,仅本机/管理后台可调用)。 */
@Serializable
data class PairingCodeOut(
    val code: String = "",
    val expires_in_seconds: Int = 0,
)

/** 雪碧图清单(GET /cache/sprites/{media_id})。 */
@Serializable
data class SpriteManifestDto(
    val media_id: String = "",
    val status: String = "",
    val columns: Int = 0,
    val rows: Int = 0,
    val count: Int = 0,
    val tile_width: Int = 0,
    val tile_height: Int = 0,
    val interval_ms: Long = 0,
    val total_duration_ms: Long = 0,
    val video_width: Int = 0,
    val video_height: Int = 0,
    val url: String? = null,
    val error: String? = null,
    val updated_at: String? = null,
)

/** 触发雪碧图生成结果(POST /cache/sprites/{media_id})。 */
@Serializable
data class SpriteEnsureOut(
    val task_id: String = "",
    val status: String = "",
)

/** 播放信息(GET /media/{media_id}/playback):Jellyfin 直连流地址。 */
@Serializable
data class PlaybackInfoDto(
    val media_id: String = "",
    val title: String = "",
    val stream_url: String = "",
    val media_type: String = "",
    val duration_ms: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val container: String? = null,
)

/** 批阅会话概览(POST/GET /review/sessions)。 */
@Serializable
data class ReviewSessionDto(
    val session_id: String = "",
    val status: String = "",
    val current_index: Int = 0,
    val total_count: Int = 0,
    val seen_count: Int = 0,
    val source: ReviewSourceDto? = null,
)

/** 会话筛选/排序快照。 */
@Serializable
data class ReviewSourceDto(
    val filter: Map<String, String> = emptyMap(),
    val sort: Map<String, String> = emptyMap(),
)

/** 批阅队列项(GET /review/sessions/{id}/queue)。 */
@Serializable
data class ReviewQueueItemDto(
    val index: Int = 0,
    val media: MediaSummary? = null,
)

/** 批阅队列分页响应。 */
@Serializable
data class ReviewQueuePageDto(
    val items: List<ReviewQueueItemDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val page_size: Int = 50,
)

/** 创建批阅会话请求:只提交 source(筛选/排序),队列由服务端构建。 */
@Serializable
data class ReviewCreateRequest(
    val source: ReviewSourceDto = ReviewSourceDto(),
)

/** 标记已看/未看请求。 */
@Serializable
data class ReviewSeenRequest(
    val media_id: String = "",
    val seen: Boolean = true,
)

/** 批阅位置请求(断点恢复:随批阅移动更新 current_index)。 */
@Serializable
data class ReviewPositionRequest(
    val index: Int = 0,
)

/** 播放进度上报请求。 */
@Serializable
data class ProgressRequest(
    val position_ms: Long = 0,
    val is_paused: Boolean = false,
)

/** 收藏项(GET /favorites)。 */
@Serializable
data class FavoriteItemDto(
    val media_id: String = "",
    val media: MediaSummary? = null,
)

/** 待删除队列项(GET /delete-queue)。 */
@Serializable
data class DeleteQueueItemDto(
    val media_id: String = "",
    val status: String = "",
    val size_bytes: Long? = null,
    val added_at: String? = null,
    val media: MediaSummary? = null,
)

/** 最终删除提交结果(POST /delete-queue/commit)。 */
@Serializable
data class CommitResultDto(
    val outcome: Map<String, String> = emptyMap(),
)

/** 重复分组(GET /duplicates*)。 */
@Serializable
data class DuplicateGroupDto(
    val group_id: String = "",
    val type: String = "",
    val count: Int = 0,
    val media_ids: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    val size_bytes: Long = 0,
    val duration_ms: Long? = null,
    val detail: String = "",
)