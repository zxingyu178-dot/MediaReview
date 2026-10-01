package com.mediareview.app.feature.v2.organize

import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.organize.data.DeleteCommitPrepare
import com.mediareview.app.feature.v2.organize.data.DeleteQueueEntry
import com.mediareview.app.feature.v2.organize.data.DeleteQueueSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupDetail
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupPage
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateGroupType
import com.mediareview.app.feature.v2.organize.data.DuplicateMemberUi
import com.mediareview.app.feature.v2.organize.data.DuplicateScanState
import com.mediareview.app.feature.v2.organize.data.DuplicateSummary
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionItem
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionSummary
import com.mediareview.app.feature.v2.organize.data.ReviewProgressSummary
import com.mediareview.app.feature.v2.organize.data.V2OrganizeRepository

/** 构造测试媒体（仅测试使用；字段最小化）。 */
fun fakeMedia(id: String, name: String = "媒体 $id"): V2Media = V2Media(
    id = id,
    code = "",
    name = name,
    folderId = "f1",
    folderName = "旅行",
    type = V2MediaType.VIDEO,
    durationMs = 30_000,
    sizeBytes = 1_000,
    dateMillis = 0,
    isFavorite = false,
    isReviewed = false,
    assetPath = "",
    thumbPath = "",
    spritePath = null,
    spriteManifestPath = null,
    naturalWidth = 1920,
    naturalHeight = 1080,
)

/**
 * 可编程内存仓库（Stage 8C §54）：每个方法独立配置成功/失败与调用计数，
 * 用于验证 ViewModel 的"每卡独立失败 / 服务端确认制 / single-flight / 结果汇总"。
 */
class FakeOrganizeRepository : V2OrganizeRepository {

    override var mode: V2DataMode = V2DataMode.SERVER

    /**
     * 让"数据源切换前的旧请求"挂起（Stage 8C.1 §26 迟到请求测试）：
     * 仅**首次**汇总类调用等待该闸门，之后的调用立即返回。
     */
    var holdFirstSummaryCall: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var summaryHeld = false

    private suspend fun maybeHoldFirstSummaryCall() {
        if (!summaryHeld) {
            summaryHeld = true
            holdFirstSummaryCall?.await()
        }
    }

    // ---------- overview ----------
    var deleteSummaryResult: Result<DeleteQueueSummary> = Result.success(DeleteQueueSummary(0, 0L))
    var duplicateSummaryResult: Result<DuplicateSummary> =
        Result.success(DuplicateSummary(0, 0, null, null, 0))
    var reviewSummaryResult: Result<ReviewProgressSummary?> = Result.success(null)
    var librarySummaryResult: Result<LibrarySelectionSummary> =
        Result.success(LibrarySelectionSummary(0, 0))
    var deleteSummaryCalls = 0
    var duplicateSummaryCalls = 0
    var reviewSummaryCalls = 0
    var librarySummaryCalls = 0

    // ---------- delete queue ----------
    var deleteQueueResult: Result<List<DeleteQueueEntry>> = Result.success(emptyList())
    var restoreResult: Result<Unit> = Result.success(Unit)
    var prepareResult: Result<DeleteCommitPrepare> =
        Result.success(DeleteCommitPrepare("nonce-1", null, 0, 0L, emptyList()))
    var commitResult: Result<Map<String, DeleteOutcomeStatus>> = Result.success(emptyMap())

    /** 可选的提交挂起闸门（测试"提交进行中"状态：确认/取消交互）。 */
    var commitGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var loadDeleteQueueCalls = 0
    val restoreCalls = mutableListOf<String>()
    var prepareCalls = 0
    var commitCalls = 0
    val committedNonces = mutableListOf<String>()

    // ---------- duplicates ----------
    var exactGroups: List<DuplicateGroupSummary> = emptyList()
    var similarGroups: List<DuplicateGroupSummary> = emptyList()
    var loadGroupsCalls = 0
    val loadGroupsRequests = mutableListOf<Pair<DuplicateGroupType, Int>>()

    /** 非空时 loadDuplicateGroups 一律失败（用于"扫描成功但结果刷新失败"用例）。 */
    var loadGroupsFailure: Throwable? = null
    var detailResult: Result<DuplicateGroupDetail> =
        Result.success(DuplicateGroupDetail("g1", "exact", "", 0, 0, emptyList()))
    var keepResult: Result<Unit> = Result.success(Unit)
    val keepCalls = mutableListOf<Pair<String, String>>()
    var startScanResult: Result<DuplicateScanState> =
        Result.success(DuplicateScanState("t1", "pending", 0, null))
    var scanStatusResult: Result<DuplicateScanState> =
        Result.success(DuplicateScanState("t1", "running", 50, null))

    /** 轮询序列：每次 GET status 依次弹出；耗尽后回退到 [scanStatusResult]。 */
    val scanStatusSequence = ArrayDeque<DuplicateScanState>()
    var startScanCalls = 0
    var scanStatusCalls = 0
    var pauseCalls = 0
    var resumeCalls = 0
    var cancelCalls = 0
    var pauseResult: Result<DuplicateScanState> =
        Result.success(DuplicateScanState("t1", "paused", 50, null))
    var resumeResult: Result<DuplicateScanState> =
        Result.success(DuplicateScanState("t1", "pending", 50, null))
    var cancelResult: Result<DuplicateScanState> =
        Result.success(DuplicateScanState("t1", "cancelled", 50, null))

    // ---------- libraries ----------
    var librariesResult: Result<List<LibrarySelectionItem>> = Result.success(emptyList())
    var saveSelectionResult: Result<List<LibrarySelectionItem>> = Result.success(emptyList())
    var loadLibrariesCalls = 0
    var saveSelectionCalls = 0
    val savedSelections = mutableListOf<List<String>>()

    // ---------- V2OrganizeRepository ----------

    override suspend fun deleteSummary(): DeleteQueueSummary {
        deleteSummaryCalls++
        maybeHoldFirstSummaryCall()
        return deleteSummaryResult.getOrThrow()
    }

    override suspend fun duplicateSummary(): DuplicateSummary {
        duplicateSummaryCalls++
        maybeHoldFirstSummaryCall()
        return duplicateSummaryResult.getOrThrow()
    }

    override suspend fun reviewSummary(): ReviewProgressSummary? {
        reviewSummaryCalls++
        maybeHoldFirstSummaryCall()
        return reviewSummaryResult.getOrThrow()
    }

    override suspend fun librarySummary(): LibrarySelectionSummary {
        librarySummaryCalls++
        maybeHoldFirstSummaryCall()
        return librarySummaryResult.getOrThrow()
    }

    override suspend fun loadDeleteQueue(): List<DeleteQueueEntry> {
        loadDeleteQueueCalls++
        return deleteQueueResult.getOrThrow()
    }

    override suspend fun restoreDeleteItem(mediaId: String) {
        restoreCalls += mediaId
        restoreResult.getOrThrow()
    }

    override suspend fun prepareDeleteCommit(): DeleteCommitPrepare {
        prepareCalls++
        return prepareResult.getOrThrow()
    }

    override suspend fun commitDelete(nonce: String): Map<String, DeleteOutcomeStatus> {
        commitCalls++
        committedNonces += nonce
        commitGate?.await()
        return commitResult.getOrThrow()
    }

    override suspend fun loadDuplicateGroups(
        type: DuplicateGroupType,
        page: Int,
        pageSize: Int,
    ): DuplicateGroupPage {
        loadGroupsCalls++
        loadGroupsRequests += type to page
        loadGroupsFailure?.let { throw it }
        val source = if (type == DuplicateGroupType.EXACT) exactGroups else similarGroups
        val from = (page - 1) * pageSize
        return DuplicateGroupPage(
            items = source.drop(from).take(pageSize),
            total = source.size,
            page = page,
            pageSize = pageSize,
        )
    }

    override suspend fun loadDuplicateDetail(groupId: String): DuplicateGroupDetail =
        detailResult.getOrThrow()

    override suspend fun startDuplicateScan(): DuplicateScanState {
        startScanCalls++
        return startScanResult.getOrThrow()
    }

    override suspend fun duplicateScanStatus(): DuplicateScanState {
        scanStatusCalls++
        return scanStatusSequence.removeFirstOrNull() ?: scanStatusResult.getOrThrow()
    }

    override suspend fun pauseDuplicateScan(taskId: String): DuplicateScanState {
        pauseCalls++
        return pauseResult.getOrThrow()
    }

    override suspend fun resumeDuplicateScan(taskId: String): DuplicateScanState {
        resumeCalls++
        return resumeResult.getOrThrow()
    }

    override suspend fun cancelDuplicateScan(taskId: String): DuplicateScanState {
        cancelCalls++
        return cancelResult.getOrThrow()
    }

    override suspend fun setDuplicateKeep(groupId: String, mediaId: String, keep: Boolean) {
        keepCalls += groupId to mediaId
        keepResult.getOrThrow()
    }

    override suspend fun loadLibraries(): List<LibrarySelectionItem> {
        loadLibrariesCalls++
        return librariesResult.getOrThrow()
    }

    override suspend fun saveLibrarySelection(selectedIds: List<String>): List<LibrarySelectionItem> {
        saveSelectionCalls++
        savedSelections += selectedIds
        return saveSelectionResult.getOrThrow()
    }
}

/** 构造成员 UI 数据（对比页测试用）。 */
fun fakeMember(
    id: String,
    keep: Boolean = false,
    available: Boolean = true,
): DuplicateMemberUi = DuplicateMemberUi(
    media = fakeMedia(id),
    keep = keep,
    coverUri = "http://server/media/$id.jpg",
    available = available,
)