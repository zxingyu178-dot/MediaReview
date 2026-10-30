package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.*
import org.junit.Test

class SeekPositionLatchTest {
    @Test fun oldKernelPositionCannotReplaceCommittedPreview() {
        val latch = SeekPositionLatch()
        latch.commit(70_000)
        assertEquals(70_000L, latch.display(10_000))
        assertEquals(70_000L, latch.display(10_500))
        assertEquals(70_100L, latch.display(70_100))
        assertEquals(71_000L, latch.display(71_000))
    }
    @Test fun cancelledOrNewSourceDoesNotKeepOldTarget() {
        val latch = SeekPositionLatch()
        latch.commit(70_000)
        latch.clear()
        assertEquals(0L, latch.display(0))
    }
}
