package com.mediareview.app.feature.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectNavigationTest {
    @Test
    fun unpairedStateDoesNotContinue() {
        var calls = 0

        continueWhenPaired(paired = false) { calls += 1 }

        assertEquals(0, calls)
    }

    @Test
    fun pairedStateContinuesOnce() {
        var calls = 0

        continueWhenPaired(paired = true) { calls += 1 }

        assertEquals(1, calls)
    }
}
