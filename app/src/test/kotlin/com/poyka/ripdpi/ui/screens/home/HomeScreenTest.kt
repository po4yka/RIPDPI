package com.poyka.ripdpi.ui.screens.home

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationLadderUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsAnalysisSheetUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsVerificationSheetUiState
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.permissions.PermissionIssueUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionRecovery
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.subscription.subscriptionExpiryUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `subscription expiry banner opens status`() {
        var opened = false
        val now = 1_000L
        val group =
            ProxyGroup(
                id = "phone",
                name = "Phone",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = false,
                subscription = Subscription(tokenExpiresAtEpochMillis = now + 24L * 60L * 60L * 1_000L),
            )
        composeRule.setContent {
            RipDpiTheme {
                HomeSubscriptionExpiryBanner(
                    state = subscriptionExpiryUiState(listOf(group), now),
                    onOpenStatus = { opened = true },
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.HomeSubscriptionExpiryBanner).performClick()

        assertTrue(opened)
    }

    @Test
    fun `actuator fault detail copies the message on click`() {
        val error = "Failed to start VPN"
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            connectionState = ConnectionState.Error,
                            errorMessage = error,
                            connectionActuator =
                                HomeConnectionActuatorUiState(
                                    status = HomeConnectionActuatorStatus.Fault,
                                    faultDetail = error,
                                ),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorFaultDetail)
            .performClick()

        val clipboard =
            RuntimeEnvironment
                .getApplication()
                .getSystemService(ClipboardManager::class.java)
        assertEquals(
            error,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    @Test
    fun `home keeps mode cards collapsed until disclosure expands`() {
        var primaryConnectionToggles = 0
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = MainUiState(modeCards = modeCards()),
                    onToggleConnection = { primaryConnectionToggles++ },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        HomeMode.entries.forEach { mode ->
            composeRule
                .onAllNodesWithTag(RipDpiTestTags.homeModeCard(mode.name))
                .assertCountEquals(0)
        }
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeModesDiagnosticsHeader).assertCountEquals(1)
        composeRule.onNodeWithTag(RipDpiTestTags.HomeModesDiagnosticsHeader).assertHasClickAction()
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeModesDiagnosticsCollapsed).assertCountEquals(1)
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeModesDiagnosticsExpanded).assertCountEquals(0)
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        expandModesAndDiagnostics()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeModesDiagnosticsExpanded).assertIsDisplayed()
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeModesDiagnosticsCollapsed).assertCountEquals(0)
        HomeMode.entries.forEach { mode ->
            composeRule
                .onAllNodesWithTag(RipDpiTestTags.homeModeCard(mode.name))
                .assertCountEquals(1)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.homeModeCard(HomeMode.Diagnostic.name)).performScrollTo()
        val bypassTop = cardTop(HomeMode.LocalDpiBypass)
        val vpnTop = cardTop(HomeMode.RemoteVpn)
        val diagnosticTop = cardTop(HomeMode.Diagnostic)
        assertTrue(vpnTop > bypassTop)
        assertTrue(diagnosticTop > vpnTop)
        assertEquals(1, primaryConnectionToggles)

        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeStatusCard).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeDiagnosticsCard).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeApproachCard).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeHistoryCard).assertCountEquals(0)
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeStatsGrid).assertCountEquals(0)
    }

    @Test
    fun `mode cards route low emphasis actions through screen callbacks`() {
        var bypassEnabled: Boolean? = null
        var vpnEnabled: Boolean? = null
        var bypassOpened = false
        var vpnOpened = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = MainUiState(modeCards = modeCards(activeMode = HomeMode.LocalDpiBypass)),
                    onToggleConnection = {},
                    onBypassToggle = { bypassEnabled = it },
                    onVpnToggle = { vpnEnabled = it },
                    onBypassCardClick = { bypassOpened = true },
                    onVpnCardClick = { vpnOpened = true },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        expandModesAndDiagnostics()
        clickHomeNode(RipDpiTestTags.homeModePrimaryAction(HomeMode.LocalDpiBypass.name))
        clickHomeNode(RipDpiTestTags.homeModePrimaryAction(HomeMode.RemoteVpn.name))
        clickHomeNode(RipDpiTestTags.homeModeConfigureAction(HomeMode.LocalDpiBypass.name))
        clickHomeNode(RipDpiTestTags.homeModeCardBody(HomeMode.RemoteVpn.name))

        assertEquals(false, bypassEnabled)
        assertEquals(true, vpnEnabled)
        assertTrue(bypassOpened)
        assertTrue(vpnOpened)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
    fun `primary actuator stays above setup warnings at Pixel 7 width and maximum font`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        HomeScreen(
                            uiState =
                                MainUiState(
                                    permissionSummary =
                                        PermissionSummaryUiState(
                                            issue =
                                                PermissionIssueUiState(
                                                    kind = PermissionKind.VpnConsent,
                                                    title = "VPN permission needed",
                                                    message = "Allow RIPDPI to create the local VPN before connecting.",
                                                    recovery = PermissionRecovery.ShowVpnPermissionDialog,
                                                    actionLabel = "Allow VPN",
                                                    blocking = true,
                                                ),
                                        ),
                                    modeCards = modeCards(),
                                ),
                            onToggleConnection = {},
                            onOpenDiagnostics = {},
                            onOpenHistory = {},
                            onRepairPermission = {},
                            onOpenVpnPermissionDialog = {},
                        )
                    }
                }
            }
        }

        val actuator =
            composeRule
                .onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton)
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val setupWarning = composeRule.onNodeWithTag(RipDpiTestTags.HomeSetupHealthRow).fetchSemanticsNode()
        assertTrue(actuator.boundsInRoot.bottom <= setupWarning.boundsInRoot.top)
    }

    /**
     * The message used to sit in a banner of its own below the actuator, which
     * made a single failure occupy three surfaces. It now belongs to the
     * control that reports the fault, so it has to render inside it.
     */
    @Test
    fun `fault detail renders inside the actuator, not beside it`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            connectionState = ConnectionState.Error,
                            errorMessage = "Tunnel failed",
                            connectionActuator =
                                HomeConnectionActuatorUiState(
                                    status = HomeConnectionActuatorStatus.Fault,
                                    statusDescription = "Secure line fault at Tunnel",
                                    actionLabel = "Retry secure line",
                                    faultDetail = "Tunnel failed",
                                ),
                            modeCards = modeCards(),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        val rail = composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).fetchSemanticsNode()
        val detail = composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorFaultDetail).fetchSemanticsNode()
        composeRule.onNodeWithText("Tunnel failed").assertIsDisplayed()
        assertTrue(rail.boundsInRoot.bottom <= detail.boundsInRoot.top)
    }

    @Test
    fun `diagnostic card runs analysis and opens diagnostics`() {
        var ranAnalysis = false
        var openedDiagnostics = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState = MainUiState(modeCards = modeCards()),
                    onToggleConnection = {},
                    onDiagnosticRun = { ranAnalysis = true },
                    onDiagnosticCardClick = { openedDiagnostics = true },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        expandModesAndDiagnostics()
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(HomeMode.Diagnostic.name))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeConfigureAction(HomeMode.Diagnostic.name))
            .assertTextEquals("Open Diagnostics")
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeCardBody(HomeMode.Diagnostic.name))
            .performScrollTo()
            .performClick()

        assertTrue(ranAnalysis)
        assertTrue(openedDiagnostics)
    }

    @Test
    fun `analysis and verification sheets stay functional with mode card body`() {
        var shared = false
        var openedDiagnostics = false
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            modeCards = modeCards(),
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    analysisAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Run Full Analysis",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                    verifiedVpnAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Start Verified VPN",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                    analysisSheet =
                                        HomeDiagnosticsAnalysisSheetUiState(
                                            runId = "home-run",
                                            headline = "Analysis complete",
                                            summary = "Settings were applied.",
                                            appliedSettings =
                                                persistentListOf(
                                                    DiagnosticsAppliedSetting("WARP routing", "Rules"),
                                                    DiagnosticsAppliedSetting("WARP hostlist", "2 hosts"),
                                                ),
                                        ),
                                    verificationSheet =
                                        HomeDiagnosticsVerificationSheetUiState(
                                            sessionId = "verify-session",
                                            success = true,
                                            headline = "VPN access confirmed",
                                            summary = "Connectivity is working.",
                                        ),
                                ),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = { openedDiagnostics = true },
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                    onShareAnalysis = { shared = true },
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsAnalysisSheet).assertIsDisplayed()
        composeRule.onNodeWithText("WARP routing").assertIsDisplayed()
        composeRule.onNodeWithText("2 hosts").assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsShareAction).performClick()
        assertTrue(shared)

        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerificationSheet).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsVerificationOpenDiagnosticsAction).performClick()
        assertTrue(openedDiagnostics)
    }

    @Test
    fun `pcap toggle hidden when root mode disabled`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    pcapToggleVisible = false,
                                    analysisAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Run Full Analysis",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                ),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule
            .onAllNodesWithTag(RipDpiTestTags.HomeDiagnosticsPcapToggle)
            .assertCountEquals(0)
        expandModesAndDiagnostics()
        composeRule
            .onAllNodesWithTag(RipDpiTestTags.HomeDiagnosticsPcapToggle)
            .assertCountEquals(0)
    }

    @Test
    fun `pcap toggle visible and disabled until opt-in when root mode enabled`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    pcapToggleVisible = true,
                                    pcapRecordingRequested = false,
                                    analysisAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Run Full Analysis",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                ),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        composeRule
            .onAllNodesWithTag(RipDpiTestTags.HomeDiagnosticsPcapToggle)
            .assertCountEquals(0)
        expandModesAndDiagnostics()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsPcapToggle)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.home_diagnostics_pcap_helper))
            .assertIsDisplayed()
    }

    @Test
    fun `pcap toggle invokes callback when tapped`() {
        var toggleCount = 0
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    pcapToggleVisible = true,
                                    pcapRecordingRequested = false,
                                    analysisAction =
                                        HomeDiagnosticsActionUiState(
                                            label = "Run Full Analysis",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                ),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                    onTogglePcapRecording = { toggleCount += 1 },
                )
            }
        }

        composeRule
            .onAllNodesWithTag(RipDpiTestTags.HomeDiagnosticsPcapToggle)
            .assertCountEquals(0)
        expandModesAndDiagnostics()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsPcapToggle)
            .performScrollTo()
            .performClick()

        assertEquals(1, toggleCount)
    }

    @Test
    fun `analysis sheet owned stack remediation opens browser target`() {
        var openedBrowserTarget: String? = null
        composeRule.setContent {
            RipDpiTheme {
                HomeScreen(
                    uiState =
                        MainUiState(
                            modeCards = modeCards(),
                            homeDiagnostics =
                                HomeDiagnosticsUiState(
                                    analysisSheet =
                                        HomeDiagnosticsAnalysisSheetUiState(
                                            runId = "home-run",
                                            headline = "Owned stack required",
                                            summary = "Open the owned-stack browser.",
                                            remediationLadder =
                                                DiagnosticsRemediationLadderUiModel(
                                                    title = "Owned stack path",
                                                    summary = "Open the target with RIPDPI browser.",
                                                    steps = persistentListOf(),
                                                    primaryAction =
                                                        DiagnosticsRemediationActionUiModel(
                                                            label = "Open browser",
                                                            kind =
                                                                DiagnosticsRemediationActionKindUiModel
                                                                    .OPEN_OWNED_STACK_BROWSER,
                                                            targetUrl = "https://example.org:443/",
                                                        ),
                                                    tone = DiagnosticsTone.Warning,
                                                ),
                                        ),
                                ),
                        ),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                    onOpenOwnedStackBrowser = { openedBrowserTarget = it },
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.HomeDiagnosticsRemediationAction).performClick()

        assertEquals("https://example.org:443/", openedBrowserTarget)
    }

    private fun cardTop(mode: HomeMode): Float =
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeCard(mode.name))
            .fetchSemanticsNode()
            .boundsInRoot
            .top

    private fun clickHomeNode(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun expandModesAndDiagnostics() {
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeModesDiagnosticsHeader)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
    }

    private fun modeCards(activeMode: HomeMode? = null) =
        persistentListOf(
            card(HomeMode.LocalDpiBypass, activeMode = activeMode),
            card(HomeMode.RemoteVpn, activeMode = activeMode),
            card(HomeMode.Diagnostic, activeMode = activeMode),
        )

    private fun card(
        mode: HomeMode,
        activeMode: HomeMode?,
    ): HomeModeCardUiState {
        val active = mode == activeMode
        return HomeModeCardUiState(
            mode = mode,
            title =
                when (mode) {
                    HomeMode.LocalDpiBypass -> "Local bypass"
                    HomeMode.RemoteVpn -> "VPN"
                    HomeMode.Diagnostic -> "Network Diagnostic"
                },
            primaryLabel =
                when (mode) {
                    HomeMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                    HomeMode.RemoteVpn -> "relay.example"
                    HomeMode.Diagnostic -> "No analysis yet"
                },
            statusLine = if (active) "Connected 00:01:00" else "Inactive",
            primaryActionLabel =
                when {
                    mode == HomeMode.Diagnostic -> "Run Scan"
                    active -> "Disable"
                    else -> "Enable"
                },
            configureLabel =
                if (mode == HomeMode.Diagnostic) {
                    "Open Diagnostics"
                } else {
                    "Configure"
                },
            primaryActionEnabled = true,
            isActive = active,
        )
    }
}
