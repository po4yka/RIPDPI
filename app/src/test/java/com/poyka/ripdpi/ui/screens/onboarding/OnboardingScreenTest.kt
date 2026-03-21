package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `onboarding exposes stable selector contract`() {
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = OnboardingUiState(),
                    onPageChanged = {},
                    onSkip = {},
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.Onboarding)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingContinue).assertExists()
    }
}
