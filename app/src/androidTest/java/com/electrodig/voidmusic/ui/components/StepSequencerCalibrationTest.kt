package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.electrodig.voidmusic.detection.grid.GridScanner
import com.electrodig.voidmusic.detection.grid.SequenceState
import com.electrodig.voidmusic.ui.theme.VoidMusicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StepSequencerCalibrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun uncalibratedStepOverlayExplainsWhyGridIsUnavailable() {
        composeRule.setContent {
            VoidMusicTheme {
                StepSequencerOverlay(
                    projection = null,
                    sequence = SequenceState(),
                    fingertip = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithContentDescription("步进网格未校准").assertIsDisplayed()
        composeRule.onNodeWithText("请先完成四点校准").assertIsDisplayed()
    }

    @Test
    fun defaultCalibrationShows64CellPreviewAndConfirmsWithoutDragging() {
        var confirmedCorners: List<GridScanner.GridPoint>? = null
        composeRule.setContent {
            VoidMusicTheme {
                CalibrationOverlay(
                    onConfirm = { corners ->
                        confirmedCorners = corners
                        true
                    },
                    onCancel = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithContentDescription("校准网格预览，共 64 格").assertIsDisplayed()
        composeRule.onNodeWithText("确认并保存").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(4, confirmedCorners?.size)
        }
    }

    @Test
    fun cancellingCalibrationDoesNotConfirm() {
        var confirmed = false
        var cancelled = false
        composeRule.setContent {
            VoidMusicTheme {
                CalibrationOverlay(
                    onConfirm = {
                        confirmed = true
                        true
                    },
                    onCancel = { cancelled = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle {
            assertTrue(cancelled)
            assertFalse(confirmed)
        }
    }
}
