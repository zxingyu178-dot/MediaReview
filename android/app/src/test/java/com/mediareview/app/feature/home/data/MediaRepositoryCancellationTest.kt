package com.mediareview.app.feature.home.data

import java.util.concurrent.CancellationException
import org.junit.Assert.fail
import org.junit.Test

class MediaRepositoryCancellationTest {
    @Test
    fun suspendFailureBoundaryRethrowsCancellation() {
        try {
            runSuspendCatching<Unit> { throw CancellationException("cancelled") }
            fail("CancellationException must escape")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
