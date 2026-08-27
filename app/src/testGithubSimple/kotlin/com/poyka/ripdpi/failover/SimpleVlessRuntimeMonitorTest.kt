package com.poyka.ripdpi.failover

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceIntentArbiter
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.StartupFallbackController
import com.poyka.ripdpi.services.StartupFallbackDispatchResult
import com.poyka.ripdpi.services.StartupFallbackLease
import com.poyka.ripdpi.services.TransportFailoverTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleVlessRuntimeMonitorTest {
    @Test
    fun `suspended explicit preparation cannot clear newer standalone profile`() =
        runTest {
            val backing = FakeAppSettingsRepository()
            val updateStarted = CompletableDeferred<Unit>()
            val releaseUpdate = CompletableDeferred<Unit>()
            val settings =
                object : AppSettingsRepository by backing {
                    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
                        updateStarted.complete(Unit)
                        releaseUpdate.await()
                        backing.update(transform)
                    }
                }
            val boot = TestAwgBootSelection()
            val selection =
                SimpleAwgEgressSelection(
                    AwgProfileRepository(FakeAwgProfileDao(), FakeAwgCredentialStore()),
                    settings,
                    boot,
                )
            val monitor = buildMonitor(DefaultServiceStateStore(), settings, awgSelection = selection)
            val arbiter = ServiceIntentArbiter()
            val generation = arbiter.userStart(arbiter::captureExplicitUserIntentGeneration) { true }
            val prepare = launch { monitor.prepare(Mode.VPN, arbiter.explicitUserStartGuard(generation)) }
            updateStarted.await()
            arbiter.userStart({ boot.setActiveAwgProfileId("awg-newer") }) { true }
            releaseUpdate.complete(Unit)
            prepare.join()
            assertEquals("awg-newer", boot.activeAwgProfileId())
            monitor.prepare(Mode.VPN, arbiter.explicitUserStartGuard(arbiter.captureExplicitUserIntentGeneration()))
            assertNull(boot.activeAwgProfileId())
        }

    @Test
    fun `superseded AWG lookup and rollback preserve newer standalone authority`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            val bootSelection = TestAwgBootSelection()
            val selection =
                SimpleAwgEgressSelection(
                    AwgProfileRepository(FakeAwgProfileDao(), FakeAwgCredentialStore()),
                    settings,
                    bootSelection,
                )
            val lookupStarted = CompletableDeferred<Unit>()
            val lookupResult = CompletableDeferred<AwgActivationRequest?>()
            val suspendedSelection =
                object : SimpleAwgFallbackSelection by selection {
                    override suspend fun firstAvailable(): AwgActivationRequest? {
                        lookupStarted.complete(Unit)
                        return lookupResult.await()
                    }
                }
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    awgSelection = suspendedSelection,
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("Hysteria2 readiness failed"))
            runCurrent()
            assertTrue(lookupStarted.isCompleted)

            bootSelection.setActiveAwgProfileId("awg-editor-newer")
            controller.acceptUserStart()
            lookupResult.complete(sampleAwgRequest())
            runCurrent()

            assertEquals("awg-editor-newer", bootSelection.activeAwgProfileId())
            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)
            assertNull(selection.selectedAwgEgress())
        }

    @Test
    fun `manual VPN preparation is not blocked by a stale fallback wait`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            settings.update {
                setEnableCmdSettings(true)
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            stateStore.setStatus(AppStatus.Reconnecting, Mode.VPN)
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("stale startup failure"))
            runCurrent()

            val preparation = launch { monitor.prepare(Mode.VPN, ServiceIntentArbiter().explicitUserStartGuard(0L)) }
            runCurrent()

            assertTrue(preparation.isCompleted)
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            assertEquals(emptyList<Mode>(), controller.startupFallbackStartCalls)
            assertEquals(RelayKindVlessReality, settings.snapshot().relayKind)
            assertFalse(settings.snapshot().enableCmdSettings)
        }

    @Test
    fun `halt timeout leaves the VLESS fallback transition retryable`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            settings.update {
                setEnableCmdSettings(true)
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            stateStore.setStatus(AppStatus.Reconnecting, Mode.VPN)
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("startup failure without halt"))
            runCurrent()

            advanceTimeBy(5_001L)
            runCurrent()
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS retry failed"))
            runCurrent()

            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(RelayKindHysteria2, settings.snapshot().relayKind)
            assertFalse(settings.snapshot().enableCmdSettings)
        }

    @Test
    fun `rejected Hysteria2 dispatch rolls back and keeps the fallback retryable`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller =
                RecordingServiceController(
                    startupFallbackResults =
                        mutableListOf(
                            ServiceStartResult.Rejected(
                                mode = Mode.VPN,
                                reason =
                                    ServiceStartRejectionReason.ForegroundServiceBlocked(
                                        "background start denied",
                                    ),
                            ),
                            ServiceStartResult.Accepted(Mode.VPN),
                        ),
                )
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()

            assertEquals(RelayKindVlessReality, settings.snapshot().relayKind)
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS retry failed"))
            runCurrent()

            assertEquals(listOf(Mode.VPN, Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(RelayKindHysteria2, settings.snapshot().relayKind)
        }

    @Test
    fun `missing Hysteria2 profile does not consume the VLESS fallback transition`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            val profiles = mutableListOf<RelayProfileRecord>()
            val relayCatalog = SimpleFailoverRelayCatalog { profiles.toList() }
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    relayCatalog = relayCatalog,
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()
            assertEquals(emptyList<Mode>(), controller.startupFallbackStartCalls)

            profiles += RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS retry failed"))
            runCurrent()

            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(RelayKindHysteria2, settings.snapshot().relayKind)
        }

    @Test
    fun `missing AWG profile does not consume the Hysteria2 fallback transition`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            val awgSelection = RecordingAwgFallbackSelection()
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    awgSelection = awgSelection,
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("Hysteria2 readiness failed"))
            runCurrent()
            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)

            val awgRequest = sampleAwgRequest()
            awgSelection.request = awgRequest
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("Hysteria2 retry failed"))
            runCurrent()

            assertEquals(listOf(Mode.VPN, Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(awgRequest.profileId, settings.snapshot().simpleFailoverAwgProfileId)
        }

    @Test
    fun `rejected AWG dispatch rolls back and keeps the fallback retryable`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller =
                RecordingServiceController(
                    startupFallbackResults =
                        mutableListOf(
                            ServiceStartResult.Accepted(Mode.VPN),
                            ServiceStartResult.Rejected(
                                mode = Mode.VPN,
                                reason =
                                    ServiceStartRejectionReason.ForegroundServiceBlocked(
                                        "background start denied",
                                    ),
                            ),
                            ServiceStartResult.Accepted(Mode.VPN),
                        ),
                )
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
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    awgSelection = awgSelection,
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("Hysteria2 readiness failed"))
            runCurrent()

            assertEquals(true, settings.snapshot().relayEnabled)
            assertEquals(RelayKindHysteria2, settings.snapshot().relayKind)
            assertNull(awgSelection.currentRequest)
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("Hysteria2 retry failed"))
            runCurrent()

            assertEquals(listOf(Mode.VPN, Mode.VPN, Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(false, settings.snapshot().relayEnabled)
            assertEquals(awgRequest.profileId, settings.snapshot().simpleFailoverAwgProfileId)
        }

    @Test
    fun `bind activates failure subscription before returning`() =
        runTest {
            val stateStore = SubscriptionAwareServiceStateStore()
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
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    controller = controller,
                )

            monitor.bind(backgroundScope)

            assertEquals(1, stateStore.eventSubscriberCount)
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("immediate startup failure"))
            runCurrent()
            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)
        }

    @Test
    fun `next explicit VPN attempt restores seeded VLESS after AWG fallback`() =
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

            assertEquals(false, settings.snapshot().relayEnabled)
            assertEquals(awgRequest.profileId, settings.snapshot().simpleFailoverAwgProfileId)
            assertEquals(listOf(Mode.VPN, Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(emptyList<Mode>(), controller.userStartCalls)
            assertEquals(emptyList<Mode>(), controller.failoverRestartCalls)

            monitor.prepare(Mode.VPN, ServiceIntentArbiter().explicitUserStartGuard(0L))

            val prepared = settings.snapshot()
            assertEquals(true, prepared.relayEnabled)
            assertEquals(RelayKindVlessReality, prepared.relayKind)
            assertEquals(SeededVlessProfileId, prepared.relayProfileId)
            assertEquals("", prepared.simpleFailoverAwgProfileId)
            assertEquals(1, awgSelection.clearCalls)

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("next VLESS readiness failed"))
            runCurrent()

            assertEquals(RelayKindHysteria2, settings.snapshot().relayKind)
            assertEquals(listOf(Mode.VPN, Mode.VPN, Mode.VPN), controller.startupFallbackStartCalls)
        }

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
            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(RelayKindHysteria2, recovered.relayKind)
            assertEquals(SeededHysteriaProfileId, recovered.relayProfileId)
            assertEquals("", recovered.simpleFailoverAwgProfileId)

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            assertEquals(RelayKindHysteria2, monitor.activeTransport.value?.protocolKind)

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("fallback failed"))
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)
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
            assertEquals(listOf(Mode.VPN, Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(false, recovered.relayEnabled)
            assertEquals(awgRequest.profileId, recovered.simpleFailoverAwgProfileId)
            assertEquals(listOf(awgRequest), awgSelection.selectedRequests)

            stateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            assertEquals("amneziawg", monitor.activeTransport.value?.protocolKind)

            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("AWG readiness failed"))
            runCurrent()
            assertEquals(listOf(Mode.VPN, Mode.VPN), controller.startupFallbackStartCalls)
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

            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)
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

            assertEquals(emptyList<Mode>(), controller.startupFallbackStartCalls)
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

            assertEquals(emptyList<Mode>(), controller.startupFallbackStartCalls)
            assertEquals(RelayKindVlessReality, settings.snapshot().relayKind)
        }

    @Test
    fun `manual start while fallback waits invalidates the stale recovery`() =
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
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            stateStore.setStatus(AppStatus.Reconnecting, Mode.VPN)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("old VLESS readiness failed"))
            runCurrent()
            controller.acceptUserStart()
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            assertEquals(emptyList<Mode>(), controller.startupFallbackStartCalls)
            monitor.prepare(Mode.VPN, ServiceIntentArbiter().explicitUserStartGuard(0L))
            assertEquals(RelayKindVlessReality, settings.snapshot().relayKind)
        }

    @Test
    fun `manual stop while fallback waits prevents an automatic restart`() =
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
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            stateStore.setStatus(AppStatus.Reconnecting, Mode.VPN)
            runCurrent()

            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()
            controller.acceptUserStop()
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            assertEquals(emptyList<Mode>(), controller.startupFallbackStartCalls)
            assertEquals(true, settings.snapshot().relayEnabled)
            assertEquals(RelayKindVlessReality, settings.snapshot().relayKind)
            assertEquals(SeededVlessProfileId, settings.snapshot().relayProfileId)
        }

    @Test
    fun `manual stop rolls back a stale AWG selection before suppressing restart`() =
        runTest {
            val stateStore = DefaultServiceStateStore()
            val settings = FakeAppSettingsRepository()
            val controller = RecordingServiceController()
            val awgSelection = RecordingAwgFallbackSelection(sampleAwgRequest())
            settings.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindVlessReality)
                setRelayProfileId(SeededVlessProfileId)
            }
            val monitor =
                buildMonitor(
                    stateStore = stateStore,
                    settings = settings,
                    profiles = listOf(RelayProfileRecord(SeededHysteriaProfileId, RelayKindHysteria2)),
                    awgSelection = awgSelection,
                    controller = controller,
                )
            monitor.bind(backgroundScope)
            runCurrent()
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("VLESS readiness failed"))
            runCurrent()
            stateStore.setStatus(AppStatus.Reconnecting, Mode.VPN)
            stateStore.emitFailed(Sender.VPN, FailureReason.NativeError("Hysteria2 readiness failed"))
            runCurrent()

            controller.acceptUserStop()
            stateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            assertEquals(listOf(Mode.VPN), controller.startupFallbackStartCalls)
            assertEquals(true, settings.snapshot().relayEnabled)
            assertEquals(RelayKindHysteria2, settings.snapshot().relayKind)
            assertEquals(SeededHysteriaProfileId, settings.snapshot().relayProfileId)
            assertEquals("", settings.snapshot().simpleFailoverAwgProfileId)
            assertNull(awgSelection.currentRequest)
        }

    private fun buildMonitor(
        stateStore: ServiceStateStore,
        settings: AppSettingsRepository,
        profiles: List<RelayProfileRecord> = emptyList(),
        relayCatalog: SimpleFailoverRelayCatalog = SimpleFailoverRelayCatalog { profiles },
        awgSelection: SimpleAwgFallbackSelection = RecordingAwgFallbackSelection(),
        controller: RecordingServiceController = RecordingServiceController(),
    ): SimpleVlessRuntimeMonitor =
        SimpleVlessRuntimeMonitor(
            serviceStateStore = stateStore,
            settingsRepository = settings,
            relayCatalog = relayCatalog,
            awgFallbackSelection = awgSelection,
            startupFallbackController = controller,
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

private class SubscriptionAwareServiceStateStore : ServiceStateStore {
    private val statusState = MutableStateFlow(AppStatus.Halted to Mode.VPN)
    private val eventState = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventState.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()
    val eventSubscriberCount: Int
        get() = eventState.subscriptionCount.value

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        eventState.tryEmit(
            ServiceEvent.Failed(
                sender = sender,
                reason = reason,
                statusAtFailure = status.value.first,
                modeAtFailure = status.value.second,
            ),
        )
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }
}

private class RecordingAwgFallbackSelection(
    var request: AwgActivationRequest? = null,
) : SimpleAwgFallbackSelection {
    val selectedRequests = mutableListOf<AwgActivationRequest>()
    var clearCalls = 0
    var currentRequest: AwgActivationRequest? = null
        private set

    override suspend fun firstAvailable(): AwgActivationRequest? = request

    override fun select(request: AwgActivationRequest) {
        selectedRequests += request
        currentRequest = request
    }

    override fun clear() {
        clearCalls += 1
        currentRequest = null
    }

    override fun clearForUserStart() = clear()
}

private class RecordingServiceController(
    private val startupFallbackResults: MutableList<ServiceStartResult> = mutableListOf(),
) : ServiceController,
    StartupFallbackController {
    val userStartCalls = mutableListOf<Mode>()
    val startupFallbackStartCalls = mutableListOf<Mode>()
    val failoverRestartCalls = mutableListOf<Mode>()
    private var userIntentGeneration = 0L

    override fun start(mode: Mode): ServiceStartResult {
        userStartCalls += mode
        return ServiceStartResult.Accepted(mode)
    }

    override fun restartVpnForTransportFailover(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
    ): ServiceStartResult {
        failoverRestartCalls += Mode.VPN
        return ServiceStartResult.Accepted(Mode.VPN)
    }

    override fun captureStartupFallbackLease(): StartupFallbackLease =
        RecordingStartupFallbackLease(userIntentGeneration)

    override fun startVpnForStartupFallback(lease: StartupFallbackLease): StartupFallbackDispatchResult {
        if ((lease as RecordingStartupFallbackLease).generation != userIntentGeneration) {
            return StartupFallbackDispatchResult.Superseded
        }
        startupFallbackStartCalls += Mode.VPN
        val result =
            if (startupFallbackResults.isEmpty()) {
                ServiceStartResult.Accepted(Mode.VPN)
            } else {
                startupFallbackResults.removeAt(0)
            }
        return StartupFallbackDispatchResult.Dispatched(result)
    }

    fun acceptUserStart() {
        userIntentGeneration += 1
    }

    fun acceptUserStop() {
        userIntentGeneration += 1
    }

    override fun stop() = Unit
}

private data class RecordingStartupFallbackLease(
    val generation: Long,
) : StartupFallbackLease
