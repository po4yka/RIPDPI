package com.poyka.ripdpi.ui.screens.status

import android.app.Application
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.ResourceStringResolver
import com.poyka.ripdpi.activities.VpnDataPlaneStatus
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.screens.diagnostics.state.summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class StatusModeCardUiStateTest {
    private val stringResolver = ResourceStringResolver()

    @Test
    fun `modeCards exposes exactly the three home modes in stable order`() {
        val cards = buildCards()

        assertEquals(
            listOf(StatusMode.LocalDpiBypass, StatusMode.RemoteVpn, StatusMode.Diagnostic),
            cards.map { it.mode },
        )
        assertEquals("Proxy only", cards[0].title)
        assertEquals("Whole device", cards[1].title)
        assertEquals("Diagnostic scan", cards[2].title)
    }

    @Test
    fun `local bypass card is active only for connected proxy mode`() {
        val cards =
            buildCards(
                settings =
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.Proxy.preferenceValue)
                        .build(),
                activeMode = Mode.Proxy,
                configuredMode = Mode.Proxy,
                connectionState = ConnectionState.Connected,
                connectionDuration = 65.seconds,
            )

        val localBypass = cards.single { it.mode == StatusMode.LocalDpiBypass }
        val remoteVpn = cards.single { it.mode == StatusMode.RemoteVpn }
        assertTrue(localBypass.isActive)
        assertFalse(localBypass.isLoading)
        assertEquals("Connected 00:01:05", localBypass.secondaryLabel)
        assertFalse(remoteVpn.isActive)
    }

    @Test
    fun `vpn card is active for connected vpn mode when relay is disabled`() {
        val cards =
            buildCards(
                settings =
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.VPN.preferenceValue)
                        .setRelayEnabled(false)
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connected,
                vpnDataPlaneStatus = VpnDataPlaneStatus.Working,
                connectionDuration = 3.seconds,
            )

        val localBypass = cards.single { it.mode == StatusMode.LocalDpiBypass }
        val remoteVpn = cards.single { it.mode == StatusMode.RemoteVpn }
        assertFalse(localBypass.isActive)
        assertTrue(remoteVpn.isActive)
        assertEquals("Connected 00:00:03", remoteVpn.secondaryLabel)
        assertEquals("Direct", remoteVpn.primaryLabel)
    }

    @Test
    fun `vpn card shows the direct path when relay is disabled`() {
        val cards =
            buildCards(
                settings =
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.VPN.preferenceValue)
                        .setRelayEnabled(false)
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Disconnected,
            )

        val remoteVpn = cards.single { it.mode == StatusMode.RemoteVpn }
        assertEquals("Direct", remoteVpn.primaryLabel)
    }

    @Test
    fun `vpn card explains why saved doq cannot start vpn`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(DnsModeEncrypted)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDoq)
                .build()
        val disconnected = buildCards(settings = settings).single { it.mode == StatusMode.RemoteVpn }
        assertTrue(disconnected.statusLine.contains("Proxy mode"))

        val connected =
            buildCards(settings = settings, activeMode = Mode.VPN, connectionState = ConnectionState.Connected)
                .single { it.mode == StatusMode.RemoteVpn }
        assertFalse(connected.statusLine.contains("Proxy mode"))
    }

    @Test
    fun `vpn card is active for connected vpn mode and summarizes relay server`() {
        val cards =
            buildCards(
                settings =
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.VPN.preferenceValue)
                        .setRelayEnabled(true)
                        .setRelayKind(RelayKindVlessReality)
                        .setRelayServer("relay.example")
                        .setRelayServerName("relay.example")
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connected,
                vpnDataPlaneStatus = VpnDataPlaneStatus.Working,
                connectionDuration = 5.seconds,
            )

        val localBypass = cards.single { it.mode == StatusMode.LocalDpiBypass }
        val remoteVpn = cards.single { it.mode == StatusMode.RemoteVpn }
        assertFalse(localBypass.isActive)
        assertTrue(remoteVpn.isActive)
        assertEquals("Relay · relay.example", remoteVpn.primaryLabel)
        assertEquals("Connected 00:00:05", remoteVpn.secondaryLabel)
    }

    @Test
    fun `configured mode card is loading while connection is starting`() {
        val cards =
            buildCards(
                settings =
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.Proxy.preferenceValue)
                        .build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.Proxy,
                connectionState = ConnectionState.Connecting,
            )

        val localBypass = cards.single { it.mode == StatusMode.LocalDpiBypass }
        val remoteVpn = cards.single { it.mode == StatusMode.RemoteVpn }
        assertTrue(localBypass.isLoading)
        assertFalse(localBypass.isActive)
        assertEquals("Starting...", localBypass.secondaryLabel)
        assertFalse(remoteVpn.isLoading)
    }

    @Test
    fun `diagnostic card is busy only while home analysis action is busy`() {
        val cards =
            buildCards(
                homeDiagnostics =
                    StatusDiagnosticsUiState(
                        analysisAction =
                            StatusDiagnosticsActionUiState(
                                supportingText = "Stage 1 of 4 - Automatic audit",
                                busy = true,
                            ),
                    ),
            )

        val diagnostic = cards.single { it.mode == StatusMode.Diagnostic }
        assertTrue(diagnostic.isLoading)
        assertFalse(diagnostic.isActive)
        assertEquals("Stage 1 of 4 - Automatic audit", diagnostic.primaryLabel)
    }

    @Test
    fun `diagnostic card summarizes latest audit when analysis is idle`() {
        val cards =
            buildCards(
                homeDiagnostics =
                    StatusDiagnosticsUiState(
                        latestAudit =
                            StatusDiagnosticsLatestAuditUiState(
                                headline = "Analysis complete",
                                summary = "Working TCP and QUIC winners found.",
                                recommendationSummary = "TCP split + QUIC fake",
                            ),
                    ),
            )

        val diagnostic = cards.single { it.mode == StatusMode.Diagnostic }
        assertFalse(diagnostic.isLoading)
        assertEquals("Analysis complete", diagnostic.primaryLabel)
        assertEquals("TCP split + QUIC fake", diagnostic.secondaryLabel)
    }

    @Test
    fun `diagnostic card status line prioritizes actionable audit state`() {
        val diagnostic =
            buildCards(
                homeDiagnostics =
                    StatusDiagnosticsUiState(
                        latestAudit =
                            StatusDiagnosticsLatestAuditUiState(
                                headline = "Analysis complete",
                                summary = "Working TCP and QUIC winners found.",
                                completedStageCount = 4,
                                totalStageCount = 4,
                                actionable = true,
                            ),
                    ),
            ).single { it.mode == StatusMode.Diagnostic }

        assertEquals("Recommendation ready", diagnostic.statusLine)
    }

    @Test
    fun `diagnostic card status line asks for rerun when latest audit is stale`() {
        val diagnostic =
            buildCards(
                homeDiagnostics =
                    StatusDiagnosticsUiState(
                        latestAudit =
                            StatusDiagnosticsLatestAuditUiState(
                                headline = "Analysis complete",
                                summary = "Working TCP and QUIC winners found.",
                                completedStageCount = 4,
                                totalStageCount = 4,
                                stale = true,
                                actionable = true,
                            ),
                    ),
            ).single { it.mode == StatusMode.Diagnostic }

        assertEquals("Network changed - rerun scan", diagnostic.statusLine)
    }

    @Test
    fun `diagnostic card status line summarizes latest audit stage counts`() {
        val failedDiagnostic =
            buildCards(
                homeDiagnostics =
                    StatusDiagnosticsUiState(
                        latestAudit =
                            StatusDiagnosticsLatestAuditUiState(
                                headline = "Analysis incomplete",
                                summary = "Some stages failed.",
                                completedStageCount = 2,
                                failedStageCount = 1,
                                totalStageCount = 4,
                            ),
                    ),
            ).single { it.mode == StatusMode.Diagnostic }
        val completeDiagnostic =
            buildCards(
                homeDiagnostics =
                    StatusDiagnosticsUiState(
                        latestAudit =
                            StatusDiagnosticsLatestAuditUiState(
                                headline = "Analysis complete",
                                summary = "No recommendation.",
                                completedStageCount = 3,
                                totalStageCount = 4,
                            ),
                    ),
            ).single { it.mode == StatusMode.Diagnostic }

        assertEquals("Review 1/4 stages", failedDiagnostic.statusLine)
        assertEquals("3/4 stages complete", completeDiagnostic.statusLine)
    }

    @Test
    fun `diagnostic card secondary action opens diagnostics`() {
        val diagnostic = buildCards().single { it.mode == StatusMode.Diagnostic }

        assertEquals("Open diagnostics", diagnostic.configureLabel)
    }

    @Test
    fun `diagnostic idle action is a quick check`() {
        val diagnostic = buildCards().single { it.mode == StatusMode.Diagnostic }

        assertEquals("Quick scan", diagnostic.primaryActionLabel)
    }

    @Test
    fun `diagnostic startup and running actions remain cancellable`() {
        listOf(StatusDiagnosticsRunUiStatus.STARTING, StatusDiagnosticsRunUiStatus.RUNNING).forEach { status ->
            val diagnostic =
                buildCards(
                    homeDiagnostics =
                        StatusDiagnosticsUiState(
                            analysisRunStatus = status,
                            analysisAction = StatusDiagnosticsActionUiState(busy = true, enabled = false),
                        ),
                ).single { it.mode == StatusMode.Diagnostic }

            assertEquals(stringResolver.getString(R.string.diagnostics_action_cancel), diagnostic.primaryActionLabel)
            assertTrue(diagnostic.primaryActionEnabled)
            assertTrue(diagnostic.isLoading)
        }
    }

    private fun buildCards(
        settings: AppSettings = AppSettingsSerializer.defaultValue,
        activeMode: Mode = Mode.VPN,
        configuredMode: Mode = Mode.VPN,
        connectionState: ConnectionState = ConnectionState.Disconnected,
        vpnDataPlaneStatus: VpnDataPlaneStatus = VpnDataPlaneStatus.NotApplicable,
        connectionDuration: Duration = ZERO,
        homeDiagnostics: StatusDiagnosticsUiState = StatusDiagnosticsUiState(),
    ) = buildStatusModeCards(
        StatusModeCardsInput(
            settings = settings,
            activeMode = activeMode,
            configuredMode = configuredMode,
            connectionState = connectionState,
            vpnDataPlaneStatus = vpnDataPlaneStatus,
            connectionDuration = connectionDuration,
            homeDiagnostics = homeDiagnostics,
            stringResolver = stringResolver,
        ),
    )

    private class ResourceStringResolver : StringResolver {
        override fun getQuantityString(
            resId: Int,
            quantity: Int,
            vararg formatArgs: Any,
        ): String =
            org.robolectric.RuntimeEnvironment
                .getApplication()
                .resources
                .getQuantityString(resId, quantity, *formatArgs)

        private val application: Application = RuntimeEnvironment.getApplication()

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = application.getString(resId, *formatArgs)
    }
}
