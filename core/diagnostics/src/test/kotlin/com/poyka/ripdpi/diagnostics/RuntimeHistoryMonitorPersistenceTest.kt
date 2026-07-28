package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultDeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.DeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.DeviceRuntimeLifecyclePhase
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeHistoryMonitorPersistenceTest {
    @Test
    fun `telemetry persistence does not cancel an in flight write`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message == "first") {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
            }
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.updateTelemetry(telemetryWithEvent("first", createdAt = 1L))
            runCurrent()
            firstWriteStarted.await()
            serviceStateStore.updateTelemetry(telemetryWithEvent("second", createdAt = 2L))
            runCurrent()
            releaseFirstWrite.complete(Unit)
            runCurrent()

            assertEquals(listOf("first", "second"), stores.nativeEventsState.value.map { it.message })
            monitorScope.cancel()
        }

    @Test
    fun `telemetry persistence continues after a failed write`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            var failNextWrite = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message == "fails" && failNextWrite) {
                    failNextWrite = false
                    error("injected persistence failure")
                }
            }
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.updateTelemetry(telemetryWithEvent("fails", createdAt = 3L))
            runCurrent()
            serviceStateStore.updateTelemetry(telemetryWithEvent("after-failure", createdAt = 4L))
            runCurrent()

            assertTrue(stores.nativeEventsState.value.any { it.message == "after-failure" })
            monitorScope.cancel()
        }

    @Test
    fun `status before telemetry persists final data plane event on active session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            val connectionSessionId =
                stores.usageSessionsState.value
                    .single()
                    .id

            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry())
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            val finalEvent =
                stores.nativeEventsState.value.single { event ->
                    event.message == "state=outbound_only final=true"
                }
            assertEquals(connectionSessionId, finalEvent.connectionSessionId)
            monitorScope.cancel()
        }

    @Test
    fun `running attachment serializes with concurrent failure correlation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val readyWriteStarted = CompletableDeferred<Unit>()
            val releaseReadyWrite = CompletableDeferred<Unit>()
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message.contains("trigger=running_ready")) {
                    readyWriteStarted.complete(Unit)
                    releaseReadyWrite.await()
                }
            }
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            readyWriteStarted.await()

            serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError("boom"))
            runCurrent()
            releaseReadyWrite.complete(Unit)
            runCurrent()

            val readyEvent =
                stores.nativeEventsState.value.single { it.message.contains("trigger=running_ready") }
            val deviceFailure =
                stores.nativeEventsState.value.single { event ->
                    event.source == "android_device_state" && event.message.contains("trigger=failure")
                }
            val serviceFailure = stores.nativeEventsState.value.single { it.source == "proxy" }
            assertEquals(readyEvent.connectionSessionId, deviceFailure.connectionSessionId)
            assertEquals(readyEvent.connectionSessionId, serviceFailure.connectionSessionId)
            assertEquals(1, stores.usageSessionsState.value.size)
            monitorScope.cancel()
        }

    @Test
    fun `active reconnect records one start and recovery only after running`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Reconnecting, Mode.VPN)
            runCurrent()

            assertEquals(
                1,
                stores.nativeEventsState.value.count { it.message.contains("trigger=reconnect_start") },
            )
            assertTrue(stores.nativeEventsState.value.none { it.message.contains("trigger=recovery") })

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            assertEquals(
                1,
                stores.nativeEventsState.value.count { it.message.contains("trigger=reconnect_start") },
            )
            assertEquals(
                1,
                stores.nativeEventsState.value.count { it.message.contains("trigger=recovery") },
            )
            monitorScope.cancel()
        }

    @Test
    fun `handover telemetry persists each new state once per running session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.updateTelemetry(handoverTelemetry(" ", updatedAt = 1L))
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            val firstConnectionSessionId =
                stores.usageSessionsState.value
                    .single()
                    .id

            serviceStateStore.updateTelemetry(handoverTelemetry("observed", updatedAt = 10L))
            runCurrent()
            serviceStateStore.updateTelemetry(handoverTelemetry("observed", updatedAt = 11L))
            runCurrent()
            serviceStateStore.updateTelemetry(handoverTelemetry(" ", updatedAt = 12L))
            runCurrent()
            serviceStateStore.updateTelemetry(handoverTelemetry("restarting", updatedAt = 13L))
            runCurrent()

            val firstSessionEvents = handoverEvents(stores)
            assertEquals(2, firstSessionEvents.size)
            assertTrue(firstSessionEvents.all { it.connectionSessionId == firstConnectionSessionId })
            assertTrue(firstSessionEvents.none { it.message.contains("observed") || it.message.contains("restarting") })

            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            val secondConnectionSessionId =
                stores.usageSessionsState.value
                    .single { it.finishedAt == null }
                    .id
            assertTrue(handoverEvents(stores).none { it.connectionSessionId == secondConnectionSessionId })
            serviceStateStore.updateTelemetry(handoverTelemetry("restarting", updatedAt = 14L))
            runCurrent()
            assertTrue(handoverEvents(stores).none { it.connectionSessionId == secondConnectionSessionId })
            serviceStateStore.updateTelemetry(handoverTelemetry("revalidated", updatedAt = 15L))
            runCurrent()

            val secondSessionEvents =
                handoverEvents(stores).filter { it.connectionSessionId == secondConnectionSessionId }
            assertEquals(1, secondSessionEvents.size)
            monitorScope.cancel()
        }

    @Test
    fun `device runtime ingress preserves pre running service lifecycle evidence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val evidenceStore = DefaultDeviceRuntimeEvidenceStore()
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope, evidenceStore)

            evidenceStore.record(
                DeviceRuntimeEvidence.ServiceLifecycle(
                    mode = Mode.VPN,
                    phase = DeviceRuntimeLifecyclePhase.Created,
                    observedAtMillis = 10L,
                ),
            )
            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()

            val event = stores.nativeEventsState.value.single { it.message.contains("trigger=service_created") }
            assertEquals(
                stores.usageSessionsState.value
                    .single()
                    .id,
                event.connectionSessionId,
            )
            monitorScope.cancel()
        }

    private fun kotlinx.coroutines.test.TestScope.monitorScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, _ -> },
        )

    private fun createMonitor(
        stores: FakeDiagnosticsHistoryStores,
        serviceStateStore: DefaultServiceStateStore,
        scope: CoroutineScope,
        deviceRuntimeEvidenceStore: DeviceRuntimeEvidenceStore = DefaultDeviceRuntimeEvidenceStore(),
    ): RuntimeHistoryStartup =
        createRuntimeHistoryMonitor(
            appSettingsRepository = FakeAppSettingsRepository(),
            stores = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            deviceRuntimeEvidenceStore = deviceRuntimeEvidenceStore,
            scope = scope,
        )

    private fun telemetryWithEvent(
        message: String,
        createdAt: Long,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            proxyTelemetry =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    nativeEvents =
                        listOf(
                            NativeRuntimeEvent(
                                source = "proxy",
                                level = "info",
                                message = message,
                                createdAt = createdAt,
                            ),
                        ),
                ),
            updatedAt = createdAt,
        )

    private fun finalDataPlaneTelemetry(): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            proxyTelemetry =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    nativeEvents =
                        listOf(
                            NativeRuntimeEvent(
                                source = "service",
                                level = "info",
                                message = "state=outbound_only final=true",
                                createdAt = 10L,
                                kind = "data_plane_final",
                                subsystem = "data_plane",
                            ),
                        ),
                ),
            updatedAt = 10L,
        )

    private fun handoverTelemetry(
        state: String,
        updatedAt: Long,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            networkHandoverState = state,
            updatedAt = updatedAt,
        )

    private fun handoverEvents(stores: FakeDiagnosticsHistoryStores) =
        stores.nativeEventsState.value.filter { event ->
            event.source == "android_device_state" && event.message.contains("trigger=handover")
        }
}
