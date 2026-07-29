package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteDeviceAcceptanceGateLifecycleTest {
    @Test
    fun `cancelled gate run persists cleanup under real cancellation`() =
        runTest {
            val writer = RecordingRemoteDeviceAcceptanceEvidenceWriter()
            val serviceState = runningServiceState(updatedAt = 1L)
            val screen = MutableRemoteAcceptanceScreenStateObserver(interactive = false)
            val gate = gate(serviceState = serviceState, screen = screen, writer = writer)

            gate.start(backgroundScope)
            runCurrent()
            gate.start(backgroundScope)
            runCurrent()

            assertTrue(
                writer.events.any { event ->
                    event.reason == DeviceRuntimeBackgroundSurvivalReason.Cancelled
                },
            )
        }

    @Test
    fun `cancelled gate run without active dwell persists run cancellation`() =
        runTest {
            val writer = RecordingRemoteDeviceAcceptanceEvidenceWriter()
            val serviceState = runningServiceState(updatedAt = 1L)
            val screen = MutableRemoteAcceptanceScreenStateObserver(interactive = true)
            val gate = gate(serviceState = serviceState, screen = screen, writer = writer)

            gate.start(backgroundScope)
            runCurrent()
            gate.start(backgroundScope)
            runCurrent()

            assertEquals(1, writer.cancelledRuns.size)
        }

    @Test
    fun `post-await service restart records failure instead of pass`() =
        runTest {
            val writer = RecordingRemoteDeviceAcceptanceEvidenceWriter()
            val serviceState = runningServiceState(updatedAt = 1L, serviceStartedAt = 10L)
            val screen = MutableRemoteAcceptanceScreenStateObserver(interactive = false)
            val gate = gate(serviceState = serviceState, screen = screen, writer = writer)

            gate.start(backgroundScope)
            runCurrent()
            serviceState.updateTelemetry(runningRealitySnapshot(updatedAt = 2L, serviceStartedAt = 10L))
            runCurrent()
            serviceState.updateTelemetry(
                runningRealitySnapshot(
                    updatedAt = 3L,
                    serviceStartedAt = 20L,
                    restartCount = 1,
                    tunnelTxBytes = 64L,
                ),
            )
            runCurrent()

            val terminal = writer.events.last()
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.ServiceRestarted, terminal.reason)
            assertFalse(writer.events.any { it.reason == null && it.counterDelta?.hasForwarding == true })
        }

    @Test
    fun `post-await screen transition records inconclusive instead of pass`() =
        runTest {
            val writer = RecordingRemoteDeviceAcceptanceEvidenceWriter()
            val serviceState = runningServiceState(updatedAt = 1L)
            val screen = MutableRemoteAcceptanceScreenStateObserver(interactive = false)
            val gate = gate(serviceState = serviceState, screen = screen, writer = writer)

            gate.start(backgroundScope)
            runCurrent()
            serviceState.updateTelemetry(runningRealitySnapshot(updatedAt = 2L))
            runCurrent()
            screen.setInteractive(true)
            serviceState.updateTelemetry(runningRealitySnapshot(updatedAt = 3L, tunnelTxBytes = 64L))
            runCurrent()

            val terminal = writer.events.last()
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.ScreenStateChanged, terminal.reason)
            assertFalse(writer.events.any { it.reason == null && it.counterDelta?.hasForwarding == true })
        }

    @Test
    fun `after-wake state change after baseline records terminal instead of returning`() =
        runTest {
            val writer = RecordingRemoteDeviceAcceptanceEvidenceWriter()
            val serviceState = runningServiceState(updatedAt = 1L)
            val screen = MutableRemoteAcceptanceScreenStateObserver(interactive = false)
            val gate =
                gate(
                    serviceState = serviceState,
                    screen = screen,
                    writer = writer,
                    mutateWhen = { screen.isInteractive.value },
                    onTcpProbe = { screen.setInteractive(false) },
                )

            gate.start(backgroundScope)
            runCurrent()
            serviceState.updateTelemetry(runningRealitySnapshot(updatedAt = 2L))
            runCurrent()
            serviceState.updateTelemetry(runningRealitySnapshot(updatedAt = 3L, tunnelTxBytes = 64L))
            runCurrent()
            screen.setInteractive(true)
            runCurrent()

            val terminal = writer.events.last()
            assertEquals(DeviceRuntimeBackgroundSurvivalPhase.AfterWake, terminal.phase)
            assertEquals(DeviceRuntimeBackgroundSurvivalReason.ScreenStateChanged, terminal.reason)
            assertFalse(
                writer.events.any { event ->
                    event.phase == DeviceRuntimeBackgroundSurvivalPhase.AfterWake &&
                        event.reason == null
                },
            )
        }

    @Test
    fun `repeated observations after terminal do not start a second dwell in same run`() =
        runTest {
            val writer = RecordingRemoteDeviceAcceptanceEvidenceWriter()
            val serviceState = runningServiceState(updatedAt = 1L)
            val screen = MutableRemoteAcceptanceScreenStateObserver(interactive = false)
            val gate =
                gate(
                    serviceState = serviceState,
                    screen = screen,
                    writer = writer,
                    baselinePasses = false,
                )

            gate.start(backgroundScope)
            runCurrent()
            serviceState.updateTelemetry(runningRealitySnapshot(updatedAt = 2L))
            runCurrent()
            serviceState.updateTelemetry(runningRealitySnapshot(updatedAt = 3L, tunnelTxBytes = 64L))
            runCurrent()
            serviceState.updateTelemetry(runningRealitySnapshot(updatedAt = 4L, tunnelTxBytes = 96L))
            runCurrent()

            assertEquals(
                1,
                writer.events.count { it.phase == DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted },
            )
            assertTrue(writer.events.any { it.reason == DeviceRuntimeBackgroundSurvivalReason.PostActionProbeFailed })
        }

    @Test
    fun `fresh telemetry timeout covers the five second poll interval`() {
        assertTrue(RemoteAcceptanceTelemetryFreshTimeoutMs >= 6_000L)
    }

    private fun gate(
        serviceState: TestServiceStateStore,
        screen: MutableRemoteAcceptanceScreenStateObserver,
        writer: RecordingRemoteDeviceAcceptanceEvidenceWriter,
        baselinePasses: Boolean = true,
        mutateWhen: () -> Boolean = { false },
        onTcpProbe: () -> Unit = {},
    ): DefaultRemoteDeviceAcceptanceGate =
        DefaultRemoteDeviceAcceptanceGate(
            serviceStateStore = serviceState,
            screenStateObserver = screen,
            baselineProbe = baselineProbe(serviceState, baselinePasses, mutateWhen, onTcpProbe),
            networkFingerprintProvider =
                object : NetworkFingerprintProvider {
                    override fun capture() = null
                },
            evidenceWriter = writer,
            screenOffDwellMs = 0L,
            telemetryFreshTimeoutMs = 10L,
        )

    private fun baselineProbe(
        serviceState: TestServiceStateStore,
        baselinePasses: Boolean,
        mutateWhen: () -> Boolean,
        onTcpProbe: () -> Unit,
    ): RemoteDeviceAcceptanceBaselineProbe {
        var mutated = false
        return RemoteDeviceAcceptanceBaselineProbe(
            serviceStateStore = serviceState,
            relayCapabilityProbe =
                RelayCapabilityProbe(
                    tcpProbe =
                        RelayTcpProbe { _, _ ->
                            if (!mutated && mutateWhen()) {
                                mutated = true
                                onTcpProbe()
                            }
                            if (baselinePasses) {
                                RelayTcpProbeResult(succeeded = true, statusCode = 204)
                            } else {
                                RelayTcpProbeResult(succeeded = false, failure = RelayProbeFailure.TcpConnect)
                            }
                        },
                    udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.success() },
                    payloadHealthProbe =
                        RelayUdpPayloadHealthProbe { _, families ->
                            successfulPayloadHealth(families)
                        },
                ),
            underlayObservationProvider = FixedRemoteAcceptanceUnderlayProvider,
            deviceProvider = { Device },
            monotonicClock = { 1_000L },
        )
    }

    private fun runningServiceState(
        updatedAt: Long,
        serviceStartedAt: Long = 10L,
    ): TestServiceStateStore =
        TestServiceStateStore(AppStatus.Running to Mode.VPN).apply {
            updateTelemetry(runningRealitySnapshot(updatedAt = updatedAt, serviceStartedAt = serviceStartedAt))
        }

    private fun runningRealitySnapshot(
        updatedAt: Long,
        serviceStartedAt: Long = 10L,
        restartCount: Int = 0,
        tunnelTxBytes: Long = 0L,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            status = AppStatus.Running,
            mode = Mode.VPN,
            tunnelStats = TunnelStats(txBytes = tunnelTxBytes),
            relayTelemetry =
                NativeRuntimeSnapshot(
                    source = "relay",
                    state = "running",
                    health = "healthy",
                    listenerAddress = "127.0.0.1:1080",
                    protocolKind = RelayKindVlessReality,
                ),
            serviceStartedAt = serviceStartedAt,
            restartCount = restartCount,
            updatedAt = updatedAt,
        )

    private companion object {
        val Device = RemoteDeviceAcceptanceDevice("SM-S928B", "unavailable", 35, "arm64-v8a")
    }
}

private class MutableRemoteAcceptanceScreenStateObserver(
    interactive: Boolean,
) : ScreenStateObserver {
    private val interactiveState = MutableStateFlow(interactive)

    override val isInteractive: StateFlow<Boolean> = interactiveState.asStateFlow()

    fun setInteractive(interactive: Boolean) {
        interactiveState.value = interactive
    }
}

private object FixedRemoteAcceptanceUnderlayProvider : AuthoritativeVpnUnderlayObservationProvider {
    override val changes: StateFlow<Long> = MutableStateFlow(0L)

    override fun capture(): NetworkPathObservation =
        NetworkPathObservation(
            generation = 1L,
            addressFamilies = listOf("ipv4"),
            defaultRouteFamilies = listOf("ipv4"),
            dnsServerFamilies = listOf("ipv4"),
        )
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

private class RecordingRemoteDeviceAcceptanceEvidenceWriter : RemoteDeviceAcceptanceEvidenceWriter {
    val begunRuns = mutableListOf<String>()
    val events = mutableListOf<DeviceRuntimeEvidence.BackgroundSurvival>()
    val cancelledRuns = mutableListOf<String>()

    override suspend fun beginRun(
        runGeneration: String,
        observedAtMillis: Long,
    ) {
        begunRuns += runGeneration
    }

    override suspend fun record(
        runGeneration: String,
        event: DeviceRuntimeEvidence.BackgroundSurvival,
    ) {
        events += event
    }

    override suspend fun cancelRun(
        runGeneration: String,
        observedAtMillis: Long,
    ) {
        cancelledRuns += runGeneration
    }
}
