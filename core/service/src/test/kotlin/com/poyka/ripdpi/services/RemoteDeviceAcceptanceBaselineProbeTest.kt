package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceAcceptanceBaselineProbeTest {
    @Test
    fun `stale AWG telemetry cannot override selected relay egress`() =
        runTest {
            val snapshot = runningRealitySnapshot(staleAwgTelemetry = true)
            val store = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            store.updateTelemetry(snapshot)
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = store,
                    relayCapabilityProbe = successfulCapabilityProbe(mutableListOf()),
                    underlayObservationProvider = TestUnderlayObservationProvider(DualStackUnderlay),
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
                )

            val report = probe.capture(snapshot)

            assertEquals(RelayKindVlessReality, report.transportKind)
            assertEquals(AcceptanceProbeApplicability.Required.wireValue, report.step(StepRealityTcp).applicability)
            assertEquals(
                AcceptanceProbeApplicability.NotApplicable.wireValue,
                report.step(StepAmneziaWgRuntime).applicability,
            )
        }

    @Test
    fun `missing configured targets do not make hidden public probe calls`() =
        runTest {
            val requestedUrls = mutableListOf<String>()
            val snapshot = runningRealitySnapshot()
            val store = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            store.updateTelemetry(snapshot)
            val probe =
                RemoteDeviceAcceptanceBaselineProbe(
                    serviceStateStore = store,
                    relayCapabilityProbe =
                        RelayCapabilityProbe(
                            tcpProbe =
                                RelayTcpProbe { _, url ->
                                    requestedUrls += url
                                    RelayTcpProbeResult(succeeded = true, statusCode = 204)
                                },
                            udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.success() },
                        ),
                    underlayObservationProvider = TestUnderlayObservationProvider(DualStackUnderlay),
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                )

            val report = probe.capture(snapshot)

            assertTrue(requestedUrls.isEmpty())
            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.status)
            assertEquals(ErrorRemoteAcceptanceProbeTargetMissing, report.step(StepRealityTcp).errorClass)
            assertEquals(ErrorRemoteAcceptanceProbeTargetMissing, report.step(StepIpv4).errorClass)
        }

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
                            if (url == FixtureIpv6ProbeUrl) {
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
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
                )

            val report = probe.capture(snapshot)

            assertEquals(
                setOf(
                    FixtureConnectivityProbeUrl,
                    FixtureIpv4ProbeUrl,
                    FixtureIpv6ProbeUrl,
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
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
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
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
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
                        runningRealitySnapshot(protocolKind = "vless"),
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
                        probeTargets = FixtureRemoteAcceptanceProbeTargets,
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
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
                )

            val report = probe.capture(initial)

            listOf(StepRealityTcp, StepUdpAssociate, StepDnsUdp, StepRelayUdpPayload, StepIpv4, StepIpv6)
                .forEach { stepId ->
                    assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.step(stepId).status)
                    assertEquals(ErrorPayloadHealthContextDrift, report.step(stepId).errorClass)
                }
            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.step(StepRelayUdpPayload).status)
            assertEquals(ErrorPayloadHealthContextDrift, report.step(StepRelayUdpPayload).errorClass)
            assertEquals(null, report.pathHealth)
        }

    @Test
    fun `context drift takes precedence over null connectivity probes`() =
        runTest {
            val initial = runningRealitySnapshot()
            val serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            serviceStateStore.updateTelemetry(initial)
            val capabilityProbe =
                RelayCapabilityProbe(
                    tcpProbe =
                        RelayTcpProbe { _, _ ->
                            serviceStateStore.updateTelemetry(
                                runningRealitySnapshot(listenerAddress = "127.0.0.1:1081"),
                            )
                            error("connectivity probe unavailable")
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
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
                )

            val report = probe.capture(initial)

            listOf(StepRealityTcp, StepUdpAssociate, StepDnsUdp, StepRelayUdpPayload, StepIpv4, StepIpv6)
                .forEach { stepId ->
                    assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.step(stepId).status)
                    assertEquals(ErrorPayloadHealthContextDrift, report.step(stepId).errorClass)
                }
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
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
                )

            probe.capture(snapshot)
            probe.capture(snapshot)

            assertEquals(1, payloadCalls)
        }

    @Test
    fun `payload health cache key includes service start`() =
        runTest {
            var payloadCalls = 0
            val firstSnapshot = runningRealitySnapshot(serviceStartedAt = 100L)
            val secondSnapshot = runningRealitySnapshot(serviceStartedAt = 200L)
            val serviceStateStore = TestServiceStateStore(AppStatus.Running to Mode.VPN)
            serviceStateStore.updateTelemetry(firstSnapshot)
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
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
                )

            probe.capture(firstSnapshot)
            serviceStateStore.updateTelemetry(secondSnapshot)
            probe.capture(secondSnapshot)

            assertEquals(2, payloadCalls)
        }

    @Test
    fun `payload health cache is bypassed when underlay generation is unknown`() =
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
                    underlayObservationProvider =
                        TestUnderlayObservationProvider(
                            DualStackUnderlay.copy(generation = null),
                        ),
                    deviceProvider = { Device },
                    monotonicClock = { 1_000L },
                    probeTargets = FixtureRemoteAcceptanceProbeTargets,
                )

            probe.capture(snapshot)
            probe.capture(snapshot)

            assertEquals(2, payloadCalls)
        }

    @Test
    fun `payload health cache shares concurrent same key loaders`() =
        runTest {
            val cache = RelayUdpPayloadHealthCache()
            val key = payloadCacheKey()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var payloadCalls = 0
            val first =
                async {
                    cache.getOrPut(key, nowMs = 1_000L) {
                        payloadCalls += 1
                        started.complete(Unit)
                        release.await()
                        successfulPayloadHealth(setOf(RelayUdpPayloadFamily.Ipv4))
                    }
                }
            val second =
                async {
                    started.await()
                    cache.getOrPut(key, nowMs = 1_000L) {
                        payloadCalls += 1
                        successfulPayloadHealth(setOf(RelayUdpPayloadFamily.Ipv4))
                    }
                }

            started.await()
            release.complete(Unit)

            assertEquals(1, payloadCalls)
            assertEquals(RelayUdpPayloadHealthVerdict.Acknowledged.wireValue, first.await()?.overallVerdict)
            assertEquals(RelayUdpPayloadHealthVerdict.Acknowledged.wireValue, second.await()?.overallVerdict)
        }

    @Test
    fun `payload health cache retries after cancelled loader`() =
        runTest {
            val cache = RelayUdpPayloadHealthCache()
            val key = payloadCacheKey()
            val started = CompletableDeferred<Unit>()
            val cancelled =
                async {
                    cache.getOrPut(key, nowMs = 1_000L) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }

            started.await()
            cancelled.cancelAndJoin()
            var retryCalls = 0
            val result =
                cache.getOrPut(key, nowMs = 1_000L) {
                    retryCalls += 1
                    successfulPayloadHealth(setOf(RelayUdpPayloadFamily.Ipv4))
                }

            assertEquals(1, retryCalls)
            assertEquals(RelayUdpPayloadHealthVerdict.Acknowledged.wireValue, result?.overallVerdict)
        }

    @Test
    fun `payload health cache follower retries after shared leader is cancelled`() =
        runTest {
            val cache = RelayUdpPayloadHealthCache()
            val key = payloadCacheKey()
            val leaderStarted = CompletableDeferred<Unit>()
            val leader =
                async {
                    cache.getOrPut(key, nowMs = 1_000L) {
                        leaderStarted.complete(Unit)
                        awaitCancellation()
                    }
                }

            leaderStarted.await()
            var leaderCancelled = false
            var retryCalls = 0
            val follower =
                async {
                    cache.getOrPut(key, nowMs = 1_000L) {
                        assertTrue(leaderCancelled)
                        retryCalls += 1
                        successfulPayloadHealth(setOf(RelayUdpPayloadFamily.Ipv4))
                    }
                }

            yield()
            leaderCancelled = true
            leader.cancelAndJoin()

            assertEquals(1, retryCalls)
            assertEquals(RelayUdpPayloadHealthVerdict.Acknowledged.wireValue, follower.await()?.overallVerdict)
        }

    private fun payloadCacheKey(): RelayUdpPayloadHealthCacheKey =
        RelayUdpPayloadHealthCacheKey(
            endpoint = RelayProbeEndpoint("127.0.0.1", 1080),
            underlayGeneration = 1L,
            serviceStartedAt = 100L,
            families = setOf(RelayUdpPayloadFamily.Ipv4),
        )

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
        serviceStartedAt: Long = 100L,
        staleAwgTelemetry: Boolean = false,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            status = AppStatus.Running,
            mode = Mode.VPN,
            serviceStartedAt = serviceStartedAt,
            relayTelemetry =
                NativeRuntimeSnapshot(
                    source = "relay",
                    state = "running",
                    health = "healthy",
                    listenerAddress = listenerAddress,
                    protocolKind = protocolKind,
                ),
            awgTelemetry =
                if (staleAwgTelemetry) {
                    NativeRuntimeSnapshot(source = "amneziawg", state = "running", health = "healthy")
                } else {
                    NativeRuntimeSnapshot.idle(source = "amneziawg")
                },
            awgTelemetryStatus =
                if (staleAwgTelemetry) {
                    RuntimeTelemetryStatus(state = RuntimeTelemetryState.Snapshot)
                } else {
                    RuntimeTelemetryStatus.NoData
                },
        )

    private fun RemoteDeviceAcceptanceReport.step(id: String): RemoteDeviceAcceptanceStep = steps.first { it.id == id }

    private companion object {
        val Device = RemoteDeviceAcceptanceDevice("SM-S928B", "XSG", 35, "arm64-v8a")
        const val FixtureConnectivityProbeUrl = "https://acceptance.invalid/connectivity"
        const val FixtureIpv4ProbeUrl = "https://acceptance-ipv4.invalid/generate_204"
        const val FixtureIpv6ProbeUrl = "https://acceptance-ipv6.invalid/generate_204"
        val FixtureRemoteAcceptanceProbeTargets =
            RemoteAcceptanceProbeTargets(
                connectivityUrl = FixtureConnectivityProbeUrl,
                ipv4Url = FixtureIpv4ProbeUrl,
                ipv6Url = FixtureIpv6ProbeUrl,
            )
        val DualStackUnderlay =
            NetworkPathObservation(
                generation = 1L,
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
