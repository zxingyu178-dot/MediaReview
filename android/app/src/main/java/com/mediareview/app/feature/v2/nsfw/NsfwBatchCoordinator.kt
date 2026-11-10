package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 实现必须按来源/安装身份隔离，且不能覆盖已有未确认批次。 */
interface NsfwBatchJournal {
    suspend fun load(origin: String): NsfwFrozenBatch?
    suspend fun save(origin: String, batch: NsfwFrozenBatch)
    suspend fun clear(origin: String, requestId: String)
}

/** 保存先于网络，恢复只查原回执；只有已验证结果可被确认清除。 */
class NsfwBatchCoordinator(
    private val source: NsfwReviewDataSource,
    private val journal: NsfwBatchJournal,
    private val origin: String,
) {
    private val gate = Mutex()
    private var verifiedRequestId: String? = null

    suspend fun pending(): NsfwFrozenBatch? = gate.withLock { journal.load(origin) }

    suspend fun submit(batch: NsfwFrozenBatch): NsfwBatchReviewResult = gate.withLock {
        require(origin.isNotBlank() && batch.requestId.isNotBlank() && batch.items.size in 1..10)
        check(journal.load(origin) == null) { "有待核对批次，请先查询原结果" }
        val frozen = batch.copy(items = batch.items.toList())
        journal.save(origin, frozen)
        val result = try { source.batchReview(frozen.requestId, frozen.items) }
        catch (error: NsfwApiException) {
            // 只有整页版本冲突明确保证事务未写入；网络/未知/幂等冲突继续保留原批次。
            if (error.httpStatus == 409 && error.code == "REVIEW_CONFLICT") journal.clear(origin, frozen.requestId)
            throw error
        }
        validate(frozen, result)
        verifiedRequestId = frozen.requestId
        result
    }

    suspend fun recover(): NsfwBatchReviewResult? = gate.withLock {
        val frozen = journal.load(origin) ?: return@withLock null
        val result = source.batchReviewReceipt(frozen.requestId)
        validate(frozen, result)
        verifiedRequestId = frozen.requestId
        result
    }

    suspend fun acknowledge(requestId: String) = gate.withLock {
        check(verifiedRequestId == requestId) { "批次结果尚未确认，不能清除核对记录" }
        journal.clear(origin, requestId)
        verifiedRequestId = null
    }

    private fun validate(batch: NsfwFrozenBatch, result: NsfwBatchReviewResult) {
        val expected = batch.items.associateBy { it.imageId }
        val valid = result.items.size == batch.items.size && expected.size == batch.items.size &&
            result.items.map { it.imageId }.toSet() == expected.keys &&
            result.items.map { it.imageId }.distinct().size == result.items.size &&
            result.items.all { item ->
                val original = expected[item.imageId]
                original != null && item.status == original.action &&
                    item.revision.matches(Regex("[0-9]+")) && original.expectedRevision.matches(Regex("[0-9]+")) &&
                    item.revision.toBigInteger() > original.expectedRevision.toBigInteger()
            } && result.keptCount == result.items.count { it.status == "KEEP" } &&
            result.rejectedCount == result.items.count { it.status == "REJECT" }
        if (!valid) throw NsfwApiException(NsfwErrorCodes.RESPONSE_MISMATCH, "批次回执与原请求不一致，请继续核对，勿重复提交")
    }
}
