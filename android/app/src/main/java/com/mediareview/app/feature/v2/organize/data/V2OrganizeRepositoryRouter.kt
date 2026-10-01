package com.mediareview.app.feature.v2.organize.data

import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V2 整理中心数据源路由（Stage 8C）。
 *
 * UI / ViewModel 永远只依赖 [V2OrganizeRepository]，由本路由器按 [V2DataModeStore]
 * 的运行时模式把调用委托给 Demo 或 Server 实现 —— 与媒体层 [V2MediaRepositoryRouter]
 * 完全同构，切换数据源不需要任何 if/else。
 */
@Singleton
class V2OrganizeRepositoryRouter @Inject constructor(
    private val demo: V2DemoOrganizeRepository,
    private val server: V2ServerOrganizeRepository,
    private val modeStore: V2DataModeStore,
) : V2OrganizeRepository {

    override val mode: V2DataMode get() = modeStore.mode.value

    private fun active(): V2OrganizeRepository =
        if (modeStore.mode.value == V2DataMode.SERVER) server else demo

    override suspend fun deleteSummary(): DeleteQueueSummary = active().deleteSummary()

    override suspend fun duplicateSummary(): DuplicateSummary = active().duplicateSummary()

    override suspend fun reviewSummary(): ReviewProgressSummary? = active().reviewSummary()

    override suspend fun librarySummary(): LibrarySelectionSummary = active().librarySummary()

    override suspend fun loadDeleteQueue(): List<DeleteQueueEntry> = active().loadDeleteQueue()

    override suspend fun restoreDeleteItem(mediaId: String) = active().restoreDeleteItem(mediaId)

    override suspend fun prepareDeleteCommit(): DeleteCommitPrepare = active().prepareDeleteCommit()

    override suspend fun commitDelete(nonce: String): Map<String, DeleteOutcomeStatus> =
        active().commitDelete(nonce)

    override suspend fun loadDuplicateGroups(
        type: DuplicateGroupType,
        page: Int,
        pageSize: Int,
    ): DuplicateGroupPage = active().loadDuplicateGroups(type, page, pageSize)

    override suspend fun loadDuplicateDetail(groupId: String): DuplicateGroupDetail =
        active().loadDuplicateDetail(groupId)

    override suspend fun startDuplicateScan(): DuplicateScanState = active().startDuplicateScan()

    override suspend fun duplicateScanStatus(): DuplicateScanState = active().duplicateScanStatus()

    override suspend fun pauseDuplicateScan(taskId: String): DuplicateScanState =
        active().pauseDuplicateScan(taskId)

    override suspend fun resumeDuplicateScan(taskId: String): DuplicateScanState =
        active().resumeDuplicateScan(taskId)

    override suspend fun cancelDuplicateScan(taskId: String): DuplicateScanState =
        active().cancelDuplicateScan(taskId)

    override suspend fun setDuplicateKeep(groupId: String, mediaId: String, keep: Boolean) =
        active().setDuplicateKeep(groupId, mediaId, keep)

    override suspend fun loadLibraries(): List<LibrarySelectionItem> = active().loadLibraries()

    override suspend fun saveLibrarySelection(selectedIds: List<String>): List<LibrarySelectionItem> =
        active().saveLibrarySelection(selectedIds)
}