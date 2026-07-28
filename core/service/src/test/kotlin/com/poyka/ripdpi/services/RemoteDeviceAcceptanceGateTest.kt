package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindVlessReality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceAcceptanceGateTest {
    @Test
    fun `screen off survival requires the configured dwell`() {
        val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L)

        assertFalse(tracker.observe(nowMs = 1_000L, running = true, interactive = false))
        assertFalse(tracker.observe(nowMs = 2_000L, running = true, interactive = true))
        assertFalse(tracker.observe(nowMs = 3_000L, running = true, interactive = false))
        assertTrue(tracker.observe(nowMs = 303_000L, running = true, interactive = true))
    }

    @Test
    fun `screen off survival does not pass when VPN stops during dwell`() {
        val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L)

        assertFalse(tracker.observe(nowMs = 1_000L, running = true, interactive = false))
        assertFalse(tracker.observe(nowMs = 200_000L, running = false, interactive = false))
        assertFalse(tracker.observe(nowMs = 400_000L, running = true, interactive = true))
    }

    @Test
    fun `successful baseline passes data plane and leaves guided checks incomplete`() {
        val report = buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence())

        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.status)
        assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.acceptanceDataPlaneStatus())
        assertTrue(report.steps.take(6).all { it.status == RemoteDeviceAcceptanceStatus.Pass })
        assertTrue(
            report.steps
                .drop(6)
                .take(3)
                .all { it.status == RemoteDeviceAcceptanceStatus.Incomplete },
        )
        assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.steps.last().status)
        assertTrue(report.steps.filter { it.status == RemoteDeviceAcceptanceStatus.Pass }.all { it.errorClass == null })
    }

    @Test
    fun `DNS response failure proves association but fails UDP DNS`() {
        val evidence =
            successfulEvidence().copy(
                probe =
                    successfulProbe().copy(
                        udpSucceeded = false,
                        udpFailure = RelayProbeFailure.DnsResponse.wireValue,
                    ),
            )

        val report = buildRemoteDeviceAcceptanceBaseline(Device, evidence)

        assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.step("socks_udp_associate").status)
        assertEquals(RemoteDeviceAcceptanceStatus.Fail, report.step("dns_udp").status)
        assertEquals(RelayProbeFailure.DnsResponse.wireValue, report.step("dns_udp").errorClass)
    }

    @Test
    fun `post-open IO classification still proves UDP association`() {
        val evidence =
            successfulEvidence().copy(
                probe =
                    successfulProbe().copy(
                        udpAssociationOpened = true,
                        udpSucceeded = false,
                        udpFailure = RelayProbeFailure.UdpIo.wireValue,
                    ),
            )

        val report = buildRemoteDeviceAcceptanceBaseline(Device, evidence)

        assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.step("socks_udp_associate").status)
        assertEquals(RemoteDeviceAcceptanceStatus.Fail, report.step("dns_udp").status)
    }

    @Test
    fun `payload floor passes while higher size loss remains inconclusive evidence`() {
        val evidence =
            successfulEvidence().copy(
                payloadHealth =
                    RelayUdpPayloadHealthEvidence(
                        overallVerdict = RelayUdpPayloadHealthVerdict.InconclusiveSizeCorrelatedLoss.wireValue,
                        families =
                            listOf(
                                RelayUdpPayloadFamilyHealthEvidence(
                                    family = "ipv4",
                                    controlBefore = RelayUdpPayloadControlOutcome.Acknowledged.wireValue,
                                    controlAfter = RelayUdpPayloadControlOutcome.Acknowledged.wireValue,
                                    maxAcknowledgedPayloadBytes = 1_232,
                                    firstRepeatedFailedPayloadBytes = 1_400,
                                    attemptCount = 8,
                                    verdict = RelayUdpPayloadHealthVerdict.InconclusiveSizeCorrelatedLoss.wireValue,
                                ),
                            ),
                    ),
            )

        val report = buildRemoteDeviceAcceptanceBaseline(Device, evidence)

        assertEquals(RemoteDeviceAcceptanceStatus.Pass, report.step(StepRelayUdpPayload).status)
        assertNull(report.step(StepRelayUdpPayload).errorClass)
        assertEquals(
            RelayUdpPayloadHealthVerdict.InconclusiveSizeCorrelatedLoss.wireValue,
            report.pathHealth?.overallVerdict,
        )
    }

    @Test
    fun `unavailable and inconclusive payload health leave payload step incomplete`() {
        val unavailable = buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence().copy(payloadHealth = null))
        val controlFailed =
            buildRemoteDeviceAcceptanceBaseline(
                Device,
                successfulEvidence().copy(
                    payloadHealth =
                        RelayUdpPayloadHealthEvidence(
                            overallVerdict = RelayUdpPayloadHealthVerdict.InconclusiveControlFailed.wireValue,
                            families =
                                listOf(
                                    RelayUdpPayloadFamilyHealthEvidence(
                                        family = "ipv4",
                                        controlBefore = RelayUdpPayloadControlOutcome.Failed.wireValue,
                                        controlAfter = RelayUdpPayloadControlOutcome.NotAttempted.wireValue,
                                        maxAcknowledgedPayloadBytes = null,
                                        firstRepeatedFailedPayloadBytes = null,
                                        attemptCount = 1,
                                        verdict = RelayUdpPayloadHealthVerdict.InconclusiveControlFailed.wireValue,
                                    ),
                                ),
                        ),
                ),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, unavailable.step(StepRelayUdpPayload).status)
        assertEquals(ErrorPayloadHealthUnavailable, unavailable.step(StepRelayUdpPayload).errorClass)
        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, unavailable.acceptanceDataPlaneStatus())
        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, controlFailed.step(StepRelayUdpPayload).status)
        assertEquals(
            RelayUdpPayloadHealthVerdict.InconclusiveControlFailed.wireValue,
            controlFailed.step(StepRelayUdpPayload).errorClass,
        )
        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, controlFailed.acceptanceDataPlaneStatus())
    }

    @Test
    fun `data plane status preserves fail incomplete and pass`() {
        val failed =
            buildRemoteDeviceAcceptanceBaseline(
                Device,
                successfulEvidence().copy(
                    probe =
                        successfulProbe().copy(
                            udpSucceeded = false,
                            udpFailure = RelayProbeFailure.DnsResponse.wireValue,
                        ),
                ),
            )
        val incomplete = buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence().copy(payloadHealth = null))

        assertEquals(RemoteDeviceAcceptanceStatus.Fail, failed.acceptanceDataPlaneStatus())
        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, incomplete.acceptanceDataPlaneStatus())
        assertEquals(
            RemoteDeviceAcceptanceStatus.Pass,
            buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence()).acceptanceDataPlaneStatus(),
        )
    }

    @Test
    fun `guided result keeps inconclusive post action probe incomplete`() {
        assertEquals(
            GuidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Pass, null),
            guidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Pass),
        )
        assertEquals(
            GuidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Incomplete, ErrorPostActionProbeInconclusive),
            guidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Incomplete),
        )
        assertEquals(
            GuidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Fail, ErrorPostActionProbe),
            guidedDataPlaneResult(RemoteDeviceAcceptanceStatus.Fail),
        )
    }

    @Test
    fun `wrong transport fails closed without probe details`() {
        val report =
            buildRemoteDeviceAcceptanceBaseline(
                Device,
                successfulEvidence().copy(transportKind = "hysteria2", probe = null),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Fail, report.status)
        assertEquals("transport_mismatch", report.step("reality_tcp").errorClass)
        assertEquals("transport_mismatch", report.step("socks_udp_associate").errorClass)
        assertEquals("transport_mismatch", report.step("dns_udp").errorClass)
    }

    @Test
    fun `redacted report contains only approved device transport and step fields`() {
        val report = buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence())

        val rendered = renderRemoteDeviceAcceptanceReport(report)

        assertTrue(rendered.contains("ripdpi_remote_device_acceptance_v1"))
        assertTrue(rendered.contains("SM-S928B"))
        assertTrue(rendered.contains("XSG"))
        assertTrue(rendered.contains(RelayKindVlessReality))
        assertTrue(rendered.contains("relay_egress_udp_payload"))
        assertTrue(rendered.contains("acknowledged_application_payload_ceiling"))
        assertTrue(rendered.contains("not_quantified_variable_encapsulation"))
        assertTrue(rendered.contains("effectivePathMtuBytes"))
        assertTrue(rendered.contains("mtuBand"))
        assertTrue(rendered.contains("nat64Reachability"))
        assertTrue(rendered.contains("unknown"))
        assertTrue(rendered.contains("durationMs"))
        assertFalse(rendered.contains("profile", ignoreCase = true))
        assertFalse(rendered.contains("credential", ignoreCase = true))
        assertFalse(rendered.contains("endpoint", ignoreCase = true))
        assertFalse(rendered.contains("uuid", ignoreCase = true))
        assertFalse(rendered.contains("network-hash", ignoreCase = true))
        assertFalse(rendered.contains("2001:db8", ignoreCase = true))
        assertFalse(rendered.contains("nat64Prefix", ignoreCase = true))
        assertFalse(rendered.contains("networkHandle", ignoreCase = true))
        assertNull(report.step("reality_tcp").errorClass)
    }

    private fun RemoteDeviceAcceptanceReport.step(id: String): RemoteDeviceAcceptanceStep = steps.first { it.id == id }

    private fun successfulEvidence(): AcceptanceBaselineEvidence =
        AcceptanceBaselineEvidence(
            serviceRunning = true,
            transportKind = RelayKindVlessReality,
            listenerAvailable = true,
            probe = successfulProbe(),
            ipv4Probe = successfulProbe(),
            ipv6Probe = successfulProbe(),
            payloadHealth = successfulPayloadHealth(),
            underlay =
                RemoteDeviceAcceptanceUnderlay(
                    mtuBand = "standard",
                    hasIpv4Address = true,
                    hasIpv6Address = true,
                    hasIpv4DefaultRoute = true,
                    hasIpv6DefaultRoute = true,
                    hasIpv4Dns = true,
                    hasIpv6Dns = true,
                    nat64Advertised = true,
                ),
            directEgressObserved = false,
            durationMs = 42L,
        )

    private fun successfulProbe(): RelayCapabilityProbeEvidence =
        RelayCapabilityProbeEvidence(
            tcpSucceeded = true,
            tcpStatusCode = 204,
            tcpFailure = null,
            udpAssociationOpened = true,
            udpSucceeded = true,
            udpFailure = null,
            latencyMs = 42L,
        )

    private fun successfulPayloadHealth(): RelayUdpPayloadHealthEvidence =
        RelayUdpPayloadHealthEvidence(
            overallVerdict = RelayUdpPayloadHealthVerdict.Acknowledged.wireValue,
            families =
                listOf(
                    RelayUdpPayloadFamilyHealthEvidence(
                        family = "ipv4",
                        controlBefore = RelayUdpPayloadControlOutcome.Acknowledged.wireValue,
                        controlAfter = RelayUdpPayloadControlOutcome.Acknowledged.wireValue,
                        maxAcknowledgedPayloadBytes = 1_232,
                        firstRepeatedFailedPayloadBytes = null,
                        attemptCount = 7,
                        verdict = RelayUdpPayloadHealthVerdict.Acknowledged.wireValue,
                    ),
                ),
        )

    private companion object {
        val Device =
            RemoteDeviceAcceptanceDevice(
                model = "SM-S928B",
                csc = "XSG",
                api = 35,
                abi = "arm64-v8a",
            )
    }
}
