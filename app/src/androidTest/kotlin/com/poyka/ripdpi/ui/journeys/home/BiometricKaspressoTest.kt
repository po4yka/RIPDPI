package com.poyka.ripdpi.ui.journeys.home

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode
import org.junit.Assert.assertEquals
import org.junit.Test

private class BiometricPage(
    provider: SemanticsNodeInteractionsProvider,
) : ComposeScreen<BiometricPage>(provider, { hasTestTag(RipDpiTestTags.screen(Route.BiometricPrompt)) }) {
    fun tagged(tag: String): KNode = onNode { hasTestTag(tag) }

    val primary = tagged(RipDpiTestTags.BiometricPromptPrimaryAction)
    val secondary = tagged(RipDpiTestTags.BiometricPromptSecondaryAction)
    val pinField = tagged(RipDpiTestTags.BiometricPromptPinField)
}

@HiltAndroidTest
class BiometricKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(dataPreset = AutomationDataPreset.BiometricLockedWithPin),
    ) {
    @Test
    fun wrongPinKeepsTheLockAndCorrectPinUnlocksHome() =
        run {
            ComposeScreen.onComposeScreen<BiometricPage>(composeRule) {
                step("Locked start shows the prompt and the PIN stage") {
                    composeRule.waitForTag(RipDpiTestTags.screen(Route.BiometricPrompt))
                    primary.assertIsDisplayed()
                    if (composeRule.tagCount(RipDpiTestTags.BiometricPromptPinField) == 0) {
                        secondary.performClick()
                    }
                    composeRule.waitForTag(RipDpiTestTags.BiometricPromptPinField)
                }
                step("A wrong PIN does not unlock") {
                    pinField.performTextReplacement("1111")
                    primary.performClick()
                    composeRule.waitForIdle()
                    assertEquals(0, composeRule.tagCount(RipDpiTestTags.screen(Route.Home)))
                    pinField.assertIsDisplayed()
                }
                step("The correct PIN unlocks Home") {
                    pinField.performTextReplacement("0000")
                    primary.performClick()
                    composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
                }
            }
        }
}
