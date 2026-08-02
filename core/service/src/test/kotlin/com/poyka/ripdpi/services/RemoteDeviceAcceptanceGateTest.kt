package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalOutcome
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun telemetry(
    tunnelTxPackets: Long = 0L,
    tunnelTxBytes: Long = 0L,
    tunnelRxPackets: Long = 0L,
    tunnelRxBytes: Long = 0L,
    relayRxPackets: Long = 0L,
): ServiceTelemetrySnapshot =
    ServiceTelemetrySnapshot(
        tunnelStats =
            TunnelStats(
                txPackets = tunnelTxPackets,
                txBytes = tunnelTxBytes,
                rxPackets = tunnelRxPackets,
                rxBytes = tunnelRxBytes,
            ),
        relayTelemetry =
            NativeRuntimeSnapshot(
                source = "relay",
                tunnelStats = TunnelStats(rxPackets = relayRxPackets),
            ),
    )

class RemoteScreenOffDwellTrackerTest {
    @Test
    fun `screen off survival passes after configured dwell with forwarding delta`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            assertEquals(
                RemoteScreenOffDwellObservation.None,
                tracker.observe(
                    elapsedNowMs = 1_000L,
                    observedAtMillis = 10L,
                    running = true,
                    interactive = false,
                    telemetry = telemetry(tunnelTxBytes = 100L),
                ),
            )
            assertEquals(
                RemoteScreenOffDwellObservation.ReadyForScreenOffProbe(durationMs = 300_000L),
                tracker.observe(
                    elapsedNowMs = 301_000L,
                    observedAtMillis = 310L,
                    running = true,
                    interactive = false,
                    telemetry = telemetry(tunnelTxBytes = 100L),
                ),
            )
            assertEquals(
                RemoteScreenOffDwellObservation.None,
                tracker.recordScreenOffProbe(
                    elapsedNowMs = 301_050L,
                    observedAtMillis = 315L,
                    telemetry = telemetry(tunnelTxBytes = 132L, relayRxPackets = 2L),
                    countersBefore = telemetry(tunnelTxBytes = 100L).toRuntimeDataPlaneCounters(),
                    screenOffProbePassed = true,
                    telemetryFresh = true,
                ),
            )
            assertEquals(
                RemoteScreenOffDwellObservation.ReadyForAfterWakeProbe(durationMs = 300_100L),
                tracker.observe(
                    elapsedNowMs = 301_100L,
                    observedAtMillis = 320L,
                    running = true,
                    interactive = true,
                    telemetry = telemetry(tunnelTxBytes = 132L, relayRxPackets = 2L),
                ),
            )

            val result =
                tracker.completeAfterWake(
                    elapsedNowMs = 301_150L,
                    observedAtMillis = 325L,
                    telemetry = telemetry(tunnelTxBytes = 160L, relayRxPackets = 3L),
                    countersBefore = telemetry(tunnelTxBytes = 132L, relayRxPackets = 2L).toRuntimeDataPlaneCounters(),
                    afterWakeProbePassed = true,
                    telemetryFresh = true,
                )

            assertEquals(RemoteDeviceAcceptanceStatus.Pass, result.status)
            assertNull(result.errorClass)
            assertTrue(result.counterDelta.hasForwarding)
            assertEquals(3, events.size)
            val started = events[0] as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted, started.phase)
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Pending, started.outcome)
            val screenOffProbe = events[1] as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe, screenOffProbe.phase)
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Passed, screenOffProbe.outcome)
            val completed = events[2] as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalPhase.AfterWake, completed.phase)
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Passed, completed.outcome)
            assertEquals(28L, completed.counterDelta?.tunnelBytes)
            assertEquals(1L, completed.counterDelta?.nativePackets)
        }

    @Test
    fun `screen off survival fails when no forwarding delta is observed after wake`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelRxBytes = 100L),
            )
            val ready =
                tracker.observe(
                    elapsedNowMs = 301_000L,
                    observedAtMillis = 310L,
                    running = true,
                    interactive = false,
                    telemetry = telemetry(tunnelRxBytes = 100L),
                )
            assertEquals(RemoteScreenOffDwellObservation.ReadyForScreenOffProbe(300_000L), ready)

            val result =
                tracker.recordScreenOffProbe(
                    elapsedNowMs = 301_050L,
                    observedAtMillis = 315L,
                    telemetry = telemetry(tunnelRxBytes = 100L),
                    countersBefore = telemetry(tunnelRxBytes = 100L).toRuntimeDataPlaneCounters(),
                    screenOffProbePassed = true,
                    telemetryFresh = true,
                ) as RemoteScreenOffDwellObservation.Completed

            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, result.result.status)
            assertEquals(ErrorScreenOffNoDataPlaneDelta, result.result.errorClass)
            val completed = events.last() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe, completed.phase)
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive, completed.outcome)
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.NoDataPlaneDelta, completed.reason)
            assertFalse(result.result.counterDelta.hasForwarding)
        }

    @Test
    fun `screen off survival is inconclusive when after-wake probe is missing`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelRxBytes = 100L),
            )
            tracker.observe(
                elapsedNowMs = 301_000L,
                observedAtMillis = 310L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelRxBytes = 100L),
            )
            val woke =
                tracker.observe(
                    elapsedNowMs = 301_100L,
                    observedAtMillis = 320L,
                    running = true,
                    interactive = true,
                    telemetry = telemetry(tunnelRxBytes = 132L),
                ) as RemoteScreenOffDwellObservation.Completed

            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, woke.result.status)
            val completed = events.last() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive, completed.outcome)
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.ScreenOffProbeMissing, completed.reason)
        }

    @Test
    fun `screen off survival fails when VPN stops during dwell`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(),
            )
            val stopped =
                tracker.observe(
                    elapsedNowMs = 200_000L,
                    observedAtMillis = 20L,
                    running = false,
                    interactive = false,
                    telemetry = telemetry(),
                ) as RemoteScreenOffDwellObservation.Completed

            assertEquals(RemoteDeviceAcceptanceStatus.Fail, stopped.result.status)
            assertEquals(ErrorScreenOffServiceStopped, stopped.result.errorClass)
            val completed = events.last() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Failed, completed.outcome)
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.ServiceStopped, completed.reason)
        }

    @Test
    fun `screen off start evidence is recorded before completion and stays privacy safe`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelTxBytes = 7L),
            )

            assertEquals(1, events.size)
            val started = events.single() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted, started.phase)
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Pending, started.outcome)
            assertEquals(7L, started.countersBefore.tunnelTxBytes)
            assertNull(started.countersAfter)
            listOf("ssid", "bssid", "serial", "endpoint", "profile", "uuid").forEach { forbidden ->
                assertFalse(started.toString().contains(forbidden, ignoreCase = true))
            }
        }

    @Test
    fun `screen off cancellation records inconclusive evidence`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(),
            )
            val cancelled =
                tracker.cancel(
                    elapsedNowMs = 2_000L,
                    observedAtMillis = 20L,
                    telemetry = telemetry(),
                )

            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, cancelled?.status)
            val completed = events.last() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive, completed.outcome)
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.Cancelled, completed.reason)
        }

    @Test
    fun `short screen off wake records inconclusive result without passing the gate`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(),
            )
            val wokeEarly =
                tracker.observe(
                    elapsedNowMs = 2_000L,
                    observedAtMillis = 20L,
                    running = true,
                    interactive = true,
                    telemetry = telemetry(),
                ) as RemoteScreenOffDwellObservation.Completed

            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, wokeEarly.result.status)
            val completed = events.last() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive, completed.outcome)
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.TooShort, completed.reason)
        }

    @Test
    fun `ambient dwell traffic does not satisfy the screen-off probe delta`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelTxBytes = 100L),
            )
            tracker.observe(
                elapsedNowMs = 301_000L,
                observedAtMillis = 310L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelTxBytes = 500L),
            )
            val result =
                tracker.recordScreenOffProbe(
                    elapsedNowMs = 301_050L,
                    observedAtMillis = 315L,
                    telemetry = telemetry(tunnelTxBytes = 500L),
                    countersBefore = telemetry(tunnelTxBytes = 500L).toRuntimeDataPlaneCounters(),
                    screenOffProbePassed = true,
                    telemetryFresh = true,
                ) as RemoteScreenOffDwellObservation.Completed

            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, result.result.status)
            assertFalse(result.result.counterDelta.hasForwarding)
            val completed = events.last() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.NoDataPlaneDelta, completed.reason)
        }

    @Test
    fun `successful probe with stale telemetry stays inconclusive`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelTxBytes = 100L),
            )
            tracker.observe(
                elapsedNowMs = 301_000L,
                observedAtMillis = 310L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelTxBytes = 100L),
            )
            val result =
                tracker.recordScreenOffProbe(
                    elapsedNowMs = 301_050L,
                    observedAtMillis = 315L,
                    telemetry = telemetry(tunnelTxBytes = 140L),
                    countersBefore = telemetry(tunnelTxBytes = 100L).toRuntimeDataPlaneCounters(),
                    screenOffProbePassed = true,
                    telemetryFresh = false,
                ) as RemoteScreenOffDwellObservation.Completed

            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, result.result.status)
            assertTrue(result.result.counterDelta.hasForwarding)
            val completed = events.last() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.TelemetryStale, completed.reason)
        }

    @Test
    fun `screen state changes during the screen-off probe record an inconclusive terminal event`() =
        runTest {
            val events = mutableListOf<DeviceRuntimeEvidence>()
            val tracker = RemoteScreenOffDwellTracker(minimumDwellMs = 300_000L) { events.add(it) }

            tracker.observe(
                elapsedNowMs = 1_000L,
                observedAtMillis = 10L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelTxBytes = 100L),
            )
            tracker.observe(
                elapsedNowMs = 301_000L,
                observedAtMillis = 310L,
                running = true,
                interactive = false,
                telemetry = telemetry(tunnelTxBytes = 100L),
            )
            val result =
                tracker.interruptScreenOffProbe(
                    elapsedNowMs = 301_050L,
                    observedAtMillis = 315L,
                    telemetry = telemetry(tunnelTxBytes = 100L),
                    countersBefore = telemetry(tunnelTxBytes = 100L).toRuntimeDataPlaneCounters(),
                    reason = DeviceRuntimeBackgroundSurvivalReason.ScreenStateChanged,
                    status = RemoteDeviceAcceptanceStatus.Incomplete,
                    errorClass = null,
                ) as RemoteScreenOffDwellObservation.Completed

            assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, result.result.status)
            val completed = events.last() as DeviceRuntimeEvidence.BackgroundSurvival
            assertEquals(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe, completed.phase)
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.ScreenStateChanged, completed.reason)
        }
}

class RemoteDeviceAcceptanceGateTest {
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
    fun `path policy assessment distinguishes observed consistency from missing evidence`() {
        val expectations =
            listOf(
                Triple(
                    AcceptancePathPolicyAssessment.Consistent,
                    RemoteDeviceAcceptanceStatus.Pass,
                    null,
                ),
                Triple(
                    AcceptancePathPolicyAssessment.Inconsistent,
                    RemoteDeviceAcceptanceStatus.Fail,
                    ErrorPathPolicyInconsistent,
                ),
                Triple(
                    AcceptancePathPolicyAssessment.Inconclusive,
                    RemoteDeviceAcceptanceStatus.Incomplete,
                    ErrorPathPolicyInconclusive,
                ),
                Triple(
                    AcceptancePathPolicyAssessment.Unavailable,
                    RemoteDeviceAcceptanceStatus.Incomplete,
                    ErrorPathPolicyContextUnavailable,
                ),
            )

        expectations.forEach { (assessment, expectedStatus, expectedError) ->
            val step =
                buildRemoteDeviceAcceptanceBaseline(
                    Device,
                    successfulEvidence().copy(pathPolicyAssessment = assessment),
                ).step(StepPathPolicyConsistency)

            assertEquals(expectedStatus, step.status)
            assertEquals(expectedError, step.errorClass)
        }
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
        assertFalse(failed.acceptanceDataPlanePassed())
        assertFalse(incomplete.acceptanceDataPlanePassed())
        assertEquals(
            RemoteDeviceAcceptanceStatus.Pass,
            buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence()).acceptanceDataPlaneStatus(),
        )
        assertTrue(buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence()).acceptanceDataPlanePassed())
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
    fun `unknown transport is inconclusive without probe details`() {
        val report =
            buildRemoteDeviceAcceptanceBaseline(
                Device,
                successfulEvidence().copy(
                    transportKind = "unknown",
                    probe = null,
                    probePlan = acceptanceTransportProbePlan("unknown"),
                ),
            )

        assertEquals(RemoteDeviceAcceptanceStatus.Incomplete, report.status)
        assertEquals(ErrorRuntimeContextUnavailable, report.step("reality_tcp").errorClass)
        assertEquals(ErrorRuntimeContextUnavailable, report.step("socks_udp_associate").errorClass)
        assertEquals(ErrorRuntimeContextUnavailable, report.step("dns_udp").errorClass)
    }

    @Test
    fun `redacted report contains only approved device transport and step fields`() {
        val report =
            buildRemoteDeviceAcceptanceBaseline(Device, successfulEvidence()).copy(
                uidPolicyQualification =
                    RemoteDeviceUidPolicyQualification(
                        kernelMajorMinorBand = "6.1",
                        unprivilegedBindToDevice = "supported",
                        uidPolicyEligible = true,
                        uidPolicyArmed = true,
                        uidResolvedCount = 4,
                        uidUnresolvedCount = 1,
                        policyDecisionDeniedTcpCount = 1,
                        policyDecisionDeniedUdpCount = 2,
                    ),
            )

        val rendered = renderRemoteDeviceAcceptanceReport(report)

        assertTrue(rendered.contains("ripdpi_remote_device_acceptance_v2"))
        assertTrue(rendered.contains("other_android_family"))
        assertTrue(rendered.contains("cscStatus"))
        assertTrue(rendered.contains("available"))
        assertTrue(rendered.contains("arm64"))
        assertFalse(rendered.contains("SM-S928B"))
        assertFalse(rendered.contains("XSG"))
        assertFalse(rendered.contains("arm64-v8a"))
        assertTrue(rendered.contains(RelayKindVlessReality))
        assertTrue(rendered.contains("relay_egress_udp_payload"))
        assertTrue(rendered.contains("acknowledged_application_payload_ceiling"))
        assertTrue(rendered.contains("not_quantified_variable_encapsulation"))
        assertTrue(rendered.contains("effectivePathMtuBytes"))
        assertTrue(rendered.contains("mtuBand"))
        assertTrue(rendered.contains("nat64Reachability"))
        assertTrue(rendered.contains("unknown"))
        assertTrue(rendered.contains("durationMs"))
        assertTrue(rendered.contains("kernelMajorMinorBand"))
        assertTrue(rendered.contains("6.1"))
        assertTrue(rendered.contains("unprivilegedBindToDevice"))
        assertTrue(rendered.contains("policyDecisionDeniedUdpCount"))
        assertFalse(rendered.contains("uidPolicyDenied"))
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
            pathPolicyAssessment = AcceptancePathPolicyAssessment.Consistent,
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
