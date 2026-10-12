package com.mediareview.app.feature.v2.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.player.native.state.SeekDragEngine
import com.mediareview.app.feature.v2.player.native.ui.PlayerSeekBar
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 组件真实pointer回归；真实播放器position另由Stage8H播放测试验证，不能混称。 */
class PlayerSeekBarTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun physicalDragTwentyToSeventyEmitsOneSeekAfterRelease() {
        val engine = SeekDragEngine()
        val commits = mutableListOf<Long>()
        var pauses = 0
        var resumes = 0
        compose.setContent {
            Box(Modifier.width(300.dp)) {
                PlayerSeekBar(8_000L, 40_000L,
                    onDragStart = { if (engine.begin(8_000L, 40_000L, true)) pauses++ },
                    onPreview = engine::preview,
                    onCommit = { engine.commit()?.let { commits += it.targetMs; if (it.resumePlaying) resumes++ } },
                    onCancel = { if (engine.cancel()) resumes++ })
            }
        }
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * .2f, height * .5f))
            moveTo(Offset(width * .4f, height * .5f))
            moveTo(Offset(width * .7f, height * .5f))
        }
        compose.runOnIdle { assertEquals(1, pauses); assertTrue(commits.isEmpty()); assertTrue(engine.active) }
        compose.onNodeWithTag("player_seek_bar").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(28_000L), commits)
            assertEquals(1, resumes)
            assertFalse(engine.active)
        }
    }

    @Test fun cancellationRestoresPlayingWithoutCommittingAndThinReviewTrackHasFullHitArea() {
        var cancelled = 0
        var committed = 0
        compose.setContent {
            PlayerSeekBar(8_000L, 40_000L, onDragStart = {}, onPreview = {},
                onCommit = { committed++ }, onCancel = { cancelled++ }, trackHeight = 2.dp)
        }
        compose.onNodeWithTag("player_seek_bar").assertHeightIsEqualTo(32.dp)
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * .2f, 1f))
            moveTo(Offset(width * .7f, 1f))
            cancel()
        }
        compose.runOnIdle { assertEquals(0, committed); assertEquals(1, cancelled) }
    }
}
