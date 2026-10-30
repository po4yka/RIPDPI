package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.tagExists
import com.poyka.ripdpi.ui.journeys.waitForTag
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Test

@HiltAndroidTest
class SettingsBackupPinWarningKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(dataPreset = AutomationDataPreset.BiometricLockedWithPin),
    ) {
    @Test
    fun clearingTheBackupPinWhileBiometricLockIsOnShowsTheWarning() =
        run {
            step("Unlock with the backup PIN") {
                composeRule.waitForTag(RipDpiTestTags.screen(Route.BiometricPrompt))
                if (!composeRule.tagExists(RipDpiTestTags.BiometricPromptPinField)) {
                    composeRule.onNode(hasTestTag(RipDpiTestTags.BiometricPromptSecondaryAction)).performClick()
                }
                composeRule.waitForTag(RipDpiTestTags.BiometricPromptPinField)
                composeRule.onNode(hasTestTag(RipDpiTestTags.BiometricPromptPinField)).performTextReplacement("0000")
                // The soft keyboard opens over Unlock at an unpredictable moment, so submit through the action.
                composeRule
                    .onNode(hasTestTag(RipDpiTestTags.BiometricPromptPrimaryAction))
                    .performSemanticsAction(SemanticsActions.OnClick)
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
            }
            with(SettingsPage(composeRule)) {
                step("Open settings: a saved PIN means no warning") {
                    click(bottomNavSettings)
                    awaitScreen()
                    require(backupPinClear)
                    assertFalse(exists(backupPinWarning))
                }
                step("Clearing the PIN while biometric lock stays on shows the warning") {
                    click(backupPinClear)
                    click(backupPinClearConfirm)
                    require(backupPinWarning)
                    awaitDisplayed(backupPinWarning)
                }
            }
        }
}

@HiltAndroidTest
class SettingsLogsNavigationKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Settings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Test
    fun logsEntryOpensLogsAndBackReturnsToSettings() =
        run {
            step("Open logs from settings") {
                with(SettingsPage(composeRule)) { click(logs) }
                LogsEntryPage(composeRule).awaitScreen()
            }
            step("Back returns to settings") {
                Espresso.pressBack()
                SettingsPage(composeRule).awaitScreen()
            }
        }
}
