package com.mediareview.app.feature.v2.organize.data

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.model.CommitRequest
import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.model.DuplicateKeepRequest
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.TaskStateDto
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaReviewApi
import com.mediareview.app.core.network.SelectionRequest
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2ServerProfilePort
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * Production（真实 Server）整理中心仓库 —— Stage 8C。
 *
 * 使用的服务端合同：
 * ```
 * GET  /api/v1/delete-queue/summary       待删除摘要（pending 的 count/total_bytes）
 * GET  /api/v1/delete-queue               队列列表（媒体摘要带 cover_url，客户端零 N+1）
 * DELETE /api/v1/delete-queue/{media_id}  撤销单项
 * POST /api/v1/delete-queue/commit/prepare 生成一次性删除 nonce（快照）
 * POST /api/v1/delete-queue/commit        用 nonce 提交真实删除（不重新 prepare）
 * GET  /api/v1/duplicates/summary         重复计数 + 扫描任务状态
 * GET  /api/v1/duplicates/exact|similar   持久化分组列表
 * GET  /api/v1/duplicates/{group_id}      分组详情（成员媒体摘要，一次批量 SQL）
 * POST /api/v1/duplicates/scan            触发扫描（幂等）
 * GET  /api/v1/duplicates/status          扫描状态
 * POST /api/v1/tasks/{task_id}/pause|resume|cancel  扫描生命周期
 * POST /api/v1/duplicates/{group_id}/keep 人工保留选择
 * GET  /api/v1/libraries                  媒体库列表（含勾选）
 * PUT  /api/v1/libraries/selection        保存勾选
 * GET  /api/v1/review/sessions/latest     active 批阅会话（404 = 没有进行中的批阅）
 * ```
 *
 * 失败语义（§4/§6）：任何方法失败都**上抛**，绝不 catch→空/0；调用方据此区分
 * 「网络失败」与「真的没有数据」。404 只在明确语义处（latest 无 active）被解释。
 */
@Singleton
class V2ServerOrganizeRepository internal constructor(
    private val profilePort: V2ServerProfilePort,
    private val apiFactory: ApiFactory,
    private val mapper: V2MediaMapper,
    private val resources: V2ServerResourceCache,
    private val statusStore: V2ServerStatusStore,
) : V2OrganizeRepository {

    @Inject
    constructor(
        profileStore: ServerProfileStore,
        apiFactory: ApiFactory,
        mapper: V2MediaMapper,
        resources: V2ServerResourceCache,
        statusStore: V2ServerStatusStore,
    ) : this(
        profilePort = object : V2ServerProfilePort {
            override suspend fun baseUrl(): String = profileStore.current().baseUrl
        },
        apiFactory = apiFactory,
        mapper = mapper,
        resources = resources,
        statusStore = statusStore,
    )

    override val mode: V2DataMode = V2DataMode.SERVER

    // ---------- 访问层（与其余 V2 Server 仓库完全同构） ----------

    private suspend fun apiWithBase(): Pair<MediaReviewApi, String> {
        val baseUrl = profilePort.baseUrl()
        require(baseUrl.isNotBlank()) { "尚未连接服务器" }
        return apiFactory.create(baseUrl) to baseUrl
    }

    private suspend fun <T> call(block: suspend (MediaReviewApi, String) -> T): T {
        return try {
            val (api, base) = apiWithBase()
            val result = block(api, base)
            statusStore.onRequestSuccess()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            statusStore.onRequestFailure(error)
            throw error
        }
    }

    private fun <T> unwrap(resp: Envelope<T>): T {
        if (!resp.success) {
            throw IllegalStateException(resp.error?.message ?: "服务器返回错误")
        }
        return resp.data ?: throw IllegalStateException("服务器返回空数据")
    }

    // ---------- Overview 摘要 ----------

    override suspend fun deleteSummary(): DeleteQueueSummary = call { api, _ ->
        val dto = unwrap(api.deleteQueueSummary())
        DeleteQueueSummary(count = dto.count, totalBytes = dto.total_bytes)
    }

    override suspend fun duplicateSummary(): DuplicateSummary = call { api, _ ->
        val dto = unwrap(api.duplicatesSummary())
        DuplicateSummary(
            exactGroups = dto.exact_groups,
            similarGroups = dto.similar_groups,
            scanTaskId = dto.scan_task_id,
            scanStatus = dto.scan_status,
            scanProgress = dto.scan_progress,
        )
    }

    /**
     * active 批阅会话进度（`GET /review/sessions/latest`）。
     *
     * **明确 404 = 没有进行中的批阅**（返回 null，`Ready(null)` 是合法状态）；
     * 其他错误（网络 / 500 等）一律上抛 → 卡片进入 Error，绝不显示成"暂无批阅"。
     */
    override suspend fun reviewSummary(): ReviewProgressSummary? {
        val dto = try {
            call { api, _ -> unwrap(api.latestReviewSession()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notFound: HttpException) {
            if (notFound.code() == HTTP_NOT_FOUND) return null else throw notFound
        }
        return ReviewProgressSummary(
            seen = dto.seen_count,
            total = dto.total_count,
            // 旧 Server 不返回 remaining_count（默认 -1）：用 total-seen 兜底；新 Server 以权威值为准
            remaining = if (dto.remaining_count >= 0) {
                dto.remaining_count
            } else {
                (dto.total_count - dto.seen_count).coerceAtLeast(0)
            },
        )
    }

    override suspend fun librarySummary(): LibrarySelectionSummary = call { api, _ ->
        val libraries = unwrap(api.libraries())
        LibrarySelectionSummary(
            selected = libraries.count { it.selected },
            total = libraries.size,
        )
    }

    // ---------- 待删除队列 ----------

    override suspend fun loadDeleteQueue(): List<DeleteQueueEntry> = call { api, base ->
        val rows = unwrap(api.listDeleteQueue())
        rows.map { row ->
            val mapped = row.media?.let { mapper.mapMedia(it, base) }
            if (mapped != null) resources.put(mapped)
            DeleteQueueEntry(
                mediaId = row.media_id,
                status = row.status,
                sizeBytes = row.size_bytes ?: row.media?.size_bytes ?: 0L,
                addedAt = row.added_at,
                media = mapped?.media,
                coverUri = mapped?.coverUrl,
            )
        }
    }

    override suspend fun restoreDeleteItem(mediaId: String) {
        call { api, _ -> unwrap(api.dequeueDelete(mediaId)) }
    }

    override suspend fun prepareDeleteCommit(): DeleteCommitPrepare = call { api, _ ->
        val dto = unwrap(api.prepareDeleteCommit())
        DeleteCommitPrepare(
            nonce = dto.nonce,
            expiresAt = dto.expires_at,
            count = dto.count,
            totalBytes = dto.total_bytes,
            mediaIds = dto.media_ids,
        )
    }

    override suspend fun commitDelete(nonce: String): Map<String, DeleteOutcomeStatus> =
        call { api, _ ->
            val dto = unwrap(api.commitDeleteQueue(CommitRequest(nonce)))
            dto.outcome.mapValues { (_, value) -> DeleteOutcomeStatus.fromWire(value) }
        }

    // ---------- 重复媒体 ----------

    override suspend fun loadDuplicateGroups(type: DuplicateGroupType): List<DuplicateGroupSummary> =
        call { api, _ ->
            val dtos = when (type) {
                DuplicateGroupType.EXACT -> unwrap(api.duplicatesExact())
                DuplicateGroupType.SIMILAR -> unwrap(api.duplicatesSimilar())
            }
            dtos.map { it.toSummary() }
        }

    override suspend fun loadDuplicateDetail(groupId: String): DuplicateGroupDetail =
        call { api, base ->
            val dto = unwrap(api.duplicateDetail(groupId))
            val members = dto.members.map { member ->
                // 一次请求内的成员媒体摘要：直接构造 MediaSummary 走统一映射（禁止 N+1）
                val summary = MediaSummary(
                    media_id = member.media_id,
                    name = member.name,
                    media_type = member.media_type ?: "video",
                    size_bytes = member.size_bytes,
                    duration_ms = member.duration_ms,
                    width = member.width,
                    height = member.height,
                    cover_url = member.cover_url,
                )
                val mapped = mapper.mapMedia(summary, base)
                resources.put(mapped)
                DuplicateMemberUi(
                    media = mapped.media,
                    keep = member.keep,
                    coverUri = mapped.coverUrl.orEmpty(),
                )
            }
            DuplicateGroupDetail(
                groupId = dto.group_id,
                type = dto.type,
                detail = dto.detail,
                count = dto.count,
                sizeBytes = dto.size_bytes,
                members = members,
            )
        }

    override suspend fun startDuplicateScan(): DuplicateScanState = call { api, _ ->
        val task = unwrap(api.scanDuplicates())
        DuplicateScanState(
            taskId = task.task_id.ifBlank { null },
            status = task.status,
            progress = task.progress,
            error = task.error,
        )
    }

    override suspend fun duplicateScanStatus(): DuplicateScanState = call { api, _ ->
        val dto = unwrap(api.duplicatesStatus())
        DuplicateScanState(
            taskId = dto.task_id?.ifBlank { null },
            status = dto.status,
            progress = dto.progress ?: 0,
            error = dto.error,
        )
    }

    override suspend fun pauseDuplicateScan(taskId: String): DuplicateScanState = call { api, _ ->
        unwrap(api.pauseTask(taskId)).toScanState()
    }

    override suspend fun resumeDuplicateScan(taskId: String): DuplicateScanState = call { api, _ ->
        unwrap(api.resumeTask(taskId)).toScanState()
    }

    override suspend fun cancelDuplicateScan(taskId: String): DuplicateScanState = call { api, _ ->
        unwrap(api.cancelTask(taskId)).toScanState()
    }

    override suspend fun setDuplicateKeep(groupId: String, mediaId: String, keep: Boolean) {
        call { api, _ ->
            unwrap(api.setDuplicateKeep(groupId, DuplicateKeepRequest(media_id = mediaId, keep = keep)))
        }
    }

    // ---------- 媒体库管理 ----------

    override suspend fun loadLibraries(): List<LibrarySelectionItem> = call { api, _ ->
        unwrap(api.libraries()).map {
            LibrarySelectionItem(
                jellyfinId = it.jellyfin_id,
                name = it.name,
                selected = it.selected,
            )
        }
    }

    override suspend fun saveLibrarySelection(selectedIds: List<String>): List<LibrarySelectionItem> =
        call { api, _ ->
            unwrap(api.saveLibrariesSelection(SelectionRequest(selected = selectedIds))).map {
                LibrarySelectionItem(
                    jellyfinId = it.jellyfin_id,
                    name = it.name,
                    selected = it.selected,
                )
            }
        }

    // ---------- 映射 ----------

    private fun DuplicateGroupDto.toSummary(): DuplicateGroupSummary = DuplicateGroupSummary(
        groupId = group_id,
        type = type,
        count = count,
        sizeBytes = size_bytes,
        detail = detail,
    )

    private fun TaskStateDto.toScanState(): DuplicateScanState =
        DuplicateScanState(
            taskId = task_id.ifBlank { null },
            status = status,
            progress = progress,
            error = error,
        )

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}