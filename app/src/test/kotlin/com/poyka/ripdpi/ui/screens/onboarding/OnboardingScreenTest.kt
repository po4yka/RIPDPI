package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.OnboardingValidationRecoveryKind
import com.poyka.ripdpi.activities.OnboardingValidationState
import com.poyka.ripdpi.activities.OnboardingValidationStep
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCloudflareIp
import com.poyka.ripdpi.data.DnsProviderDnsSb
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.DnsProviderGoogleIp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
        renderOnboarding(OnboardingUiState(currentPage = 0))

        composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.Onboarding)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).assertExists()
        composeRule.onNodeWithText("Skip setup").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingContinue).assertExists()
    }

    @Test
    fun `skip on intro completes immediately without confirmation`() {
        var skipped = false
        renderOnboarding(
            uiState = OnboardingUiState(currentPage = 0),
            onSkip = { skipped = true },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).performClick()

        assertTrue(skipped)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkipConfirmDialog).assertCountEquals(0)
    }

    @Test
    fun `setup pages do not show a top skip action`() {
        renderOnboarding(uiState = OnboardingUiState(currentPage = OnboardingInfoPageCount))

        // The mode step relies on safe defaults + the bottom CTA; no top "Use recommended"/skip.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkip).assertCountEquals(0)
        composeRule.onAllNodesWithText("Use recommended").assertCountEquals(0)
    }

    @Test
    fun `dns setup presents the curated onboarding subset and advanced affordance`() {
        composeRule.setContent {
            RipDpiTheme {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    OnboardingDnsSelectionContent(
                        selectedProviderId = OnboardingDnsSystemId,
                        onDnsSelected = {},
                        onOpenAdvancedDns = {},
                    )
                }
            }
        }

        // Exactly the curated five ids are shown, in order.
        listOf(
            OnboardingDnsSystemId,
            com.poyka.ripdpi.data.DnsProviderAdGuard,
            DnsProviderCloudflare,
            com.poyka.ripdpi.data.DnsProviderQuad9,
            com.poyka.ripdpi.data.DnsProviderMullvad,
        ).forEach { id ->
            composeRule.onNodeWithTag(RipDpiTestTags.onboardingDnsProvider(id)).assertExists()
        }

        // Removed catalog duplicates/extras must NOT leak into onboarding.
        listOf(
            DnsProviderGoogle,
            DnsProviderGoogleIp,
            DnsProviderCloudflareIp,
            DnsProviderDnsSb,
        ).forEach { id ->
            composeRule.onAllNodesWithTag(RipDpiTestTags.onboardingDnsProvider(id)).assertCountEquals(0)
        }

        // Curated display names render; Cloudflare uses the DoH-host variant, not the bare-IP one.
        composeRule.onNodeWithText("System default").assertExists()
        composeRule.onNodeWithText("AdGuard DNS").assertExists()
        composeRule.onNodeWithText("Cloudflare").assertExists()
        composeRule.onNodeWithText("Quad9").assertExists()
        composeRule.onNodeWithText("Mullvad DNS").assertExists()
        composeRule.onAllNodesWithText("Cloudflare (IP)").assertCountEquals(0)

        // Advanced DNS settings affordance is present.
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingAdvancedDns).assertExists()
        composeRule.onNodeWithText("Advanced DNS settings").assertExists()
    }

    @Test
    fun `advanced dns affordance opens advanced settings`() {
        var opened = false
        composeRule.setContent {
            RipDpiTheme {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    OnboardingDnsSelectionContent(
                        selectedProviderId = OnboardingDnsSystemId,
                        onDnsSelected = {},
                        onOpenAdvancedDns = { opened = true },
                    )
                }
            }
        }

        // The affordance sits below the curated cards inside a vertical scroll container.
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingAdvancedDns).performScrollTo().performClick()

        assertTrue(opened)
    }

    @Test
    fun `idle connection test exposes start cta and pending checklist`() {
        renderOnboarding(OnboardingUiState(currentPage = OnboardingPages.lastIndex))

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidateAction).assertExists()
        composeRule.onNodeWithText("Start test").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidationStatus).assertExists()
        // No in-content finish/keep-running button in Idle.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishKeepRunning).assertCountEquals(0)
        // Skip is a secondary action near the bottom CTA ("Skip test"), not a top-right action.
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).assertExists()
        composeRule.onNodeWithText("Skip test").assertExists()
        // The bottom "Skip test" maps to finish-anyway but does not carry the content finish tag.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertCountEquals(0)
    }

    @Test
    fun `failed validation renders retry finish without testing and suggested mode action`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState =
                    OnboardingValidationState.Failed(
                        reason = "SOCKS listener failed",
                        suggestedMode = Mode.VPN,
                        failedStep = OnboardingValidationStep.Connectivity,
                    ),
            ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidateAction).assertExists()
        composeRule.onNodeWithText("Try again").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSwitchSuggestedMode).assertExists()
        // A connectivity/mode failure is recovered by switching mode, not by changing DNS.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingChangeDns).assertCountEquals(0)
    }

    @Test
    fun `vpn permission failure surfaces grant permission cta and proxy fallback`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                selectedMode = Mode.VPN,
                validationState =
                    OnboardingValidationState.Failed(
                        reason = "VPN permission denied",
                        recoveryKind = OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION,
                        suggestedMode = Mode.Proxy,
                        failedStep = OnboardingValidationStep.Tunnel,
                    ),
            ),
        )

        // Primary recovery is granting permission, NOT switching modes.
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidateAction).assertExists()
        composeRule.onNodeWithText("Grant VPN permission").assertExists()
        // Proxy fallback + finish are grouped secondary actions.
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSwitchSuggestedMode).assertExists()
        composeRule.onNodeWithText("Use Proxy mode instead").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertExists()
        // The local-VPN clarification is shown in the error banner.
        composeRule.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun `dns step failure renders change dns action and suppresses mode switch`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState =
                    OnboardingValidationState.Failed(
                        reason = "DNS resolution failed",
                        suggestedMode = null,
                        failedStep = OnboardingValidationStep.Dns,
                    ),
            ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingChangeDns).assertExists()
        composeRule.onNodeWithText("Change DNS").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertExists()
        // Retry remains the footer CTA on the failed page.
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidateAction).assertExists()
        // A DNS failure never offers a mode switch.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSwitchSuggestedMode).assertCountEquals(0)
    }

    @Test
    fun `change dns action navigates back to dns selection`() {
        var changeDns = false
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState =
                    OnboardingValidationState.Failed(
                        reason = "DNS resolution failed",
                        failedStep = OnboardingValidationStep.Dns,
                    ),
            ),
            onChangeDns = { changeDns = true },
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingChangeDns).performScrollTo().performClick()

        assertTrue(changeDns)
    }

    @Test
    fun `successful validation renders finish and disconnect actions`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState = OnboardingValidationState.Success(latencyMs = 42, mode = Mode.VPN),
            ),
        )

        // Footer CTA = "Finish" (keep running); content secondary = finish disconnected.
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishKeepRunning).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishDisconnected).assertExists()
        composeRule.onNodeWithText("Setup complete").assertExists()
        // No "finish without testing" once the test succeeded.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertCountEquals(0)
    }

    @Test
    fun `running connectivity check hides finish and validate actions`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                validationState = OnboardingValidationState.RunningTrafficCheck(Mode.Proxy),
            ),
        )

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidationStatus).assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingValidateAction).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishKeepRunning).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertCountEquals(0)
    }

    @Test
    fun `checking dns busy state hides actions and blocks skip`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                selectedMode = Mode.VPN,
                validationState = OnboardingValidationState.CheckingDns(Mode.VPN),
            ),
        )

        // Busy footer CTA carries no actionable tag and shows the testing label.
        composeRule.onNodeWithText("Testing…").assertExists()
        composeRule.onNodeWithContentDescription("Step 4 of 4").assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkip).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingValidateAction).assertCountEquals(0)
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidationStatus).assertExists()
    }

    @Test
    fun `starting mode busy state blocks skip and validate actions`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                selectedMode = Mode.VPN,
                validationState = OnboardingValidationState.StartingMode(Mode.VPN),
            ),
        )

        composeRule.onNodeWithContentDescription("Step 4 of 4").assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkip).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingValidateAction).assertCountEquals(0)
    }

    @Test
    fun `requesting vpn consent surfaces the local permission helper copy`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingPages.lastIndex,
                selectedMode = Mode.VPN,
                validationState = OnboardingValidationState.RequestingVpnConsent,
            ),
        )

        composeRule
            .onNodeWithText("Android will ask for permission to create a local VPN connection.")
            .assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkip).assertCountEquals(0)
    }

    private fun renderOnboarding(
        uiState: OnboardingUiState,
        onSkip: () -> Unit = {},
        onChangeDns: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = uiState,
                    actions =
                        OnboardingScreenActions(
                            onSkip = onSkip,
                            onChangeDns = onChangeDns,
                        ),
                )
            }
        }
    }
}
