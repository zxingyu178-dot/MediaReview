package com.mediareview.app.feature.connect.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun `本地凭据存在不能在未探测时冒充已配对`() {
        assertEquals(
            AuthenticationState.Unknown,
            restoredConnectionState(credentialPresent = true, credentialRejected = false).authentication,
        )
        assertEquals(
            AuthenticationState.Rejected,
            restoredConnectionState(credentialPresent = false, credentialRejected = true).authentication,
        )
    }
    @Test
    fun `连接状态分别表达服务 Jellyfin 同步和认证`() {
        val state = ConnectionState(
            mediaReview = OnlineState.Online,
            jellyfin = OnlineState.Unknown,
            sync = SyncState.Idle,
            authentication = AuthenticationState.Paired,
        )

        assertEquals(OnlineState.Online, state.mediaReview)
        assertEquals(OnlineState.Unknown, state.jellyfin)
        assertEquals(SyncState.Idle, state.sync)
        assertEquals(AuthenticationState.Paired, state.authentication)
    }

    @Test
    fun `配对状态失败保持认证未知且已认证时采用 Jellyfin 实际探测`() {
        assertEquals(
            AuthenticationState.Unknown,
            buildConnectionState(
                mediaReviewOnline = true,
                jellyfinOnline = null,
                pairingRequired = null,
                paired = false,
                syncState = SyncState.Unknown,
                authenticationRejected = false,
            ).authentication,
        )
        assertEquals(
            OnlineState.Online,
            buildConnectionState(
                mediaReviewOnline = true,
                jellyfinOnline = true,
                pairingRequired = true,
                paired = true,
                syncState = SyncState.Syncing,
                authenticationRejected = false,
            ).jellyfin,
        )
        assertEquals(
            AuthenticationState.Rejected,
            buildConnectionState(
                mediaReviewOnline = true,
                jellyfinOnline = null,
                pairingRequired = true,
                paired = true,
                syncState = SyncState.Failed,
                authenticationRejected = true,
            ).authentication,
        )
    }
}
