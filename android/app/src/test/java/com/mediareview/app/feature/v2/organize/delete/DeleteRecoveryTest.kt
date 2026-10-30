package com.mediareview.app.feature.v2.organize.delete

import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.feature.v2.organize.data.DeleteCommitResponse
import com.mediareview.app.feature.v2.organize.data.DiskDeletionReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeleteRecoveryTest {
    private val receipt = DeleteCommitResponse(
        mapOf("a" to DeleteOutcomeStatus.Success), DiskDeletionReceipt(1, 7, 0, 0),
    )

    @Test fun `响应丢失后查询原批次得到权威结果`() = runTest {
        val requests = mutableListOf<String>()
        val checks = mutableListOf<Int>()
        val result = recoverDeleteResult("original", onChecking = { checks += it }) { nonce ->
            requests += nonce
            if (requests.size == 1) DeleteResultLookup.Running else DeleteResultLookup.Completed(receipt)
        }
        assertEquals(DeleteResultLookup.Completed(receipt), result)
        assertEquals(listOf("original", "original"), requests)
        assertEquals(listOf(1, 2), checks)
    }

    @Test fun `持续离线有界退出不推断成功`() = runTest {
        var calls = 0
        val result = recoverDeleteResult("original") { calls++; throw java.io.IOException("offline") }
        assertEquals(4, calls)
        assertEquals(DeleteResultLookup.Unknown, result)
    }

    @Test fun `未知和旧服务均不重发删除或继续轮询`() = runTest {
        for (state in listOf(DeleteResultLookup.Unknown, DeleteResultLookup.Unsupported, DeleteResultLookup.NotSubmitted)) {
            var calls = 0
            assertEquals(state, recoverDeleteResult("original") { calls++; state })
            assertEquals(1, calls)
        }
    }

    @Test fun `页面离开取消查询不吞取消异常`() = runTest {
        try {
            recoverDeleteResult("original") { throw CancellationException("left page") }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
    }
}
