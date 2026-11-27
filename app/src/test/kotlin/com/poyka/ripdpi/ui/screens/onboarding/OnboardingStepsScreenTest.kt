package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The steps around the four original ones, the notes of the idle test, and the completion step of every exit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OnboardingStepsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `steps come in the documented order and the progress counts all of them`() {
        val kinds = OnboardingPages.map { (it as? OnboardingPage.Setup)?.kind }
        assertEquals(
            listOf(
                SetupPageKind.Welcome,
                null,
                SetupPageKind.PersonaSelection,
                SetupPageKind.ModeSelection,
                SetupPageKind.DnsSelection,
                SetupPageKind.BackgroundGuidance,
                SetupPageKind.OwnServer,
                SetupPageKind.ConnectionTest,
                SetupPageKind.Completion,
            ),
            kinds,
        )
        // After a preset the three choice steps explain the app, and every other step keeps its place.
        val presetPages = onboardingPages(presetApplied = true)
        assertEquals(
            listOf(
                R.string.onboarding_preset_ready_title,
                R.string.onboarding_preset_connect_title,
                R.string.onboarding_preset_trouble_title,
            ),
            presetPages.subList(OnboardingInfoPageCount, OnboardingInfoPageCount + 3).map {
                (it as OnboardingPage.Informational).titleRes
            },
        )
        // The privacy step changes its text too: a preset can route traffic through remote servers.
        assertEquals(OnboardingPages - presetPages.toSet(), OnboardingPages.subList(1, 5))
        val presetText = { mode: Mode, page: Int ->
            (onboardingPages(presetApplied = true, mode = mode)[page] as OnboardingPage.Informational).descriptionRes
        }
        assertEquals(R.string.onboarding_local_first_body_preset, presetText(Mode.VPN, 1))
        assertEquals(R.string.onboarding_preset_connect_body, presetText(Mode.VPN, OnboardingInfoPageCount + 1))
        assertEquals(R.string.onboarding_preset_connect_body_proxy, presetText(Mode.Proxy, OnboardingInfoPageCount + 1))

        renderOnboarding(OnboardingUiState(currentPage = 0))

        composeRule.onNodeWithText("Welcome to RIPDPI").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Step 1 of 9").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingLanguage).assertHasClickAction()
    }

    @Test
    fun `skip on intro shows the completion step, which completes onboarding`() {
        var uiState by mutableStateOf(OnboardingUiState(currentPage = 0))
        var finished = false
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = uiState,
                    actions =
                        OnboardingScreenActions(
                            onShowCompletion = { uiState = uiState.copy(currentPage = OnboardingCompletionPageIndex) },
                            onFinish = { finished = true },
                        ),
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkip).performClick()
        composeRule.waitForIdle()

        assertFalse(finished)
        composeRule.onNodeWithText("Setup complete").assertIsDisplayed()
        composeRule.onNodeWithText("Not run").assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkipConfirmDialog).assertCountEquals(0)

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingCompletionDone).performClick()

        assertTrue(finished)
    }

    @Test
    fun `skip test and finish without testing show the completion step`() {
        var shown = 0
        var uiState by mutableStateOf(OnboardingUiState(currentPage = OnboardingTestPageIndex))
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = uiState,
                    actions = OnboardingScreenActions(onShowCompletion = { shown++ }, onFinish = { error("no exit") }),
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkipTest).performClick()
        assertEquals(1, shown)

        composeRule.runOnIdle {
            uiState = uiState.withValidationState(OnboardingValidationState.Failed(reason = "No answer"))
        }
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishAnyway).performScrollTo().performClick()
        assertEquals(2, shown)
    }

    @Test
    fun `completion after a passed test keeps the tunnel with Done and offers Disconnect`() {
        var kept = false
        var disconnected = false
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState =
                        OnboardingUiState(
                            currentPage = OnboardingCompletionPageIndex,
                            selectedPersona = "advanced",
                            selectedMode = Mode.Proxy,
                            selectedDnsProviderId = DnsProviderCloudflare,
                        ).withValidationState(
                            OnboardingValidationState.Success(
                                Mode.Proxy,
                                dnsSummary = "Test DNS",
                                volumeTested = true,
                            ),
                        ),
                    actions =
                        OnboardingScreenActions(
                            onFinishKeepRunning = { kept = true },
                            onFinishDisconnected = { disconnected = true },
                            onFinish = { error("a passed test must not stop the tunnel") },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        listOf("Advanced", "Proxy only", "Cloudflare", "Passed").forEach { value ->
            composeRule.onNodeWithText(value).assertExists()
        }
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingBack).assertCountEquals(0)
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishKeepRunning).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishDisconnected).performClick()

        assertTrue(kept)
        assertTrue(disconnected)
    }

    @Test
    fun `completion after a failed test states the failure and goes back with the back action`() {
        var back = false
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState =
                        OnboardingUiState(currentPage = OnboardingCompletionPageIndex)
                            .withValidationState(OnboardingValidationState.Failed(reason = "No answer")),
                    actions = OnboardingScreenActions(onBack = { back = true }),
                )
            }
        }

        composeRule.onNodeWithText("Failed").assertExists()
        composeRule.onNodeWithText("AdGuard DNS").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingBack).performClick()

        assertTrue(back)
    }

    @Test
    fun `idle test explains the notification request before the user can start the test`() {
        renderOnboarding(OnboardingUiState(currentPage = OnboardingTestPageIndex, notificationsNoteVisible = true))

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingNotificationNote).assertIsDisplayed()
        composeRule.onNodeWithText("Allow status notifications").assertIsDisplayed()
        composeRule.onNodeWithText("VPN permission").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidateAction).assertTextEquals("Start test")
    }

    @Test
    fun `denied notifications fail the first row and offer to allow them`() {
        renderOnboarding(
            OnboardingUiState(currentPage = OnboardingTestPageIndex).withValidationState(
                OnboardingValidationState.Failed(
                    reason = "Allow notifications so RIPDPI can show validation service status.",
                    recoveryKind = OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS,
                ),
            ),
        )

        // No row ran, so no row is done: the tunnel row failed and the others wait.
        composeRule.onNode(rowWithState("Start local VPN tunnel", "Failed")).assertExists()
        composeRule.onNode(rowWithState("Check DNS resolution", "Pending")).assertExists()
        composeRule.onNode(rowWithState("Verify internet connectivity", "Pending")).assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.OnboardingValidateAction)
            .assertTextEquals("Allow status notifications")
        composeRule.onAllNodesWithText("Grant VPN permission").assertCountEquals(0)
    }

    @Test
    fun `a volume failure completes the tunnel and dns rows and fails the connectivity row`() {
        renderOnboarding(
            OnboardingUiState(currentPage = OnboardingTestPageIndex).withValidationState(
                OnboardingValidationState.Failed(
                    reason = "The test transfer stopped after 20480 bytes.",
                    failedStep = OnboardingValidationStep.Volume,
                    transferredBytes = 20_480L,
                ),
            ),
        )

        composeRule.onNode(rowWithState("Start local VPN tunnel", "Done")).assertExists()
        composeRule.onNode(rowWithState("Check DNS resolution", "Done")).assertExists()
        composeRule.onNode(rowWithState("Verify internet connectivity", "Failed")).assertExists()
    }

    @Test
    fun `step progress is a polite live region so a step change is announced`() {
        renderOnboarding(OnboardingUiState(currentPage = 2))

        composeRule
            .onNodeWithContentDescription("Step 3 of 9")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `idle test has no notification note when android will not ask`() {
        renderOnboarding(OnboardingUiState(currentPage = OnboardingTestPageIndex))

        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingNotificationNote).assertCountEquals(0)
    }

    @Test
    fun `idle proxy test explains the local proxy instead of the vpn permission`() {
        renderOnboarding(OnboardingUiState(currentPage = OnboardingTestPageIndex, selectedMode = Mode.Proxy))

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingProxyNote).assertIsDisplayed()
        composeRule.onNodeWithText("Local proxy").assertIsDisplayed()
        composeRule.onAllNodesWithText("VPN permission").assertCountEquals(0)
    }

    @Test
    fun `dns step selects a resolver and maps the default resolver to the untouched settings`() {
        val selected = mutableListOf<String>()
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = OnboardingUiState(currentPage = onboardingPageIndex(SetupPageKind.DnsSelection)),
                    actions = OnboardingScreenActions(onDnsSelected = { selected += it }),
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingDnsOptionPrefix + DnsProviderAdGuard).assertIsSelected()
        composeRule
            .onNodeWithTag(RipDpiTestTags.OnboardingDnsOptionPrefix + DnsProviderCloudflare)
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.OnboardingDnsOptionPrefix + DnsProviderAdGuard)
            .performScrollTo()
            .performClick()

        assertEquals(listOf(DnsProviderCloudflare, OnboardingDnsSystemId), selected)
    }

    @Test
    fun `background step opens system settings and hides vpn settings for the proxy`() {
        var uiState by mutableStateOf(
            OnboardingUiState(currentPage = onboardingPageIndex(SetupPageKind.BackgroundGuidance)),
        )
        val opened = mutableListOf<String>()
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = uiState,
                    actions =
                        OnboardingScreenActions(
                            onOpenBatterySettings = { opened += "battery" },
                            onOpenVpnSettings = { opened += "vpn" },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Always-on VPN").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingBatterySettings).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingVpnSettings).performScrollTo().performClick()
        assertEquals(listOf("battery", "vpn"), opened)
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingContinue).assertExists()

        composeRule.runOnIdle { uiState = uiState.copy(selectedMode = Mode.Proxy) }

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingBatterySettings).assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingVpnSettings).assertCountEquals(0)
        composeRule.onAllNodesWithText("Always-on VPN").assertCountEquals(0)
    }

    @Test
    fun `own server step offers each way to add a server and continues without one`() {
        val used = mutableListOf<Any>()
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = OnboardingUiState(currentPage = onboardingPageIndex(SetupPageKind.OwnServer)),
                    actions =
                        OnboardingScreenActions(
                            onPasteServerLink = { used += "paste" },
                            onScanServer = { used += "scan" },
                            onOpenRoute = { used += it },
                            onContinue = { used += "continue" },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Have your own server?").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayAddServerPaste).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayAddServerScan).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayImportAwgConf).performScrollTo().assertHasClickAction()
        composeRule.onNodeWithTag(RipDpiTestTags.ConnectionRelayAddProfile).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.connectionRelayCreateProfile(Route.SshProfile)).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingContinue).performClick()

        composeRule.runOnIdle { assertEquals(listOf("paste", "scan", Route.SshProfile, "continue"), used) }
    }

    /** A checklist row: the state description is on the row, and the label is its child. */
    private fun rowWithState(
        label: String,
        state: String,
    ): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state) and hasAnyDescendant(hasText(label))

    private fun renderOnboarding(uiState: OnboardingUiState) {
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(uiState = uiState, actions = OnboardingScreenActions())
            }
        }
    }
}
