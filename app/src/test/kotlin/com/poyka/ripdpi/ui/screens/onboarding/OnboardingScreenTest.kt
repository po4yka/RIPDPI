package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.OnboardingValidationRecoveryKind
import com.poyka.ripdpi.activities.OnboardingValidationState
import com.poyka.ripdpi.data.BuiltInDnsProviders
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                    onRunValidation = {},
                    onFinishKeepingRunning = {},
                    onFinishDisconnected = {},
                    onFinishAnyway = {},
                    onAcceptSuggestedMode = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.Onboarding)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).assertExists()
        composeRule.onNodeWithText("Set up later").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingContinue).assertExists()
    }

    @Test
    fun `set up later on intro completes without confirmation`() {
        var skipped = false
        renderValidationPage(
            uiState = OnboardingUiState(currentPage = 0),
            onSkip = { skipped = true },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).performClick()

        assertTrue(skipped)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkipConfirmDialog).assertCountEquals(0)
    }

    @Test
    fun `set up later on setup page requires confirmation`() {
        var skipped = false
        renderValidationPage(
            uiState = OnboardingUiState(currentPage = OnboardingInfoPageCount),
            onSkip = { skipped = true },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).performClick()

        assertFalse(skipped)
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkipConfirmDialog).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkipConfirmContinue).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkipConfirmSetUpLater).performClick()
        assertTrue(skipped)
    }

    @Test
    fun `dns setup uses canonical built in provider catalog`() {
        composeRule.setContent {
            RipDpiTheme {
                OnboardingDnsSelectionContent(
                    selectedProviderId = BuiltInDnsProviders.first().providerId,
                    onDnsSelected = {},
                )
            }
        }

        BuiltInDnsProviders.forEach { provider ->
            composeRule.onNodeWithTag(RipDpiTestTags.onboardingDnsProvider(provider.providerId)).assertExists()
            composeRule.onNodeWithText(provider.displayName).assertExists()
        }
    }

    @Test
    fun `final page renders validation action before validation`() {
        renderValidationPage(OnboardingUiState(currentPage = OnboardingPages.lastIndex))

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidateAction).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishKeepRunning).assertCountEquals(0)
    }

    @Test
    fun `failed validation renders retry finish anyway and suggested mode action`() {
        renderValidationPage(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState =
                    OnboardingValidationState.Failed(
                        reason = "VPN permission denied",
                        suggestedMode = Mode.Proxy,
                    ),
            ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidateAction).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSwitchSuggestedMode).assertExists()
    }

    @Test
    fun `successful validation renders explicit finish actions`() {
        renderValidationPage(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState = OnboardingValidationState.Success(latencyMs = 42, mode = Mode.VPN),
            ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishKeepRunning).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishDisconnected).assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertCountEquals(0)
    }

    @Test
    fun `validation running state hides finish actions`() {
        renderValidationPage(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState = OnboardingValidationState.RunningTrafficCheck(Mode.Proxy),
            ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidationStatus).assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingValidateAction).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertCountEquals(0)
    }

    @Test
    fun `validation busy state renders mode-specific copy and blocks skip`() {
        renderValidationPage(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                selectedMode = Mode.VPN,
                validationState = OnboardingValidationState.StartingMode(Mode.VPN),
            ),
        )

        composeRule.onNodeWithText("Validate VPN mode").assertExists()
        composeRule
            .onNodeWithText("This can take a moment. If Android asks for permission, allow it to continue.")
            .assertExists()
        composeRule.onNodeWithContentDescription("Step 4 of 4").assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkip).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingValidateAction).assertCountEquals(0)
    }

    @Test
    fun `notification permission failure renders allow recovery action`() {
        renderValidationPage(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState =
                    OnboardingValidationState.Failed(
                        reason = "Notifications required",
                        recoveryKind = OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS,
                    ),
            ),
        )

        composeRule.onNodeWithText("Allow").assertExists()
    }

    @Test
    fun `vpn permission failure renders grant recovery action`() {
        renderValidationPage(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState =
                    OnboardingValidationState.Failed(
                        reason = "VPN permission denied",
                        recoveryKind = OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION,
                    ),
            ),
        )

        composeRule.onNodeWithText("Grant VPN permission").assertExists()
    }

    private fun renderValidationPage(
        uiState: OnboardingUiState,
        onSkip: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = uiState,
                    onPageChanged = {},
                    onSkip = onSkip,
                    onContinue = {},
                    onModeSelected = {},
                    onDnsSelected = {},
                    onRunValidation = {},
                    onFinishKeepingRunning = {},
                    onFinishDisconnected = {},
                    onFinishAnyway = {},
                    onAcceptSuggestedMode = {},
                )
            }
        }
    }
}
