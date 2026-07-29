package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class VpnTunnelAppliedNetworkReceiptExportTest {
    @Test
    fun `applied tunnel receipt is exported and disappears after invalidation`() =
        runTest {
            val snapshot =
                ServiceTelemetrySnapshot(
                    status = AppStatus.Running,
                    mode = Mode.VPN,
                    serviceStartedAt = 100L,
                    relayTelemetry =
                        NativeRuntimeSnapshot(
                            source = "relay",
                            state = "running",
                            health = "healthy",
                            listenerAddress = "127.0.0.1:1080",
                            protocolKind = RelayKindVlessReality,
                        ),
                )
            val serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            val receiptStore = VpnTunnelAppliedNetworkReceiptStore()
            serviceStateStore.updateTelemetry(snapshot)
            receiptStore.publish(
                VpnTunnelNetworkParameters(tunnelMtu = 1_320, metered = true),
                apiLevel = Build.VERSION_CODES.Q,
            )
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = serviceStateStore,
                    relayCapabilityProbe = successfulCapabilityProbe(),
                    underlayObservationProvider = FixedUnderlayObservationProvider,
                    deviceProvider = { RemoteDeviceAcceptanceDevice("SM-S928B", "XSG", 35, "arm64-v8a") },
                    monotonicClock = { 1_000L },
                    probeTargets = ProbeTargets,
                    appliedNetworkReceiptStore = receiptStore,
                )

            val published = probe.capture(snapshot)

            assertEquals(1_320, published.underlay.appliedTunnelMtuBytes)
            assertEquals(80, published.underlay.configuredEncapsulationBudgetBytes)
            assertEquals(true, published.underlay.appliedTunnelMetered)
            assertEquals("tun2socks", published.underlay.appliedTunnelEgress)
            val rendered = renderRemoteDeviceAcceptanceReport(published)
            assertTrue(rendered.contains("\"appliedTunnelMtuBytes\": 1320"))
            assertTrue(rendered.contains("\"configuredEncapsulationBudgetBytes\": 80"))

            receiptStore.invalidate()
            val invalidated = probe.capture(snapshot)

            assertEquals(null, invalidated.underlay.appliedTunnelMtuBytes)
            assertEquals("unavailable", invalidated.underlay.appliedTunnelEgress)
            assertFalse(renderRemoteDeviceAcceptanceReport(invalidated).contains("\"appliedTunnelMtuBytes\": 1320"))
        }

    @Test
    fun `API 28 receipt omits metered state that builder cannot apply`() {
        val receipt =
            VpnTunnelAppliedNetworkReceiptStore().publish(
                VpnTunnelNetworkParameters(metered = true),
                apiLevel = Build.VERSION_CODES.P,
            )

        assertNull(receipt.metered)
    }

    @Test
    fun `API 29 receipt records applied metered state`() {
        val receipt =
            VpnTunnelAppliedNetworkReceiptStore().publish(
                VpnTunnelNetworkParameters(metered = true),
                apiLevel = Build.VERSION_CODES.Q,
            )

        assertEquals(true, receipt.metered)
    }

    private fun successfulCapabilityProbe(): RelayCapabilityProbe =
        RelayCapabilityProbe(
            tcpProbe = RelayTcpProbe { _, _ -> RelayTcpProbeResult(succeeded = true, statusCode = 204) },
            udpProbe = RelayUdpAssociateProbe { _, _ -> RelayUdpProbeResult.success() },
            payloadHealthProbe =
                RelayUdpPayloadHealthProbe { _, families, _ ->
                    RelayUdpPayloadHealthEvidence(
                        overallVerdict = RelayUdpPayloadHealthVerdict.Acknowledged.wireValue,
                        families =
                            families.map { family ->
                                RelayUdpPayloadFamilyHealthEvidence(
                                    family = family.wireValue,
                                    controlBefore = RelayUdpPayloadControlOutcome.Acknowledged.wireValue,
                                    controlAfter = RelayUdpPayloadControlOutcome.Acknowledged.wireValue,
                                    maxAcknowledgedPayloadBytes = 1_232,
                                    firstRepeatedFailedPayloadBytes = null,
                                    attemptCount = 1,
                                    verdict = RelayUdpPayloadHealthVerdict.Acknowledged.wireValue,
                                )
                            },
                    )
                },
        )

    private companion object {
        val ProbeTargets =
            RemoteAcceptanceProbeTargets(
                catalogVersion = "receipt-fixture-v1",
                connectivityUrl = "https://acceptance.invalid/connectivity",
                ipv4Url = "https://acceptance-ipv4.invalid/generate_204",
                ipv6Url = "https://acceptance-ipv6.invalid/generate_204",
                udpAssociateTarget = InetSocketAddress("203.0.113.53", 53),
                udpPayloadTargets =
                    mapOf(
                        RelayUdpPayloadFamily.Ipv4 to InetSocketAddress("203.0.113.53", 53),
                    ),
            )

        val FixedUnderlayObservationProvider =
            object : AuthoritativeVpnUnderlayObservationProvider {
                override val changes: StateFlow<Long> = MutableStateFlow(0L)

                override fun capture(): NetworkPathObservation =
                    NetworkPathObservation(
                        generation = 1L,
                        mtuBand = "standard",
                        addressFamilies = listOf("ipv4"),
                        defaultRouteFamilies = listOf("ipv4"),
                        dnsServerFamilies = listOf("ipv4"),
                        nat64Present = false,
                    )
            }
    }
}
