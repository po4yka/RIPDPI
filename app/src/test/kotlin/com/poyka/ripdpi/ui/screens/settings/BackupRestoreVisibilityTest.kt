package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import com.poyka.ripdpi.backup.BackupRestoreUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class BackupRestoreVisibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Full default keeps reset action and its confirmation callback`() {
        var resets = 0
        composeRule.setContent {
            RipDpiTheme {
                BackupRestoreScreen(
                    state = BackupRestoreUiState(),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onExportClick = {},
                    onShareRedactedClick = {},
                    onImportClick = {},
                    onResetClick = { resets++ },
                )
            }
        }
        composeRule.onNode(hasScrollAction()).performScrollToKey("backup_reset_action")
        composeRule.onNodeWithTag(RipDpiTestTags.BackupResetButton).performScrollTo().performClick()
        assertEquals(1, resets)
    }

    @Test
    fun `Simple guidance and restore stay reachable without factory reset`() {
        var imports = 0
        composeRule.setContent {
            RipDpiTheme {
                BackupRestoreScreen(
                    state = BackupRestoreUiState(),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onExportClick = {},
                    onShareRedactedClick = {},
                    onImportClick = { imports++ },
                    onResetClick = { error("Reset must stay unavailable") },
                    showResetAction = false,
                    additionalGuidance = "FULL restores app settings; network memories remain.",
                )
            }
        }
        composeRule.onNodeWithText("FULL restores app settings; network memories remain.").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToKey("backup_import_action")
        composeRule.onNodeWithTag(RipDpiTestTags.BackupImportButton).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.BackupResetButton).assertDoesNotExist()
        assertEquals(1, imports)
    }
}
