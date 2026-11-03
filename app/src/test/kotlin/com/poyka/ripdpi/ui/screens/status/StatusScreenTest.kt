package com.poyka.ripdpi.ui.screens.status

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
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsRemediationActionUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsRemediationLadderUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
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
class StatusScreenTest {
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
                StatusSubscriptionExpiryBanner(
                    state = subscriptionExpiryUiState(listOf(group), now),
                    onOpenStatus = { opened = true },
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.StatusSubscriptionExpiryBanner).performClick()

        assertTrue(opened)
    }

    @Test
    fun `actuator fault detail copies the message on click`() {
        val error = "Failed to start VPN"
        composeRule.setContent {
            RipDpiTheme {
                StatusScreen(
                    uiState =
                        MainUiState(
                            connectionState = ConnectionState.Error,
                            errorMessage = error,
                            connectionActuator =
                                StatusConnectionActuatorUiState(
                                    status = StatusConnectionActuatorStatus.Fault,
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
    @Config(qualifiers = "w1300dp-h900dp")
    fun `expanded window puts the secondary cards in a supporting pane beside the actuator`() {
        composeRule.setContent {
            RipDpiTheme {
                StatusScreen(
                    uiState = MainUiState(modeCards = modeCards()),
                    onToggleConnection = {},
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        val actuator =
            composeRule.onNodeWithTag(RipDpiTestTags.ConnectionActuatorButton).fetchSemanticsNode().boundsInRoot
        val health =
            composeRule.onNodeWithTag(RipDpiTestTags.HomeConnectionHealthAction).fetchSemanticsNode().boundsInRoot
        assertTrue("Supporting pane must sit beside the actuator", health.left >= actuator.right)
        val density = composeRule.density.density
        assertTrue("Supporting pane stays near 360dp", health.width <= 360f * density)
    }

    @Test
    fun `home keeps mode cards collapsed until disclosure expands`() {
        var primaryConnectionToggles = 0
        composeRule.setContent {
            RipDpiTheme {
                StatusScreen(
                    uiState = MainUiState(modeCards = modeCards()),
                    onToggleConnection = { primaryConnectionToggles++ },
                    onOpenDiagnostics = {},
                    onOpenHistory = {},
                    onRepairPermission = {},
                    onOpenVpnPermissionDialog = {},
                )
            }
        }

        StatusMode.entries.forEach { mode ->
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
        StatusMode.entries.forEach { mode ->
            composeRule
                .onAllNodesWithTag(RipDpiTestTags.homeModeCard(mode.name))
                .assertCountEquals(1)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.homeModeCard(StatusMode.Diagnostic.name)).performScrollTo()
        val bypassTop = cardTop(StatusMode.LocalDpiBypass)
        val vpnTop = cardTop(StatusMode.RemoteVpn)
        val diagnosticTop = cardTop(StatusMode.Diagnostic)
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
    fun `mode cards open their settings and carry no connect action`() {
        var bypassOpened = false
        var vpnOpened = false
        composeRule.setContent {
            RipDpiTheme {
                StatusScreen(
                    uiState = MainUiState(modeCards = modeCards(activeMode = StatusMode.LocalDpiBypass)),
                    onToggleConnection = {},
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
        composeRule
            .onAllNodesWithTag(RipDpiTestTags.homeModePrimaryAction(StatusMode.LocalDpiBypass.name))
            .assertCountEquals(0)
        composeRule
            .onAllNodesWithTag(RipDpiTestTags.homeModePrimaryAction(StatusMode.RemoteVpn.name))
            .assertCountEquals(0)
        clickHomeNode(RipDpiTestTags.homeModeConfigureAction(StatusMode.LocalDpiBypass.name))
        clickHomeNode(RipDpiTestTags.homeModeCardBody(StatusMode.RemoteVpn.name))

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
                        StatusScreen(
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
                StatusScreen(
                    uiState =
                        MainUiState(
                            connectionState = ConnectionState.Error,
                            errorMessage = "Tunnel failed",
                            connectionActuator =
                                StatusConnectionActuatorUiState(
                                    status = StatusConnectionActuatorStatus.Fault,
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
                StatusScreen(
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
            .onNodeWithTag(RipDpiTestTags.homeModePrimaryAction(StatusMode.Diagnostic.name))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeConfigureAction(StatusMode.Diagnostic.name))
            .assertTextEquals("Open Diagnostics")
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeCardBody(StatusMode.Diagnostic.name))
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
                StatusScreen(
                    uiState =
                        MainUiState(
                            modeCards = modeCards(),
                            homeDiagnostics =
                                StatusDiagnosticsUiState(
                                    analysisAction =
                                        StatusDiagnosticsActionUiState(
                                            label = "Run Full Analysis",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                    verifiedVpnAction =
                                        StatusDiagnosticsActionUiState(
                                            label = "Start Verified VPN",
                                            supportingText = "Ready",
                                            enabled = true,
                                        ),
                                    analysisSheet =
                                        StatusDiagnosticsAnalysisSheetUiState(
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
                                        StatusDiagnosticsVerificationSheetUiState(
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
                StatusScreen(
                    uiState =
                        MainUiState(
                            homeDiagnostics =
                                StatusDiagnosticsUiState(
                                    pcapToggleVisible = false,
                                    analysisAction =
                                        StatusDiagnosticsActionUiState(
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
                StatusScreen(
                    uiState =
                        MainUiState(
                            homeDiagnostics =
                                StatusDiagnosticsUiState(
                                    pcapToggleVisible = true,
                                    pcapRecordingRequested = false,
                                    analysisAction =
                                        StatusDiagnosticsActionUiState(
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
                StatusScreen(
                    uiState =
                        MainUiState(
                            homeDiagnostics =
                                StatusDiagnosticsUiState(
                                    pcapToggleVisible = true,
                                    pcapRecordingRequested = false,
                                    analysisAction =
                                        StatusDiagnosticsActionUiState(
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
                StatusScreen(
                    uiState =
                        MainUiState(
                            modeCards = modeCards(),
                            homeDiagnostics =
                                StatusDiagnosticsUiState(
                                    analysisSheet =
                                        StatusDiagnosticsAnalysisSheetUiState(
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

    private fun cardTop(mode: StatusMode): Float =
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

    private fun modeCards(activeMode: StatusMode? = null) =
        persistentListOf(
            card(StatusMode.LocalDpiBypass, activeMode = activeMode),
            card(StatusMode.RemoteVpn, activeMode = activeMode),
            card(StatusMode.Diagnostic, activeMode = activeMode),
        )

    private fun card(
        mode: StatusMode,
        activeMode: StatusMode?,
    ): StatusModeCardUiState {
        val active = mode == activeMode
        return StatusModeCardUiState(
            mode = mode,
            title =
                when (mode) {
                    StatusMode.LocalDpiBypass -> "Local bypass"
                    StatusMode.RemoteVpn -> "VPN"
                    StatusMode.Diagnostic -> "Network Diagnostic"
                },
            primaryLabel =
                when (mode) {
                    StatusMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                    StatusMode.RemoteVpn -> "relay.example"
                    StatusMode.Diagnostic -> "No analysis yet"
                },
            statusLine = if (active) "Connected 00:01:00" else "Inactive",
            primaryActionLabel =
                when {
                    mode == StatusMode.Diagnostic -> "Run Scan"
                    active -> "Disable"
                    else -> "Enable"
                },
            configureLabel =
                if (mode == StatusMode.Diagnostic) {
                    "Open Diagnostics"
                } else {
                    "Configure"
                },
            primaryActionEnabled = true,
            isActive = active,
        )
    }
}
