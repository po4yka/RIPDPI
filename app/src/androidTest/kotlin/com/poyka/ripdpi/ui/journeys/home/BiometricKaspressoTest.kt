package com.poyka.ripdpi.ui.journeys.home

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class BiometricPage(
    rule: JourneyRule,
) : JourneyPage<BiometricPage>(rule, RipDpiTestTags.screen(Route.BiometricPrompt)) {
    val primary = RipDpiTestTags.BiometricPromptPrimaryAction
    val secondary = RipDpiTestTags.BiometricPromptSecondaryAction
    val pinField = RipDpiTestTags.BiometricPromptPinField
}

@HiltAndroidTest
class BiometricKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(dataPreset = AutomationDataPreset.BiometricLockedWithPin),
    ) {
    @Test
    fun wrongPinKeepsTheLockAndCorrectPinUnlocksHome() =
        run {
            with(BiometricPage(composeRule)) {
                step("Locked start shows the prompt and the PIN stage") {
                    awaitScreen()
                    node(primary).assertIsDisplayed()
                    if (count(pinField) == 0) {
                        node(secondary).performClick()
                    }
                    waitFor(pinField)
                }
                step("A wrong PIN does not unlock") {
                    node(pinField).performTextReplacement("1111")
                    node(primary).performClick()
                    composeRule.waitForIdle()
                    assertEquals(0, count(RipDpiTestTags.screen(Route.Home)))
                    node(pinField).assertIsDisplayed()
                }
                step("The correct PIN unlocks Home") {
                    node(pinField).performTextReplacement("0000")
                    node(primary).performClick()
                    waitFor(RipDpiTestTags.screen(Route.Home))
                }
            }
        }
}
