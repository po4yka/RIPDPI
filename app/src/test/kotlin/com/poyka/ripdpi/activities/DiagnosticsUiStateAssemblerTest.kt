package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsUiStateAssemblerTest {
    @Test
    fun `selectActiveConnectionPolicy prefers active mode policy`() {
        val vpnPolicy = diagnosticPolicy(mode = Mode.VPN, appliedAt = 100L)
        val proxyPolicy = diagnosticPolicy(mode = Mode.Proxy, appliedAt = 200L)

        val selected =
            selectActiveConnectionPolicy(
                serviceMode = Mode.VPN,
                activePolicies = mapOf(Mode.Proxy to proxyPolicy, Mode.VPN to vpnPolicy),
            )

        assertEquals(vpnPolicy, selected)
    }

    @Test
    fun `selectActiveConnectionPolicy falls back to most recently applied policy`() {
        val vpnPolicy = diagnosticPolicy(mode = Mode.VPN, appliedAt = 100L)
        val proxyPolicy = diagnosticPolicy(mode = Mode.Proxy, appliedAt = 200L)

        val selected =
            selectActiveConnectionPolicy(
                serviceMode = Mode.VPN,
                activePolicies = mapOf(Mode.Proxy to proxyPolicy),
            )

        assertEquals(proxyPolicy, selected)
        assertEquals(
            proxyPolicy,
            selectActiveConnectionPolicy(
                serviceMode = Mode.Proxy,
                activePolicies = mapOf(Mode.VPN to vpnPolicy, Mode.Proxy to proxyPolicy),
            ),
        )
    }

    @Test
    fun `buildCurrentServiceTelemetry synthesizes service sample for running runtime`() {
        val sample =
            buildCurrentServiceTelemetry(
                status = AppStatus.Running,
                mode = Mode.VPN,
                telemetry =
                    ServiceTelemetrySnapshot(
                        mode = Mode.VPN,
                        status = AppStatus.Running,
                        tunnelStats =
                            TunnelStats(
                                txPackets = 4,
                                txBytes = 1_024,
                                rxPackets = 7,
                                rxBytes = 2_048,
                            ),
                        updatedAt = 123L,
                    ),
                activeConnectionSession = null,
            )

        assertNotNull(sample)
        assertEquals("service-state:vpn:123", sample?.id)
        assertEquals("Running", sample?.connectionState)
        assertEquals("VPN", sample?.activeMode)
        assertEquals(4L, sample?.txPackets)
        assertEquals(7L, sample?.rxPackets)
    }

    @Test
    fun `buildCurrentServiceTelemetry stays null for halted idle runtime`() {
        val sample =
            buildCurrentServiceTelemetry(
                status = AppStatus.Halted,
                mode = Mode.VPN,
                telemetry = ServiceTelemetrySnapshot(),
                activeConnectionSession = null,
            )

        assertNull(sample)
    }

    private fun diagnosticPolicy(
        mode: Mode,
        appliedAt: Long,
    ): DiagnosticActiveConnectionPolicy =
        DiagnosticActiveConnectionPolicy(
            mode = mode,
            policy =
                RememberedNetworkPolicyJson(
                    fingerprintHash = "hash-${mode.name}",
                    mode = mode.preferenceValue,
                    summary =
                        NetworkFingerprintSummary(
                            transport = "wifi",
                            networkState = "connected",
                            identityKind = "ssid",
                            privateDnsMode = "off",
                            dnsServerCount = 1,
                        ),
                    proxyConfigJson = "{}",
                ),
            policySignature = "signature-${mode.name}",
            appliedAt = appliedAt,
        )
}
