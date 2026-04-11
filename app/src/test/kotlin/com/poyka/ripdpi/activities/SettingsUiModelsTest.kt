package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiModelsTest {
    @Test
    fun `dns ui state defaults match canonical encrypted settings`() {
        val defaultDns = canonicalDefaultEncryptedDnsSettings()
        val state = DnsUiState()

        assertEquals(defaultDns.dnsIp, state.dnsIp)
        assertEquals(defaultDns.providerId, state.dnsProviderId)
        assertEquals(defaultDns.encryptedDnsProtocol, state.encryptedDnsProtocol)
        assertEquals(defaultDns.encryptedDnsHost, state.encryptedDnsHost)
        assertEquals(defaultDns.encryptedDnsPort, state.encryptedDnsPort)
        assertEquals(defaultDns.encryptedDnsTlsServerName, state.encryptedDnsTlsServerName)
        assertEquals(defaultDns.encryptedDnsBootstrapIps, state.encryptedDnsBootstrapIps)
        assertEquals(defaultDns.encryptedDnsDohUrl, state.encryptedDnsDohUrl)
        assertEquals(defaultDns.summary(), state.dnsSummary)
    }

    @Test
    fun `adaptive fallback ui defaults stay enabled with all triggers`() {
        val state = AdaptiveFallbackUiState()

        assertTrue(state.enabled)
        assertTrue(state.torst)
        assertTrue(state.tlsErr)
        assertTrue(state.httpRedirect)
        assertTrue(state.connectFailure)
        assertTrue(state.autoSort)
        assertEquals(4, state.triggerCount)
        assertTrue(state.usesDefaultProfile)
    }

    @Test
    fun `routing protection suggests dht mitigation when split routing is active`() {
        val state =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setAntiCorrelationEnabled(true)
                .build()
                .toUiState(routingProtectionSnapshot = RoutingProtectionCatalogSnapshot())

        assertTrue(state.routingProtection.suggestions.any { it.id == "dht_mitigation" })
    }

    @Test
    fun `adaptive fallback only flags remembered override when exact policy was reused`() {
        val proxyTelemetry =
            NativeRuntimeSnapshot(
                source = "proxy",
                adaptiveOverrideActive = true,
            )

        val exactState =
            AppSettingsSerializer.defaultValue.toUiState(
                proxyTelemetry = proxyTelemetry,
                runtimeOverrideRememberedPolicy = true,
            )
        val inferredState =
            AppSettingsSerializer.defaultValue.toUiState(
                proxyTelemetry = proxyTelemetry,
                runtimeOverrideRememberedPolicy = false,
            )

        assertTrue(exactState.adaptiveFallback.runtimeOverrideRememberedPolicy)
        assertFalse(inferredState.adaptiveFallback.runtimeOverrideRememberedPolicy)
    }

    @Test
    fun `routing protection uses runtime pressure for dht suggestion`() {
        val state =
            AppSettingsSerializer.defaultValue.toUiState(
                serviceTelemetry =
                    ServiceTelemetrySnapshot(
                        status = AppStatus.Running,
                        relayTelemetry =
                            NativeRuntimeSnapshot(
                                source = "relay",
                                lastFailureClass = "tls_alert",
                            ),
                    ),
                routingProtectionSnapshot = RoutingProtectionCatalogSnapshot(),
            )

        assertTrue(state.routingProtection.suggestions.any { it.id == "dht_mitigation" })
    }

    @Test
    fun `routing protection prefers explicit dht correlation reason`() {
        val state =
            AppSettingsSerializer.defaultValue.toUiState(
                serviceTelemetry =
                    ServiceTelemetrySnapshot(
                        status = AppStatus.Running,
                        runtimeFieldTelemetry =
                            com.poyka.ripdpi.data.RuntimeFieldTelemetry(
                                dhtTriggerCorrelationActive = true,
                                dhtTriggerCorrelationReason =
                                    "Recent UDP traffic to 134.195.198.23:6881 was followed by tls_interference on relay, WARP, or TLS control-plane paths.",
                            ),
                    ),
                routingProtectionSnapshot = RoutingProtectionCatalogSnapshot(),
            )

        val suggestion = state.routingProtection.suggestions.first { it.id == "dht_mitigation" }
        assertTrue(suggestion.body.contains("134.195.198.23:6881"))
    }
}
