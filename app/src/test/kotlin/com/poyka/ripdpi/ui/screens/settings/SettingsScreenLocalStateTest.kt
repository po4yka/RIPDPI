package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.ui.state.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenLocalStateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `pin required prompt leaves the backup pin editor reachable`() {
        lateinit var state: SettingsScreenLocalState
        composeRule.setContent { state = rememberSettingsScreenLocalState(SettingsUiState()) }
        val hiddenBeforeLockRequest = composeRule.runOnIdle { state.showBackupPinEditor }

        composeRule.runOnIdle { state.onShowPinRequiredDialogChanged(true) }
        composeRule.runOnIdle { state.onShowPinRequiredDialogChanged(false) }

        assertEquals(false to true, hiddenBeforeLockRequest to composeRule.runOnIdle { state.showBackupPinEditor })
    }
}
