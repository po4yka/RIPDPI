package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
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
                    onModeSelected = {},
                    onDnsSelected = {},
                    onRunTest = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.Onboarding)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingContinue).assertExists()
    }
}
