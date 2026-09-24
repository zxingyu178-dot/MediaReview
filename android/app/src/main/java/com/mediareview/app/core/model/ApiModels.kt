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
    val version: String = "",
    val components: Map<String, String> = emptyMap(),
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

/** 后台任务状态(GET /tasks/{task_id}):用于雪碧图生成进度与协作取消。 */
@Serializable
data class TaskStateDto(
    val task_id: String = "",
    val type: String = "",
    val status: String = "",
    val progress: Int = 0,
    val media_id: String? = null,
    val error: String? = null,
)

/** 单个播放端点(Task C):URL + 认证 headers;设备级凭据只在 headers,绝不进 URL。 */
@Serializable
data class PlaybackEndpointDto(
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/** 播放信息(GET /media/{media_id}/playback):Direct Play + 唯一一次 HLS 回退。 */
@Serializable
data class PlaybackInfoDto(
    val media_id: String = "",
    val title: String = "",
    val stream_url: String = "",
    val stream_url_authoritative: Boolean = false,
    val stream_url_source: String = "legacy",
    val stream_url_rewrite_hosts: List<String> = emptyList(),
    val media_type: String = "",
    val duration_ms: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val container: String? = null,
    /** Task C 合同:Direct Play 端点;缺失时回退 legacy stream_url(一版兼容)。 */
    val direct: PlaybackEndpointDto? = null,
    /** 唯一一次 HLS 回退端点。 */
    val fallback_hls: PlaybackEndpointDto? = null,
    /** 服务端续播位置(来自 Jellyfin UserData)。 */
    val resume_position_ms: Long = 0,
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

/**
 * 布尔结果型变更接口的统一合同（Stage 8A 修正）。
 *
 * 服务端实际返回布尔字段，例如：
 * - `POST /favorites/{id}` → `{"media_id": "...", "favorited": true, "created": true}`；
 * - `POST /media/{id}/progress` → `{"media_id": "...", "reported": true}`。
 * 早期客户端 DTO 用 `Map<String, String>` 接收，会把布尔值解析失败（收藏/进度静默失效）。
 */
@Serializable
data class MutationResultDto(
    val media_id: String = "",
    val favorited: Boolean? = null,
    val created: Boolean? = null,
    val removed: Boolean? = null,
    val reported: Boolean? = null,
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

/** 最终删除预备信息(POST /delete-queue/commit/prepare):一次性 nonce + 队列快照摘要。 */
@Serializable
data class DeleteCommitPrepDto(
    val nonce: String = "",
    val expires_at: String? = null,
    val count: Int = 0,
    val total_bytes: Long = 0,
    val media_ids: List<String> = emptyList(),
)

/** 最终删除提交请求体:一次性 nonce(只接受服务端 prepare 签发的值)。 */
@Serializable
data class CommitRequest(
    val nonce: String,
)

/**
 * 最终删除单项结果的服务端合同解析。
 *
 * Server 实际返回 `success`/`missing`/`failed`(见 server/app/services/delete_queue.py):
 * - success:文件已真实删除,计成功且推进下游内容;
 * - missing:文件本就不存在(幂等清理),推进下游内容但不计删除成功;
 * - failed :删除失败,不推进下游内容。
 * 任何其他值一律按 [Unknown] 处理(fail-closed:不推进、不计成功)。
 */
enum class DeleteOutcomeStatus(val changed: Boolean, val successful: Boolean) {
    Success(changed = true, successful = true),
    Missing(changed = true, successful = false),
    Failed(changed = false, successful = false),
    Unknown(changed = false, successful = false),
    ;

    companion object {
        fun fromWire(value: String): DeleteOutcomeStatus = when (value.lowercase()) {
            "success" -> Success
            "missing" -> Missing
            "failed" -> Failed
            else -> Unknown
        }
    }
}

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
    /** 成员明细(含人工"保留"选择),用于双栏对比页。 */
    val members: List<DuplicateMemberDto> = emptyList(),
)

/** 重复分组内单个成员(GET /duplicates*):keep 为人工"保留"选择记录。 */
@Serializable
data class DuplicateMemberDto(
    val media_id: String = "",
    val name: String = "",
    val keep: Boolean = false,
)

/** 重复扫描任务状态(GET /duplicates/status);无任务时服务端返回 {"task_id": null}。 */
@Serializable
data class DuplicateScanStatusDto(
    val task_id: String? = null,
    val type: String? = null,
    val status: String? = null,
    val progress: Int? = null,
    val error: String? = null,
)

/** 重复分组"保留"选择请求体(POST /duplicates/{group_id}/keep)。 */
@Serializable
data class DuplicateKeepRequest(
    val media_id: String,
    val keep: Boolean,
)

@Serializable
data class JellyfinStatusOut(
    val server_name: String = "",
    val version: String = "",
    val jellyfin_id: String = "",
)
