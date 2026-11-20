package com.mediareview.app.feature.v2.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaReviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReviewSaveBannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingConfirmationAndRecoveryStayDistinctAtLargeText() {
        val initial = ReviewVideoDecision("fixture-video", 0, ReviewVideoAction.QueueDelete, ReviewSavePhase.Saving)
        val decision = mutableStateOf(initial)
        val onOriginal = mutableStateOf(false)
        var recoveries = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MediaReviewTheme {
                    Box(Modifier.width(320.dp)) {
                        ReviewSaveBanner(decision.value, onOriginal.value, { recoveries++ }, {})
                    }
                }
            }
        }
        compose.onNodeWithText("正在保存", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已保存", substring = true).assertDoesNotExist()
        compose.runOnIdle { decision.value = initial.copy(phase = ReviewSavePhase.Failed) }
        compose.onNodeWithText("保存失败", substring = true).assertIsDisplayed()
        compose.onNodeWithText("回到原视频").assertIsDisplayed()
        compose.onNodeWithTag("review_save_recover").performClick()
        compose.runOnIdle {
            assertEquals(1, recoveries)
            onOriginal.value = true
        }
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.runOnIdle { decision.value = initial.copy(phase = ReviewSavePhase.Saved) }
        compose.onNodeWithText("已保存", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("review_save_recover").assertDoesNotExist()
    }
}
