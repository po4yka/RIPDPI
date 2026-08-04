package com.poyka.ripdpi.failover

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleVlessRuntimeMonitorTest {
    @Test
    fun `publishes only VLESS Reality for a running VPN session`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
            }
            val monitor = buildMonitor(stateStore, settings)
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()

            assertEquals(RelayKindVlessReality, monitor.activeTransport.value?.protocolKind)

            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            assertNull(monitor.activeTransport.value)
        }

    @Test
    fun `does not publish a user transport for non VPN modes`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
            }
            val monitor = buildMonitor(stateStore, settings)
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()

            assertNull(monitor.activeTransport.value)
        }

    @Test
    fun `does not present a diagnostic relay as the user transport`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindHysteria2)
            }
            val monitor = buildMonitor(stateStore, settings)
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()

            assertNull(monitor.activeTransport.value)
        }

    @Test
    fun `startup failure before readiness retries once with first embedded Hysteria2`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
                setSimpleFailoverAwgProfileId("stale-awg")
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles =
                        listOf(
                            RelayProfileRecord(
                                id = "$SeededHysteriaProfileId-2",
                                kind = RelayKindHysteria2,
                            ),
                            RelayProfileRecord(
                                id = SeededHysteriaProfileId,
                                kind = RelayKindHysteria2,
                            ),
                        ),
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("readiness failed"))
            runCurrent()

            val recovered = settings.snapshot()
            assertEquals(listOf(Mode.VPN), controller.startCalls)
            assertEquals(RelayKindHysteria2, recovered.relayKind)
            assertEquals(SeededHysteriaProfileId, recovered.relayProfileId)
            assertEquals("", recovered.simpleFailoverAwgProfileId)

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            assertEquals(RelayKindHysteria2, monitor.activeTransport.value?.protocolKind)

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("fallback failed"))
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            assertEquals(listOf(Mode.VPN), controller.startCalls)
        }

    @Test
    fun `Hysteria2 failure before readiness retries once with a resolved AWG request`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            val awgRequest = sampleAwgRequest()
            val awgSelection = RecordingAwgFallbackSelection(awgRequest)
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles =
                        listOf(
                            RelayProfileRecord(
                                id = SeededHysteriaProfileId,
                                kind = RelayKindHysteria2,
                            ),
                        ),
                    awgSelection = awgSelection,
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("Hysteria2 readiness failed"))
            runCurrent()

            val recovered = settings.snapshot()
            assertEquals(listOf(Mode.VPN, Mode.VPN), controller.startCalls)
            assertEquals(false, recovered.relayEnabled)
            assertEquals(awgRequest.profileId, recovered.simpleFailoverAwgProfileId)
            assertEquals(listOf(awgRequest), awgSelection.selectedRequests)

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            assertEquals("amneziawg", monitor.activeTransport.value?.protocolKind)

            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("AWG readiness failed"))
            runCurrent()
            assertEquals(listOf(Mode.VPN, Mode.VPN), controller.startCalls)
        }

    @Test
    fun `Hysteria2 startup failure without AWG remains halted`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles =
                        listOf(
                            RelayProfileRecord(
                                id = SeededHysteriaProfileId,
                                kind = RelayKindHysteria2,
                            ),
                        ),
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("Hysteria2 readiness failed"))
            runCurrent()

            assertEquals(listOf(Mode.VPN), controller.startCalls)
            assertEquals(true, settings.snapshot().relayEnabled)
            assertEquals(RelayKindHysteria2, settings.snapshot().relayKind)
        }

    @Test
    fun `failure after VPN readiness does not switch away from VLESS`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles =
                        listOf(
                            RelayProfileRecord(
                                id = SeededHysteriaProfileId,
                                kind = RelayKindHysteria2,
                            ),
                        ),
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()
            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("runtime failed"))
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            assertEquals(emptyList<Mode>(), controller.startCalls)
            assertEquals(RelayKindVlessReality, settings.snapshot().relayKind)
        }

    @Test
    fun `startup failure without embedded Hysteria2 remains halted`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor = buildMonitor(stateStore, settings, controller = controller)
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.TunnelEstablishmentFailed)
            runCurrent()

            assertEquals(emptyList<Mode>(), controller.startCalls)
            assertEquals(RelayKindVlessReality, settings.snapshot().relayKind)
        }

    private fun buildMonitor(
        stateStore: DefaultServiceStateStore,
        settings: FakeAppSettingsRepository,
        profiles: List<RelayProfileRecord> = emptyList(),
        awgSelection: RecordingAwgFallbackSelection = RecordingAwgFallbackSelection(),
        controller: RecordingServiceController = RecordingServiceController(),
    ): SimpleVlessRuntimeMonitor =
        SimpleVlessRuntimeMonitor(
            serviceStateStore = stateStore,
            settingsRepository = settings,
            relayProfileStore = MonitorRelayProfileStore(profiles),
            awgFallbackSelection = awgSelection,
            serviceController = controller,
        )

    private fun sampleAwgRequest(): AwgActivationRequest =
        AwgActivationRequest(
            profileId = "awg-fallback",
            privateKey = "private",
            peerPublicKey = "peer",
            endpointHost = "198.51.100.10",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
        )

    private companion object {
        const val SeededVlessProfileId = "simple-seed-VlessReality"
        const val SeededHysteriaProfileId = "simple-seed-Hysteria2"
    }
}

private class RecordingAwgFallbackSelection(
    private val request: AwgActivationRequest? = null,
) : SimpleAwgFallbackSelection {
    val selectedRequests = mutableListOf<AwgActivationRequest>()

    override suspend fun firstAvailable(): AwgActivationRequest? = request

    override fun select(request: AwgActivationRequest) {
        selectedRequests += request
    }
}

private class MonitorRelayProfileStore(
    profiles: List<RelayProfileRecord>,
) : RelayProfileStore {
    private val records = profiles.associateBy(RelayProfileRecord::id).toMutableMap()

    override suspend fun load(profileId: String): RelayProfileRecord? = records[profileId]

    override suspend fun list(): List<RelayProfileRecord> = records.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        records[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}

private class RecordingServiceController : ServiceController {
    val startCalls = mutableListOf<Mode>()

    override fun start(mode: Mode): ServiceStartResult {
        startCalls += mode
        return ServiceStartResult.Accepted(mode)
    }

    override fun stop() = Unit
}
