package com.poyka.ripdpi.services

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
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceAcceptanceBaselineProbeTest {
    @Test
    fun `family steps use Reality egress probes instead of app active network`() =
        runTest {
            val requestedUrls = mutableListOf<String>()
            val snapshot = runningRealitySnapshot()
            val serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            serviceStateStore.updateTelemetry(snapshot)
            val capabilityProbe =
                RelayCapabilityProbe(
                    tcpProbe =
                        RelayTcpProbe { _, url ->
                            requestedUrls += url
                            if (url == RemoteAcceptanceIpv6ProbeUrl) {
                                RelayTcpProbeResult(
                                    succeeded = false,
                                    failure = RelayProbeFailure.TcpConnect,
                                )
                            } else {
                                RelayTcpProbeResult(succeeded = true, statusCode = 204)
                            }
                        },
                    udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.success() },
                    payloadHealthProbe =
                        RelayUdpPayloadHealthProbe { _, families ->
                            successfulPayloadHealth(families)
                        },
                )
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = serviceStateStore,
                    relayCapabilityProbe = capabilityProbe,
                    underlayObservationProvider = TestUnderlayObservationProvider(DualStackUnderlay),
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                )

            val report = probe.capture(snapshot)

            assertEquals(
                setOf(
                    RemoteAcceptanceConnectivityProbeUrl,
                    RemoteAcceptanceIpv4ProbeUrl,
                    RemoteAcceptanceIpv6ProbeUrl,
                ),
                requestedUrls.toSet(),
            )
            assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.step(StepIpv4).status)
            assertEquals(RemoteDeviceAcceptanceStatus.Fail, report.step(StepIpv6).status)
            assertEquals(RelayProbeFailure.TcpConnect.wireValue, report.step(StepIpv6).errorClass)
            assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.step(StepRelayUdpPayload).status)
            assertEquals(
                setOf("ipv4", "ipv6"),
                report.pathHealth
                    ?.families
                    ?.map { it.family }
                    ?.toSet(),
            )
            assertTrue(report.steps.none { it.id.contains("route") })
        }

    @Test
    fun `payload families are mandatory only for usable default routes`() =
        runTest {
            val observedFamilies = mutableListOf<Set<RelayUdpPayloadFamily>>()
            val underlay =
                NetworkPathObservation(
                    generation = 7L,
                    mtuBand = "standard",
                    addressFamilies = listOf("ipv4", "ipv6"),
                    defaultRouteFamilies = listOf("ipv4"),
                    dnsServerFamilies = listOf("ipv4", "ipv6"),
                    nat64Present = false,
                )
            val snapshot = runningRealitySnapshot()
            val serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            serviceStateStore.updateTelemetry(snapshot)
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = serviceStateStore,
                    relayCapabilityProbe = successfulCapabilityProbe(observedFamilies),
                    underlayObservationProvider = TestUnderlayObservationProvider(underlay),
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                )

            val report = probe.capture(snapshot)

            assertEquals(listOf(setOf(RelayUdpPayloadFamily.Ipv4)), observedFamilies)
            assertEquals(listOf("ipv4"), report.pathHealth?.families?.map { it.family })
        }

    @Test
    fun `ipv6 only nat64 underlay keeps ipv4 unsupported and nat64 reachability unknown`() =
        runTest {
            val observedFamilies = mutableListOf<Set<RelayUdpPayloadFamily>>()
            val underlay =
                NetworkPathObservation(
                    generation = 8L,
                    mtuBand = "standard",
                    addressFamilies = listOf("ipv6"),
                    defaultRouteFamilies = listOf("ipv6"),
                    dnsServerFamilies = listOf("ipv6"),
                    nat64Present = true,
                )
            val snapshot = runningRealitySnapshot()
            val serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            serviceStateStore.updateTelemetry(snapshot)
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = serviceStateStore,
                    relayCapabilityProbe = successfulCapabilityProbe(observedFamilies),
                    underlayObservationProvider = TestUnderlayObservationProvider(underlay),
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                )

            val report = probe.capture(snapshot)

            assertEquals(listOf(setOf(RelayUdpPayloadFamily.Ipv6)), observedFamilies)
            assertEquals(listOf("ipv6"), report.pathHealth?.families?.map { it.family })
            assertEquals(true, report.underlay.nat64Advertised)
            assertEquals("unknown", report.underlay.nat64Reachability)
        }

    @Test
    fun `payload probe is skipped outside active remote acceptance preflight`() =
        runTest {
            var payloadCalls = 0
            val capabilityProbe =
                RelayCapabilityProbe(
                    tcpProbe = RelayTcpProbe { _, _ -> RelayTcpProbeResult(succeeded = true, statusCode = 204) },
                    udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.success() },
                    payloadHealthProbe =
                        RelayUdpPayloadHealthProbe { _, families ->
                            payloadCalls += 1
                            successfulPayloadHealth(families)
                        },
                )
            val cases =
                listOf(
                    TestServiceStateStore(AppStatus.Halted to Mode.VPN) to runningRealitySnapshot(),
                    TestServiceStateStore(AppStatus.Running to Mode.Proxy) to runningRealitySnapshot(),
                    TestServiceStateStore(AppStatus.Running to Mode.VPN) to
                        runningRealitySnapshot(protocolKind = "hysteria2"),
                    TestServiceStateStore(AppStatus.Running to Mode.VPN) to
                        runningRealitySnapshot(listenerAddress = null),
                )

            cases.forEach { (store, snapshot) ->
                store.updateTelemetry(snapshot)
                val probe =
                    RemoteDeviceAcceptanceBaselineProbe(
                        serviceStateStore = store,
                        relayCapabilityProbe = capabilityProbe,
                        underlayObservationProvider = TestUnderlayObservationProvider(DualStackUnderlay),
                        deviceProvider = { Device },
                        monotonicClock = { 1_000L },
                    )
                probe.capture(snapshot)
            }

            assertEquals(0, payloadCalls)
        }

    @Test
    fun `payload health is incomplete when post probe runtime context drifts`() =
        runTest {
            val initial = runningRealitySnapshot()
            val serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            serviceStateStore.updateTelemetry(initial)
            val capabilityProbe =
                RelayCapabilityProbe(
                    tcpProbe = RelayTcpProbe { _, _ -> RelayTcpProbeResult(succeeded = true, statusCode = 204) },
                    udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.success() },
                    payloadHealthProbe =
                        RelayUdpPayloadHealthProbe { _, families ->
                            serviceStateStore.updateTelemetry(
                                runningRealitySnapshot(listenerAddress = "127.0.0.1:1081"),
                            )
                            successfulPayloadHealth(families)
                        },
                )
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = serviceStateStore,
                    relayCapabilityProbe = capabilityProbe,
                    underlayObservationProvider = TestUnderlayObservationProvider(DualStackUnderlay),
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                )

            val report = probe.capture(initial)

            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.step(StepRelayUdpPayload).status)
            assertEquals(ErrorPayloadHealthContextDrift, report.step(StepRelayUdpPayload).errorClass)
            assertEquals(null, report.pathHealth)
        }

    @Test
    fun `payload health cache reuses evidence during guided cooldown`() =
        runTest {
            var payloadCalls = 0
            val snapshot = runningRealitySnapshot()
            val serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            serviceStateStore.updateTelemetry(snapshot)
            val capabilityProbe =
                RelayCapabilityProbe(
                    tcpProbe = RelayTcpProbe { _, _ -> RelayTcpProbeResult(succeeded = true, statusCode = 204) },
                    udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.success() },
                    payloadHealthProbe =
                        RelayUdpPayloadHealthProbe { _, families ->
                            payloadCalls += 1
                            successfulPayloadHealth(families)
                        },
                )
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = serviceStateStore,
                    relayCapabilityProbe = capabilityProbe,
                    underlayObservationProvider = TestUnderlayObservationProvider(DualStackUnderlay),
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                )

            probe.capture(snapshot)
            probe.capture(snapshot)

            assertEquals(1, payloadCalls)
        }

    private fun successfulCapabilityProbe(
        observedFamilies: MutableList<Set<RelayUdpPayloadFamily>>,
    ): RelayCapabilityProbe =
        RelayCapabilityProbe(
            tcpProbe = RelayTcpProbe { _, _ -> RelayTcpProbeResult(succeeded = true, statusCode = 204) },
            udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.success() },
            payloadHealthProbe =
                RelayUdpPayloadHealthProbe { _, families ->
                    observedFamilies += families
                    successfulPayloadHealth(families)
                },
        )

    private fun runningRealitySnapshot(
        listenerAddress: String? = "127.0.0.1:1080",
        protocolKind: String? = RelayKindVlessReality,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            status = AppStatus.Running,
            mode = Mode.VPN,
            serviceStartedAt = 100L,
            relayTelemetry =
                NativeRuntimeSnapshot(
                    source = "relay",
                    state = "running",
                    health = "healthy",
                    listenerAddress = listenerAddress,
                    protocolKind = protocolKind,
                ),
        )

    private fun RemoteDeviceAcceptanceReport.step(id: String): RemoteDeviceAcceptanceStep = steps.first { it.id == id }

    private companion object {
        val Device = RemoteDeviceAcceptanceDevice("SM-S928B", "XSG", 35, "arm64-v8a")
        val DualStackUnderlay =
            NetworkPathObservation(
                mtuBand = "standard",
                addressFamilies = listOf("ipv4", "ipv6"),
                defaultRouteFamilies = listOf("ipv4", "ipv6"),
                dnsServerFamilies = listOf("ipv4", "ipv6"),
                nat64Present = false,
            )
    }
}

private fun successfulPayloadHealth(families: Set<RelayUdpPayloadFamily>): RelayUdpPayloadHealthEvidence =
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
                    attemptCount = 7,
                    verdict = RelayUdpPayloadHealthVerdict.Acknowledged.wireValue,
                )
            },
    )

private class TestUnderlayObservationProvider(
    private val observation: NetworkPathObservation,
) : AuthoritativeVpnUnderlayObservationProvider {
    override val changes: StateFlow<Long> = MutableStateFlow(0L)

    override fun capture(): NetworkPathObservation = observation
}
