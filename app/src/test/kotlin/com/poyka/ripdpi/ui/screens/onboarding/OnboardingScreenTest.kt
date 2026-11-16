package com.poyka.ripdpi.ui.screens.onboarding

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingBack).assertCountEquals(0)
    }

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
                SetupPageKind.ConnectionTest,
                SetupPageKind.Completion,
            ),
            kinds,
        )

        renderOnboarding(OnboardingUiState(currentPage = 0))

        composeRule.onNodeWithText("Welcome to RIPDPI").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Step 1 of 8").assertExists()
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
                        ).withValidationState(OnboardingValidationState.Success(Mode.Proxy, dnsSummary = "Test DNS")),
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
    fun `setup pages do not show a top skip action`() {
        renderOnboarding(uiState = OnboardingUiState(currentPage = OnboardingInfoPageCount))

        // Setup steps rely on safe defaults + the bottom CTA; no top "Use recommended"/skip.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkip).assertCountEquals(0)
        composeRule.onAllNodesWithText("Use recommended").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w1300dp-h900dp")
    fun `expanded window keeps the continue action inside the form column`() {
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(uiState = OnboardingUiState(currentPage = 1), actions = OnboardingScreenActions())
            }
        }

        val bounds = composeRule.onNodeWithTag(RipDpiTestTags.OnboardingContinue).fetchSemanticsNode().boundsInRoot
        val width = bounds.width
        assertTrue("Continue must not stretch across the window", width <= 700f * composeRule.density.density)
    }

    @Test
    fun `second step exposes back to the intro`() {
        var back = false
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = OnboardingUiState(currentPage = 1),
                    actions = OnboardingScreenActions(onBack = { back = true }),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Navigate back").performClick()

        assertTrue(back)
    }

    @Test
    fun `system back returns to the previous step and keeps selections`() {
        var uiState by mutableStateOf(
            OnboardingUiState(
                currentPage = onboardingPageIndex(SetupPageKind.ModeSelection),
                selectedPersona = "advanced",
                selectedMode = Mode.Proxy,
            ),
        )
        lateinit var backDispatcher: OnBackPressedDispatcher
        composeRule.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            RipDpiTheme {
                OnboardingScreen(
                    uiState = uiState,
                    actions =
                        OnboardingScreenActions(
                            onBack = { uiState = uiState.copy(currentPage = uiState.currentPage - 1) },
                            onContinue = { uiState = uiState.copy(currentPage = uiState.currentPage + 1) },
                        ),
                )
            }
        }

        composeRule.runOnIdle { backDispatcher.onBackPressed() }

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingPersonaAdvanced).assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingContinue).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingModeProxy).assertIsSelected()
    }

    @Test
    fun `busy validation disables visible back and consumes system back`() {
        var validationState by mutableStateOf<OnboardingValidationState>(
            OnboardingValidationState.RequestingNotifications,
        )
        var navigatedBack = false
        var exitedOnboarding = false
        lateinit var backDispatcher: OnBackPressedDispatcher
        composeRule.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            BackHandler { exitedOnboarding = true }
            RipDpiTheme {
                OnboardingScreen(
                    uiState =
                        OnboardingUiState(
                            currentPage = OnboardingTestPageIndex,
                            validationState = validationState,
                        ),
                    actions = OnboardingScreenActions(onBack = { navigatedBack = true }),
                )
            }
        }

        listOf(
            OnboardingValidationState.RequestingNotifications,
            OnboardingValidationState.RequestingVpnConsent,
            OnboardingValidationState.StartingMode(Mode.VPN),
            OnboardingValidationState.CheckingDns(Mode.VPN),
            OnboardingValidationState.RunningTrafficCheck(Mode.VPN),
        ).forEach { busyState ->
            composeRule.runOnIdle { validationState = busyState }
            composeRule.onNodeWithTag(RipDpiTestTags.OnboardingBack).assertIsNotEnabled()
            composeRule.runOnIdle { backDispatcher.onBackPressed() }
            assertFalse(navigatedBack)
            assertFalse(exitedOnboarding)
        }
    }

    @Test
    fun `idle connection test exposes start cta and pending checklist`() {
        renderOnboarding(OnboardingUiState(currentPage = OnboardingTestPageIndex))

        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidateAction).assertExists()
        composeRule.onNodeWithText("Start test").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidationStatus).assertExists()
        // No in-content finish/keep-running button in Idle.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishKeepRunning).assertCountEquals(0)
        // Skip is a secondary top-bar action ("Skip test"), which keeps the footer free of an empty slot.
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingSkipTest).assertExists()
        composeRule.onNodeWithText("Skip test").assertExists()
        // The "Skip test" action maps to finish-anyway but does not carry the content finish tag.
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertCountEquals(0)
    }

    @Test
    fun `failed validation renders retry finish without testing and suggested mode action`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingTestPageIndex,
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
                currentPage = OnboardingTestPageIndex,
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
                currentPage = OnboardingTestPageIndex,
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
                currentPage = OnboardingTestPageIndex,
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
    fun `successful validation states the running route and its two exits`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingTestPageIndex,
                validationState = OnboardingValidationState.Success(mode = Mode.VPN, dnsSummary = "Test DNS"),
            ),
        )

        composeRule.onNodeWithText("Connected").assertExists()
        composeRule.onNodeWithText("Whole device · Test DNS").assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishKeepRunning).assertTextEquals("Done")
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingFinishDisconnected).assertTextEquals("Disconnect")
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingFinishAnyway).assertCountEquals(0)
        composeRule.onAllNodesWithText("Verify internet connectivity").assertCountEquals(0)
    }

    @Test
    fun `dns failure offers both change dns and the mode switch`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingTestPageIndex,
                validationState =
                    OnboardingValidationState.Failed(
                        reason = "DNS resolution failed",
                        suggestedMode = Mode.Proxy,
                        failedStep = OnboardingValidationStep.Dns,
                    ),
            ),
        )

        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingChangeDns).assertCountEquals(1)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSwitchSuggestedMode).assertCountEquals(1)
    }

    @Test
    fun `successful validation has no visible back and consumes system back`() {
        var navigatedBack = false
        lateinit var backDispatcher: OnBackPressedDispatcher
        composeRule.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            RipDpiTheme {
                OnboardingScreen(
                    uiState =
                        OnboardingUiState(
                            currentPage = OnboardingTestPageIndex,
                            validationState =
                                OnboardingValidationState.Success(mode = Mode.VPN, dnsSummary = "Test DNS"),
                        ),
                    actions = OnboardingScreenActions(onBack = { navigatedBack = true }),
                )
            }
        }

        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingBack).assertCountEquals(0)
        composeRule.runOnIdle { backDispatcher.onBackPressed() }
        assertFalse(navigatedBack)
    }

    @Test
    fun `running connectivity check hides finish and validate actions`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingTestPageIndex,
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
                currentPage = OnboardingTestPageIndex,
                selectedMode = Mode.VPN,
                validationState = OnboardingValidationState.CheckingDns(Mode.VPN),
            ),
        )

        // Busy footer CTA carries no actionable tag and shows the testing label.
        composeRule.onNodeWithText("Testing…").assertExists()
        composeRule.onNodeWithContentDescription("Step 7 of 8").assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkipTest).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingValidateAction).assertCountEquals(0)
        composeRule.onNodeWithTag(RipDpiTestTags.OnboardingValidationStatus).assertExists()
    }

    @Test
    fun `starting mode busy state blocks skip and validate actions`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingTestPageIndex,
                selectedMode = Mode.VPN,
                validationState = OnboardingValidationState.StartingMode(Mode.VPN),
            ),
        )

        composeRule.onNodeWithContentDescription("Step 7 of 8").assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkipTest).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingValidateAction).assertCountEquals(0)
    }

    @Test
    fun `requesting vpn consent surfaces the local permission helper copy`() {
        renderOnboarding(
            OnboardingUiState(
                currentPage = OnboardingTestPageIndex,
                selectedMode = Mode.VPN,
                validationState = OnboardingValidationState.RequestingVpnConsent,
            ),
        )

        composeRule
            .onNodeWithText("Android will ask for permission to create a local VPN connection.")
            .assertExists()
        composeRule.onAllNodesWithTag(RipDpiTestTags.OnboardingSkipTest).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land")
    fun `landscape intro keeps both guarantee lists in view`() {
        renderOnboarding(OnboardingUiState(currentPage = 1))

        // The last item of each list must be on screen without scrolling.
        listOf("No cloud sync", "Local config").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `maximum font keeps footer reachable and option copy complete`() {
        var uiState by mutableStateOf(OnboardingUiState(currentPage = 0))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredSize(width = 411.dp, height = 839.dp)) {
                        OnboardingScreen(
                            uiState = uiState,
                            actions = OnboardingScreenActions(),
                        )
                    }
                }
            }
        }

        OnboardingPages.indices.forEach { page ->
            composeRule.runOnIdle { uiState = uiState.copy(currentPage = page) }
            composeRule.waitForIdle()
            val rootBounds =
                composeRule
                    .onNodeWithTag(RipDpiTestTags.screen(Route.Onboarding))
                    .fetchSemanticsNode()
                    .boundsInRoot
            val footerTag =
                when (page) {
                    OnboardingTestPageIndex -> RipDpiTestTags.OnboardingValidateAction
                    OnboardingCompletionPageIndex -> RipDpiTestTags.OnboardingCompletionDone
                    else -> RipDpiTestTags.OnboardingContinue
                }
            val footerBounds = composeRule.onNodeWithTag(footerTag).fetchSemanticsNode().boundsInRoot
            assertTrue(footerBounds.top >= rootBounds.top)
            assertTrue(footerBounds.bottom <= rootBounds.bottom)
        }

        composeRule.runOnIdle { uiState = uiState.copy(currentPage = 1) }
        composeRule.waitForIdle()
        val introLayouts = mutableListOf<TextLayoutResult>()
        val introNode =
            composeRule
                .onNodeWithText("No cloud sync", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(introLayouts) }
                .fetchSemanticsNode()
        assertTrue(introNode.boundsInRoot.height + 1f >= introLayouts.single().size.height)
        val localHeader =
            composeRule
                .onNodeWithText("On your device", useUnmergedTree = true)
                .fetchSemanticsNode()
        assertTrue(localHeader.boundsInRoot.top >= introNode.boundsInRoot.bottom)

        composeRule.runOnIdle { uiState = uiState.copy(currentPage = OnboardingInfoPageCount) }
        composeRule.waitForIdle()
        listOf(
            "Everyday controls stay visible while expert details stay collapsed.",
            "Show all diagnostics, profiles, and low-level settings.",
        ).forEach(::assertCompleteScrollableText)

        composeRule.runOnIdle { uiState = uiState.copy(currentPage = OnboardingInfoPageCount + 1) }
        composeRule.waitForIdle()
        listOf(
            "Routes all apps through an on-device VPN. Android asks for permission first.",
            "Runs a local proxy for apps or clients that support manual proxy configuration.",
        ).forEach(::assertCompleteScrollableText)
    }

    private fun assertCompleteScrollableText(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val layout = layouts.single()
        assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
    }

    private fun renderOnboarding(
        uiState: OnboardingUiState,
        onFinish: () -> Unit = {},
        onChangeDns: () -> Unit = {},
    ) {
        composeRule.setContent {
            RipDpiTheme {
                OnboardingScreen(
                    uiState = uiState,
                    actions =
                        OnboardingScreenActions(
                            onFinish = onFinish,
                            onChangeDns = onChangeDns,
                        ),
                )
            }
        }
    }
}
