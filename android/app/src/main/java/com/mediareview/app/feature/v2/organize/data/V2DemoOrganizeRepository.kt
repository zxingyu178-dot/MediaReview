package com.mediareview.app.feature.v2.organize.data

import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.feature.v2.data.DemoMediaRepository
import com.mediareview.app.feature.v2.data.V2DataMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demo（APK 内置离线）整理中心仓库 —— Stage 8C §43。
 *
 * 行为边界：
 * - **绝不调用真实 Server API**；
 * - 待删除 = Demo 批阅的内存待删除集合（与 Review 里的 🗑 同一份状态，
 *   在 Demo 里标记的待删除会真实出现在整理页，见 `DemoMediaRepository.pendingDeleteIds`）；
 *   最终删除在内存中完成（一次性 nonce 快照语义与 Server 对齐，只是不碰磁盘）；
 * - 重复媒体 / 媒体库管理在 Demo 没有数据源：抛
 *   [OrganizeFeatureUnavailableInDemoException]，UI 显示「仅服务器模式可用」，
 *   绝不伪装成 0 项数据。
 */
@Singleton
class V2DemoOrganizeRepository @Inject constructor(
    private val demoMedia: DemoMediaRepository,
) : V2OrganizeRepository {

    override val mode: V2DataMode = V2DataMode.DEMO

    /** Demo 最终删除的一次性 nonce 快照（nonce → media_ids），使用即移除。 */
    private val commitSnapshots = mutableMapOf<String, List<String>>()
    private var nonceSeq = 0

    private suspend fun pendingMedia(): List<com.mediareview.app.feature.v2.model.V2Media> =
        demoMedia.pendingDeleteIds().sorted().mapNotNull { demoMedia.mediaById(it) }

    // ---------- Overview 摘要 ----------

    override suspend fun deleteSummary(): DeleteQueueSummary {
        val items = pendingMedia()
        return DeleteQueueSummary(
            count = items.size,
            totalBytes = items.sumOf { it.sizeBytes },
        )
    }

    override suspend fun duplicateSummary(): DuplicateSummary =
        throw OrganizeFeatureUnavailableInDemoException("重复媒体")

    override suspend fun reviewSummary(): ReviewProgressSummary? = null

    override suspend fun librarySummary(): LibrarySelectionSummary =
        throw OrganizeFeatureUnavailableInDemoException("媒体库管理")

    // ---------- 待删除队列（Demo 内存版） ----------

    override suspend fun loadDeleteQueue(): List<DeleteQueueEntry> =
        pendingMedia().map { media ->
            DeleteQueueEntry(
                mediaId = media.id,
                status = "pending",
                sizeBytes = media.sizeBytes,
                addedAt = null,
                media = media,
                coverUri = demoMedia.coverUri(media),
            )
        }

    override suspend fun restoreDeleteItem(mediaId: String) {
        demoMedia.setPendingDelete(mediaId, false)
    }

    override suspend fun prepareDeleteCommit(): DeleteCommitPrepare {
        val items = pendingMedia()
        val nonce = "demo-${++nonceSeq}-${System.currentTimeMillis()}"
        commitSnapshots[nonce] = items.map { it.id }
        return DeleteCommitPrepare(
            nonce = nonce,
            expiresAt = null,
            count = items.size,
            totalBytes = items.sumOf { it.sizeBytes },
            mediaIds = items.map { it.id },
        )
    }

    override suspend fun commitDelete(nonce: String): Map<String, DeleteOutcomeStatus> {
        // 一次性 nonce：只接受本仓库 prepare 签发的值，使用后立即失效
        val ids = commitSnapshots.remove(nonce)
            ?: throw IllegalStateException("演示模式:删除确认不存在或已使用")
        ids.forEach { demoMedia.setPendingDelete(it, false) }
        return ids.associateWith { DeleteOutcomeStatus.Success }
    }

    // ---------- 重复媒体（Demo 不可用） ----------

    override suspend fun loadDuplicateGroups(
        type: DuplicateGroupType,
        page: Int,
        pageSize: Int,
    ): DuplicateGroupPage = throw OrganizeFeatureUnavailableInDemoException("重复媒体")

    override suspend fun loadDuplicateDetail(groupId: String): DuplicateGroupDetail =
        throw OrganizeFeatureUnavailableInDemoException("重复媒体")

    override suspend fun startDuplicateScan(): DuplicateScanState =
        throw OrganizeFeatureUnavailableInDemoException("重复媒体")

    override suspend fun duplicateScanStatus(): DuplicateScanState =
        throw OrganizeFeatureUnavailableInDemoException("重复媒体")

    override suspend fun pauseDuplicateScan(taskId: String): DuplicateScanState =
        throw OrganizeFeatureUnavailableInDemoException("重复媒体")

    override suspend fun resumeDuplicateScan(taskId: String): DuplicateScanState =
        throw OrganizeFeatureUnavailableInDemoException("重复媒体")

    override suspend fun cancelDuplicateScan(taskId: String): DuplicateScanState =
        throw OrganizeFeatureUnavailableInDemoException("重复媒体")

    override suspend fun setDuplicateKeep(groupId: String, mediaId: String, keep: Boolean) {
        throw OrganizeFeatureUnavailableInDemoException("重复媒体")
    }

    // ---------- 媒体库管理（Demo 不可用） ----------

    override suspend fun loadLibraries(): List<LibrarySelectionItem> =
        throw OrganizeFeatureUnavailableInDemoException("媒体库管理")

    override suspend fun saveLibrarySelection(selectedIds: List<String>): List<LibrarySelectionItem> =
        throw OrganizeFeatureUnavailableInDemoException("媒体库管理")
}