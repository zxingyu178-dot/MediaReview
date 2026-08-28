package com.mediareview.app.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialLoadGateTest {
    @Test
    fun rootContentClaimsInitialLoadOnlyOnceAcrossRecomposition() {
        val gate = InitialLoadGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertFalse(gate.claim())
    }
}
