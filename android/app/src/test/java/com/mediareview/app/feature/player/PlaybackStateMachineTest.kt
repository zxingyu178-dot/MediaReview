package com.mediareview.app.feature.player

import androidx.media3.common.PlaybackException
import com.mediareview.app.feature.player.PlaybackFailureReason.CONTAINER
import com.mediareview.app.feature.player.PlaybackFailureReason.DATASOURCE
import com.mediareview.app.feature.player.PlaybackFailureReason.DECODER
import com.mediareview.app.feature.player.PlaybackStage.DirectPlaying
import com.mediareview.app.feature.player.PlaybackStage.FallbackHls
import com.mediareview.app.feature.player.PlaybackStage.Idle
import com.mediareview.app.feature.player.PlaybackStage.Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task C Step 2: 播放状态机合同。
 *
 * Direct Play 失败 → 恰好一次 HLS 回退;HLS 再失败 → 中文终态错误;
 * 任何路径不得出现第二次回退(无回退循环);取消/切换媒体完全重置。
 */
class PlaybackStateMachineTest {

    @Test
    fun directStartSuccessEntersDirectPlaying() {
        val stage = PlaybackTransitions.reduce(Idle, PlaybackEvent.DirectStartSucceeded)
        assertEquals(DirectPlaying(attempt = 1), stage)
    }

    @Test
    fun directFailureFallsBackToHlsExactlyOnce() {
        val fallback = PlaybackTransitions.reduce(
            DirectPlaying(attempt = 1),
            PlaybackEvent.DirectStartFailed(DATASOURCE),
        )
        assertTrue(fallback is FallbackHls)
        assertEquals(1, (fallback as FallbackHls).attempt)
    }

    @Test
    fun hlsFailureGoesTerminalWithChineseMessage() {
        for (reason in listOf(DATASOURCE, CONTAINER, DECODER)) {
            val terminal = PlaybackTransitions.reduce(
                FallbackHls(attempt = 1),
                PlaybackEvent.HlsStartFailed(reason),
            )
            assertTrue(terminal is Terminal)
            val message = (terminal as Terminal).message
            assertTrue(message.contains("回退"))
            assertTrue(message.contains("无法播放"))
            assertEquals(reason, terminal.reason)
        }
    }

    @Test
    fun hlsSuccessStaysInFallbackStage() {
        val stage = PlaybackTransitions.reduce(
            FallbackHls(attempt = 1),
            PlaybackEvent.HlsStartSucceeded,
        )
        assertEquals(FallbackHls(attempt = 1), stage)
    }

    @Test
    fun noSecondFallbackAfterFallbackUsed() {
        // 已经回退过又收到 Direct 失败(重复/迟到的旧事件):必须终态,不得再次回退
        val terminal = PlaybackTransitions.reduce(
            FallbackHls(attempt = 1),
            PlaybackEvent.DirectStartFailed(DECODER),
        )
        assertTrue(terminal is Terminal)
        assertEquals(1, (terminal as Terminal).fallbackAttempt)
    }

    @Test
    fun lateDirectSuccessAfterFallbackIsIgnored() {
        // 迟到的 Direct 成功事件不得把状态拽回 DirectPlaying(旧任务事件)
        val stage = PlaybackTransitions.reduce(
            FallbackHls(attempt = 1),
            PlaybackEvent.DirectStartSucceeded,
        )
        assertEquals(FallbackHls(attempt = 1), stage)
    }

    @Test
    fun cancelledResetsToIdleAndNewSessionAllowsFallbackAgain() {
        val machine = PlaybackStateMachine()
        machine.dispatch(PlaybackEvent.DirectStartSucceeded)
        machine.dispatch(PlaybackEvent.DirectStartFailed(CONTAINER))
        assertEquals(FallbackHls(attempt = 1), machine.stage.value)

        machine.dispatch(PlaybackEvent.Cancelled)
        assertEquals(Idle, machine.stage.value)

        // 新会话(切换媒体):回退配额必须重新可用
        machine.dispatch(PlaybackEvent.DirectStartFailed(DATASOURCE))
        assertTrue(machine.stage.value is FallbackHls)
    }

    @Test
    fun retryFromTerminalStartsFreshSession() {
        val machine = PlaybackStateMachine()
        machine.dispatch(PlaybackEvent.DirectStartFailed(DATASOURCE))
        machine.dispatch(PlaybackEvent.HlsStartFailed(CONTAINER))
        assertTrue(machine.stage.value is Terminal)

        machine.dispatch(PlaybackEvent.RetryRequested)
        assertEquals(Idle, machine.stage.value)
        machine.dispatch(PlaybackEvent.DirectStartSucceeded)
        assertEquals(DirectPlaying(attempt = 1), machine.stage.value)
    }

    @Test
    fun failureReasonMapsMedia3ErrorCodes() {
        assertEquals(
            DATASOURCE,
            reasonForPlaybackError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )
        assertEquals(
            DATASOURCE,
            reasonForPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            CONTAINER,
            reasonForPlaybackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(
            DECODER,
            reasonForPlaybackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES),
        )
        assertEquals(
            DATASOURCE,
            reasonForPlaybackError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
    }

    @Test
    fun terminalIsNotPlayableState() {
        val terminal = PlaybackTransitions.reduce(
            FallbackHls(attempt = 1),
            PlaybackEvent.HlsStartFailed(DATASOURCE),
        ) as Terminal
        assertFalse(terminal.message.isBlank())
        assertTrue(terminal.message.isNotBlank() && terminal.message.first() != ' ')
    }
}
