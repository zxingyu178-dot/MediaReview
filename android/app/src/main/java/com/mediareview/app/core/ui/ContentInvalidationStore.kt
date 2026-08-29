package com.mediareview.app.core.ui

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import javax.inject.Inject
import javax.inject.Singleton

enum class ContentArea { Favorites, Libraries, DeleteQueue, Duplicates }

/** 只记录相关业务成功变更的版本；普通根切换不会改变版本。 */
@Singleton
class ContentInvalidationStore @Inject constructor() {
    private val revisions = AtomicLongArray(ContentArea.entries.size)

    fun revision(area: ContentArea): Long = revisions.get(area.ordinal)

    fun invalidate(area: ContentArea): Long = revisions.incrementAndGet(area.ordinal)
}

/** 同一版本只允许一次自动加载，业务版本递增后允许精确刷新。 */
class RevisionLoadGate {
    private val claimedRevision = AtomicLong(Long.MIN_VALUE)

    fun claim(revision: Long): Boolean {
        while (true) {
            val current = claimedRevision.get()
            if (current == revision) return false
            if (claimedRevision.compareAndSet(current, revision)) return true
        }
    }
}
