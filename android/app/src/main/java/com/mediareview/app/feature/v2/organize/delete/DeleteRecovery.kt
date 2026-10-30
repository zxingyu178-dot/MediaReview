package com.mediareview.app.feature.v2.organize.delete

import com.mediareview.app.feature.v2.organize.data.DeleteCommitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

sealed interface DeleteResultLookup {
    data class Completed(val response: DeleteCommitResponse) : DeleteResultLookup
    data object Running : DeleteResultLookup
    data object Unknown : DeleteResultLookup
    data object Unsupported : DeleteResultLookup
    data object NotSubmitted : DeleteResultLookup
}

/** 仅查询同一个批次；绝不prepare/commit。轮询结束保留待核对记录。 */
suspend fun recoverDeleteResult(
    nonce: String,
    onChecking: (Int) -> Unit = {},
    query: suspend (String) -> DeleteResultLookup,
): DeleteResultLookup {
    for ((index, wait) in listOf(0L, 2_000L, 5_000L, 10_000L).withIndex()) {
        delay(wait)
        onChecking(index + 1)
        val result = try {
            query(nonce)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (index == 3) return DeleteResultLookup.Unknown
            continue
        }
        if (result != DeleteResultLookup.Running) return result
    }
    return DeleteResultLookup.Unknown
}
