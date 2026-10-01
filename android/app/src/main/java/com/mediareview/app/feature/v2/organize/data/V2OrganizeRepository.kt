package com.mediareview.app.feature.v2.organize.data

import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.model.V2Media

/**
 * V2 整理中心（Organize）统一数据接口 —— Stage 8C。
 *
 * 设计约束（任务书 §4/§6）：
 * - **独立数据层**：不复用旧 `feature/deletequeue` / `feature/duplicates` / `feature/home/data`
 *   的 Repository —— 旧实现把网络失败 catch 成 `emptyList/null/false`，等于把"没有数据"
 *   伪装成"请求成功"，违反 V2「网络失败 ≠ 空数据」原则；
 * - **每个失败显式表达**：本接口的所有方法失败时**上抛异常**，调用方（ViewModel）
 *   据此进入 Error 状态并提供重试，绝不静默返回空值；
 * - Server 权威：删除 / 保留 / 媒体库选择只有服务端确认成功才允许更新 UI。
 *
 * 由 [V2OrganizeRepositoryRouter] 按运行时数据源模式在 Server / Demo 实现之间委托。
 */
interface V2OrganizeRepository {

    /** 当前生效的数据源模式（UI 据此区分"仅服务器模式可用"）。 */
    val mode: V2DataMode

    // ---------- Overview 摘要（每张卡独立取数，失败互不影响） ----------

    /** 待删除摘要：`GET /delete-queue/summary`（只统计 pending）。 */
    suspend fun deleteSummary(): DeleteQueueSummary

    /** 重复媒体摘要：`GET /duplicates/summary`（计数 + 最近扫描任务状态）。 */
    suspend fun duplicateSummary(): DuplicateSummary

    /** 批阅进度：`GET /review/sessions/latest`；**没有 active 会话时返回 null**（不是失败）。 */
    suspend fun reviewSummary(): ReviewProgressSummary?

    /** 媒体库摘要：`GET /libraries` 的选中/总数。 */
    suspend fun librarySummary(): LibrarySelectionSummary

    // ---------- 待删除队列 ----------

    /** 队列列表：`GET /delete-queue`（含媒体摘要与封面，无 N+1）。 */
    suspend fun loadDeleteQueue(): List<DeleteQueueEntry>

    /** 恢复单项：`DELETE /delete-queue/{media_id}`；失败上抛（UI 保持原样 + 提示）。 */
    suspend fun restoreDeleteItem(mediaId: String)

    /** 最终删除第一步：`POST /delete-queue/commit/prepare`（返回 nonce 快照）。 */
    suspend fun prepareDeleteCommit(): DeleteCommitPrepare

    /** 最终删除第二步：用 prepare 的 nonce 提交（`POST /delete-queue/commit`），禁止重新 prepare。 */
    suspend fun commitDelete(nonce: String): Map<String, DeleteOutcomeStatus>

    // ---------- 重复媒体 ----------

    /** 分组列表：`GET /duplicates/exact` / `GET /duplicates/similar`。 */
    suspend fun loadDuplicateGroups(type: DuplicateGroupType): List<DuplicateGroupSummary>

    /** 分组详情（对比页）：`GET /duplicates/{group_id}`，一次请求拿全部成员，客户端零 N+1。 */
    suspend fun loadDuplicateDetail(groupId: String): DuplicateGroupDetail

    /** 触发扫描：`POST /duplicates/scan`（服务端幂等）。 */
    suspend fun startDuplicateScan(): DuplicateScanState

    /** 扫描状态：`GET /duplicates/status`。 */
    suspend fun duplicateScanStatus(): DuplicateScanState

    /** 暂停扫描：`POST /tasks/{task_id}/pause`。 */
    suspend fun pauseDuplicateScan(taskId: String): DuplicateScanState

    /** 继续扫描：`POST /tasks/{task_id}/resume`。 */
    suspend fun resumeDuplicateScan(taskId: String): DuplicateScanState

    /** 取消扫描：`POST /tasks/{task_id}/cancel`。 */
    suspend fun cancelDuplicateScan(taskId: String): DuplicateScanState

    /**
     * 人工"保留"选择：`POST /duplicates/{group_id}/keep`。
     * 只有服务端成功才允许更新 UI（失败上抛）；keep 只是整理选择，**绝不是删除**。
     */
    suspend fun setDuplicateKeep(groupId: String, mediaId: String, keep: Boolean)

    // ---------- 媒体库管理 ----------

    /** 媒体库列表：`GET /libraries`（Server 同步勾选状态）。 */
    suspend fun loadLibraries(): List<LibrarySelectionItem>

    /** 保存勾选：`PUT /libraries/selection`；只有服务端成功才返回（失败上抛，本地草稿保留）。 */
    suspend fun saveLibrarySelection(selectedIds: List<String>): List<LibrarySelectionItem>
}

/** 待删除摘要。 */
data class DeleteQueueSummary(val count: Int, val totalBytes: Long)

/** 重复媒体摘要。 */
data class DuplicateSummary(
    val exactGroups: Int,
    val similarGroups: Int,
    val scanTaskId: String?,
    val scanStatus: String?,
    val scanProgress: Int,
)

/** 批阅进度摘要（来自 active Review Session，绝不伪造历史累计）。 */
data class ReviewProgressSummary(val seen: Int, val total: Int, val remaining: Int)

/** 媒体库摘要（已选 / 总数）。 */
data class LibrarySelectionSummary(val selected: Int, val total: Int)

/** 待删除队列项（含媒体摘要；媒体索引缺失时 media 为 null，仍展示占位信息）。 */
data class DeleteQueueEntry(
    val mediaId: String,
    val status: String,
    val sizeBytes: Long,
    val addedAt: String?,
    val media: V2Media?,
    val coverUri: String?,
)

/** 最终删除预备信息（来自 prepare response，确认弹窗必须使用这份数字，而不是列表旧快照）。 */
data class DeleteCommitPrepare(
    val nonce: String,
    val expiresAt: String?,
    val count: Int,
    val totalBytes: Long,
    val mediaIds: List<String>,
)

/** 重复分组列表项。 */
data class DuplicateGroupSummary(
    val groupId: String,
    val type: String,
    val count: Int,
    val sizeBytes: Long,
    val detail: String,
)

/** 重复分组详情（对比页数据）。 */
data class DuplicateGroupDetail(
    val groupId: String,
    val type: String,
    val detail: String,
    val count: Int,
    val sizeBytes: Long,
    val members: List<DuplicateMemberUi>,
)

/** 对比页单个成员：媒体摘要 + 人工保留状态 + 封面绝对地址。 */
data class DuplicateMemberUi(
    val media: V2Media,
    val keep: Boolean,
    val coverUri: String,
)

/** 重复扫描状态（taskId 为 null = 还没有任何扫描任务）。 */
data class DuplicateScanState(
    val taskId: String?,
    val status: String?,
    val progress: Int,
    val error: String?,
) {
    /** 需要持续轮询的状态。 */
    val isActive: Boolean get() = status == "pending" || status == "running"

    val isSucceeded: Boolean get() = status == "succeeded"
}

/** 媒体库条目（本地编辑草稿的权威来源）。 */
data class LibrarySelectionItem(val jellyfinId: String, val name: String, val selected: Boolean)

/** 重复分组类型（V2 用户页面只展示 exact / similar；high / candidate 不进入用户界面）。 */
enum class DuplicateGroupType(val wire: String) {
    EXACT("exact"),
    SIMILAR("similar"),
}

/**
 * Demo 模式没有该能力（重复媒体 / 媒体库管理）时抛出。
 *
 * UI 捕获后显示「仅服务器模式可用」，而不是把缺失能力伪装成"0 项数据"或普通网络错误。
 */
class OrganizeFeatureUnavailableInDemoException(feature: String) :
    Exception("演示模式不支持$feature")