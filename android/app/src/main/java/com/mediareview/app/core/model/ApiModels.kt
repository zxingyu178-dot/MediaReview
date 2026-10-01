package com.mediareview.app.core.model

import kotlinx.serialization.EncodeDefault
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
    /**
     * Stage 8B.2 §6:服务端权威进度计数。
     * 默认 -1 = 未知(兼容旧 Server);0 是**合法值**(remaining=0 表示可以完成),
     * 因此绝不能把缺失字段的默认值当作 0。
     */
    val unavailable_count: Int = -1,
    val remaining_count: Int = -1,
    val completed_count: Int = -1,
    val source: ReviewSourceDto? = null,
)

/** 最近可用项(GET /review/sessions/{id}/nearest,Stage 8B.2 §16)。 */
@Serializable
data class ReviewNearestDto(
    /** null = 服务端明确该方向没有可用媒体(与网络失败完全不同)。 */
    val index: Int? = null,
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
    /** 会话里这条是否已批阅(Stage 8B.1 §11:服务端权威状态,恢复后据此显示)。 */
    val seen: Boolean = false,
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
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ReviewSeenRequest(
    val media_id: String = "",
    /**
     * 已看标记。
     *
     * 必须**显式发送**：项目 Json 配置 `encodeDefaults = false`，不加 [EncodeDefault]
     * 时 `seen=true` 会被省略，语义将隐式依赖服务端默认值（Stage 8B 起 seen 是批阅核心状态）。
     */
    @EncodeDefault
    val seen: Boolean = true,
)

/** 批阅位置请求(断点恢复:随批阅移动更新 current_index)。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ReviewPositionRequest(
    /**
     * 绝对队列索引。
     *
     * 必须显式发送：项目 Json 配置 `encodeDefaults = false`，而 0 恰好是默认值 ——
     * 不加 [EncodeDefault] 时"回到第 1 条"的位置上报会变成空 body。
     */
    @EncodeDefault
    val index: Int = 0,
)

/**
 * 批阅进度摘要（Stage 8B 修正）：
 * `POST /review/sessions/{id}/position|advance|complete` 的真实响应是
 * `{session_id, status, current_index, total_count, seen_count, ...}`，
 * 其中含整数与时间字段——**不能**用 `Map<String, Any>` 接收（会解析失败）。
 */
@Serializable
data class ReviewProgressDto(
    val session_id: String = "",
    val status: String = "",
    val current_index: Int = 0,
    val total_count: Int = 0,
    val seen_count: Int = 0,
    // Stage 8B.2 §6:-1 = 未知(兼容旧 Server);不得把缺失值当 0
    val unavailable_count: Int = -1,
    val remaining_count: Int = -1,
    val completed_count: Int = -1,
)

/**
 * 已看标记结果（Stage 8B.1 §14：服务端**权威进度**）：
 * 服务端返回 `{"media_id": "...", "seen": true, "seen_count": n, "total_count": m}`——
 * `seen` 是布尔（早期客户端用 `Map<String, String>` 接收会解析失败）；
 * `seen_count` / `total_count` 必须直接用服务端值，客户端不得自行推算。
 */
@Serializable
data class ReviewSeenResultDto(
    val media_id: String = "",
    val seen: Boolean = true,
    val seen_count: Int = 0,
    val total_count: Int = 0,
    // Stage 8B.2 §6/§11:-1 = 未知(兼容旧 Server)
    val unavailable_count: Int = -1,
    val remaining_count: Int = -1,
    val completed_count: Int = -1,
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
    /**
     * 待删除队列结果（Stage 8B §35）：
     * `POST /delete-queue/{id}` → `{"media_id": "...", "queued": true}`；
     * `DELETE /delete-queue/{id}` → `{"media_id": "...", "queued": false}`。
     */
    val queued: Boolean? = null,
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
    /** Stage 8C.1 §23: failed 项的失败原因(guard 代码),客户端映射为简短中文。 */
    val error: String? = null,
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

/**
 * 待删除摘要(GET /delete-queue/summary,Stage 8C §11):
 * 只统计 pending 的 count / total_bytes —— Organize 首页不得为显示数字拉取整个队列。
 */
@Serializable
data class DeleteQueueSummaryDto(
    val count: Int = 0,
    val total_bytes: Long = 0,
)

/**
 * 重复媒体摘要(GET /duplicates/summary,Stage 8C §12):
 * 完全/疑似分组计数 + 最近一次扫描任务状态,不返回分组本体。
 */
@Serializable
data class DuplicateSummaryDto(
    val exact_groups: Int = 0,
    val similar_groups: Int = 0,
    val scan_task_id: String? = null,
    val scan_status: String? = null,
    val scan_progress: Int = 0,
    /** Stage 8C.1 §19: 最近一次**成功**扫描时间(失败重扫不清空上次结果)。 */
    val last_successful_scan_at: String? = null,
)

/**
 * 重复分组详情(GET /duplicates/{group_id},Stage 8C §33):
 * 分组 + 成员 + 每个成员的媒体摘要 —— 客户端一次请求拿到全部对比数据,禁止 N+1。
 */
@Serializable
data class DuplicateGroupDetailDto(
    val group_id: String = "",
    val type: String = "",
    val detail: String = "",
    val count: Int = 0,
    val size_bytes: Long = 0,
    val duration_ms: Long? = null,
    val members: List<DuplicateDetailMemberDto> = emptyList(),
)

/** 分组详情成员:媒体摘要字段 + 相对封面 URL(与 /media 相同的版本号语义)。 */
@Serializable
data class DuplicateDetailMemberDto(
    val media_id: String = "",
    val name: String = "",
    val keep: Boolean = false,
    /** Stage 8C.1 §20: 扫描后失效的成员 -> false,UI 显示「文件已不可用」。 */
    val available: Boolean = true,
    val size_bytes: Long? = null,
    val duration_ms: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val media_type: String? = null,
    val cover_url: String? = null,
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

/**
 * 重复分组分页(GET /duplicates/exact|similar,Stage 8C.1 §11):
 * 扫描结果全量持久化,数量控制只在读取侧分页(items/total/page/page_size)。
 */
@Serializable
data class DuplicateGroupPageDto(
    val items: List<DuplicateGroupDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val page_size: Int = 50,
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

/**
 * "保留"选择结果(POST /duplicates/{group_id}/keep)。
 *
 * Server 返回 `{"group_id": "...", "media_id": "...", "keep": true}` ——
 * `keep` 是**布尔**：早期用 `Map<String, String>` 接收会解析失败（静默失效），
 * Stage 8C 修正为专用 DTO。
 */
@Serializable
data class DuplicateKeepResultDto(
    val group_id: String = "",
    val media_id: String = "",
    val keep: Boolean = false,
)

@Serializable
data class JellyfinStatusOut(
    val server_name: String = "",
    val version: String = "",
    val jellyfin_id: String = "",
)
