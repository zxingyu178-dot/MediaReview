package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.data.server.V2ServerStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRecoveryTest {
    @Test fun wakeAfterExhaustionCanRecoverOnTheNewNetwork() = runTest {
        val status = MutableStateFlow(V2ServerStatus.Offline)
        var available = false; var probes = 0; var recovered = 0
        val recovery = ServerRecovery(backgroundScope, status, { true }, {
            probes++; if (available) status.value = V2ServerStatus.Online
        }, { recovered++ })
        recovery.start(); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(3, probes)
        available = true; recovery.retry(); runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(4, probes); assertEquals(1, recovered)
    }
    @Test fun transientFailureRecoversByProbeWithoutRepeatedReloads() = runTest {
        val status = MutableStateFlow(V2ServerStatus.Online)
        var probes = 0; var recovered = 0
        val recovery = ServerRecovery(backgroundScope, status, { true }, {
            probes++; status.value = V2ServerStatus.Probing
            status.value = if (probes == 1) V2ServerStatus.Offline else V2ServerStatus.Online
        }, { recovered++ })
        recovery.start(); runCurrent()
        status.value = V2ServerStatus.Offline; runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertEquals(2, probes); assertEquals(1, recovered)
    }
    @Test fun authFailureAndSourceSwitchNeverAutoReconnect() = runTest {
        val status = MutableStateFlow(V2ServerStatus.AuthRejected)
        var enabled = true; var probes = 0
        ServerRecovery(backgroundScope, status, { enabled }, { probes++ }, {}).start(); runCurrent()
        advanceTimeBy(30_000); runCurrent(); assertEquals(0, probes)
        status.value = V2ServerStatus.Offline; runCurrent(); enabled = false
        advanceTimeBy(30_000); runCurrent(); assertEquals(0, probes)
    }
    @Test fun permanentFailureHasOnlyThreeAttempts() = runTest {
        val status = MutableStateFlow(V2ServerStatus.Offline)
        var probes = 0
        ServerRecovery(backgroundScope, status, { true }, { probes++ }, {}).start(); runCurrent()
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, probes)
    }
}
