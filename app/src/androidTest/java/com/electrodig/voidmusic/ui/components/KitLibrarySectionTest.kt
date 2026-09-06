package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import com.electrodig.voidmusic.audio.KitSummary
import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.session.KitAction
import com.electrodig.voidmusic.session.KitLibraryUiState
import com.electrodig.voidmusic.session.KitOperation
import com.electrodig.voidmusic.ui.theme.VoidMusicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class KitLibrarySectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val builtIn = KitSummary("default", "默认套鼓", isBuiltIn = true)
    private val custom = KitSummary("custom", "我的音色", isBuiltIn = false)

    @Test
    fun activeCustomKitShowsManagementAndFivePadImportActions() {
        var selectedId: String? = null
        var importedPad: DrumPad? = null

        composeRule.setContent {
            VoidMusicTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    KitLibrarySection(
                        state = KitLibraryUiState(kits = listOf(builtIn, custom)),
                        activeKitId = custom.id,
                        onSelectKit = { selectedId = it },
                        onCopyActiveKit = {},
                        onRenameKit = {},
                        onDeleteKit = {},
                        onImportPad = { _, pad -> importedPad = pad }
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("我的音色，当前音色").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("重命名 我的音色").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("删除 我的音色").assertIsDisplayed()
        DrumPad.entries.forEach { pad ->
            composeRule.onNodeWithText("导入 ${pad.displayName} WAV")
                .performScrollTo()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithText("导入 Kick WAV").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("选择音色 默认套鼓")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(DrumPad.KICK, importedPad)
            assertEquals("default", selectedId)
        }
    }

    @Test
    fun builtInKitDoesNotExposePadReplacement() {
        composeRule.setContent {
            VoidMusicTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    KitLibrarySection(
                        state = KitLibraryUiState(kits = listOf(builtIn, custom)),
                        activeKitId = builtIn.id,
                        onSelectKit = {},
                        onCopyActiveKit = {},
                        onRenameKit = {},
                        onDeleteKit = {},
                        onImportPad = { _, _ -> }
                    )
                }
            }
        }

        composeRule.onAllNodesWithText("导入 Kick WAV").assertCountEquals(0)
        composeRule.onNodeWithText("复制当前音色").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun busyStateDisablesActionsAndShowsProgress() {
        var selectedId: String? = null
        composeRule.setContent {
            VoidMusicTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    KitLibrarySection(
                        state = KitLibraryUiState(
                            kits = listOf(builtIn, custom),
                            operation = KitOperation(KitAction.IMPORT, custom.id, DrumPad.SNARE)
                        ),
                        activeKitId = custom.id,
                        onSelectKit = { selectedId = it },
                        onCopyActiveKit = {},
                        onRenameKit = {},
                        onDeleteKit = {},
                        onImportPad = { _, _ -> }
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("音色操作处理中").assertIsDisplayed()
        composeRule.onNodeWithText("复制当前音色").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("选择音色 默认套鼓").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("重命名 我的音色").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("删除 我的音色").assertIsNotEnabled()
        composeRule.runOnIdle { assertNull(selectedId) }
    }
}
