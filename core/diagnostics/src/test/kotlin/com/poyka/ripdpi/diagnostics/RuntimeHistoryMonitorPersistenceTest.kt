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
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.memory.NativeMemorySample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry(createdAt = System.currentTimeMillis()))
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            val finalEvent =
                stores.nativeEventsState.value.single { event ->
                    event.message.startsWith("state=outbound_only mode=vpn generation=1 final=true")
                }
            assertEquals(connectionSessionId, finalEvent.connectionSessionId)
            assertTrue(finalEvent.message.endsWith("event_kind=data_plane_final"))
            monitorScope.cancel()
        }

    @Test
    fun `terminal session persists one runtime root cause assessment`() =
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

            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry(createdAt = System.currentTimeMillis()))
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            val assessments =
                stores.nativeEventsState.value.filter { event ->
                    event.source == RuntimeRootCauseAssessmentSource
                }
            val assessment =
                RuntimeHistoryJson.decodeFromString(
                    RuntimeRootCauseAssessment.serializer(),
                    assessments.single().message.substringAfter("runtime_root_cause_assessment "),
                )
            assertEquals(connectionSessionId, assessments.single().connectionSessionId)
            assertEquals(RuntimeRootCauseAssessmentSubsystem, assessments.single().subsystem)
            assertEquals(
                stores.nativeEventsState.value.joinToString { event -> "${event.subsystem}:${event.message}" },
                RuntimeRootCauseVerdict.VPN_PATH_LOSS,
                assessment.verdict,
            )
            monitorScope.cancel()
        }

    @Test
    fun `terminal assessment records successful network transition seal`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitorScope = monitorScope()
            var flushCalls = 0
            val monitor =
                createMonitor(
                    stores = stores,
                    serviceStateStore = serviceStateStore,
                    scope = monitorScope,
                    networkTransitionFlush = {
                        flushCalls += 1
                        true
                    },
                )

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            val assessment =
                RuntimeHistoryJson.decodeFromString(
                    RuntimeRootCauseAssessment.serializer(),
                    rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
                )
            assertEquals(1, flushCalls)
            assertTrue(assessment.terminalEvidenceSealed)
            monitorScope.cancel()
        }

    @Test
    fun `active transient failure does not persist root cause before finalization`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()

            serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError("transient"))
            runCurrent()
            assertTrue(rootCauseAssessments(stores).isEmpty())

            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry(createdAt = System.currentTimeMillis()))
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            val assessment =
                RuntimeHistoryJson.decodeFromString(
                    RuntimeRootCauseAssessment.serializer(),
                    rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
                )
            assertEquals(RuntimeRootCauseVerdict.VPN_PATH_LOSS, assessment.verdict)
            monitorScope.cancel()
        }

    @Test
    fun `root cause assessment retries after failed write`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.nativeEventsState.value = listOf(terminalDataPlaneEvent("conn-a", createdAt = 1L))
            var failNextWrite = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.source == RuntimeRootCauseAssessmentSource && failNextWrite) {
                    failNextWrite = false
                    error("injected root cause persistence failure")
                }
            }
            val persister = createArtifactPersister(stores)

            val failed =
                runCatching {
                    persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L)
                }.exceptionOrNull()
            persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L)

            assertTrue(failed is IllegalStateException)
            assertEquals(1, rootCauseAssessments(stores).size)
        }

    @Test
    fun `persistence replaces spoofed event kind only from the allowlisted field`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)
            val telemetry =
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "service",
                                        level = "info",
                                        message =
                                            "state=outbound_only mode=vpn generation=1 final=true " +
                                                "event_kind=data_plane_final",
                                        createdAt = 1L,
                                        kind = "native_warning",
                                        subsystem = "data_plane",
                                    ),
                                ),
                        ),
                    updatedAt = 1L,
                )

            persister.persistRuntimeEvents(telemetry, connectionSessionId = "conn-a")
            persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L)

            val persistedEvidence = stores.nativeEventsState.value.single { it.source == "service" }
            val assessment =
                RuntimeHistoryJson.decodeFromString(
                    RuntimeRootCauseAssessment.serializer(),
                    rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
                )
            assertFalse(persistedEvidence.message.contains("event_kind="))
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        }

    @Test
    fun `root cause assessment retries after cancelled write`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.nativeEventsState.value = listOf(terminalDataPlaneEvent("conn-a", createdAt = 1L))
            var cancelNextWrite = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.source == RuntimeRootCauseAssessmentSource && cancelNextWrite) {
                    cancelNextWrite = false
                    throw CancellationException("injected root cause cancellation")
                }
            }
            val persister = createArtifactPersister(stores)

            val cancelled =
                runCatching {
                    persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L)
                }.exceptionOrNull()
            persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L)

            assertTrue(cancelled is CancellationException)
            assertEquals(1, rootCauseAssessments(stores).size)
        }

    @Test
    fun `root cause assessment concurrent calls persist one event`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.nativeEventsState.value = listOf(terminalDataPlaneEvent("conn-a", createdAt = 1L))
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.source == RuntimeRootCauseAssessmentSource) {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
            }
            val persister = createArtifactPersister(stores)

            val first = launch { persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L) }
            firstWriteStarted.await()
            val second = launch { persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L) }
            runCurrent()
            releaseFirstWrite.complete(Unit)
            joinAll(first, second)

            assertEquals(1, rootCauseAssessments(stores).size)
        }

    @Test
    fun `root cause assessment follower retries after leader write failure`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.nativeEventsState.value = listOf(terminalDataPlaneEvent("conn-a", createdAt = 1L))
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            var attempts = 0
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.source == RuntimeRootCauseAssessmentSource) {
                    attempts += 1
                    if (attempts == 1) {
                        firstWriteStarted.complete(Unit)
                        releaseFirstWrite.await()
                        error("injected leader persistence failure")
                    }
                }
            }
            val persister = createArtifactPersister(stores)

            val leader =
                launch {
                    runCatching {
                        persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L)
                    }
                }
            firstWriteStarted.await()
            val follower = launch { persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 2L) }
            runCurrent()
            releaseFirstWrite.complete(Unit)
            joinAll(leader, follower)

            assertEquals(2, attempts)
            assertEquals(1, rootCauseAssessments(stores).size)
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
        networkTransitionFlush: (suspend () -> Boolean)? = null,
    ): RuntimeHistoryStartup =
        createRuntimeHistoryMonitor(
            appSettingsRepository = FakeAppSettingsRepository(),
            stores = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            deviceRuntimeEvidenceStore = deviceRuntimeEvidenceStore,
            networkTransitionFlush = networkTransitionFlush,
            scope = scope,
        )

    private fun createArtifactPersister(
        stores: FakeDiagnosticsHistoryStores,
        serviceStateStore: DefaultServiceStateStore = DefaultServiceStateStore(),
    ): RuntimeArtifactPersister =
        RuntimeArtifactPersister(
            artifactReadStore = stores,
            artifactWriteStore = stores,
            historyRetentionStore = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            nativeMemoryProbe = { NativeMemorySample(nativeHeapBytes = 0, processRssBytes = 0) },
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

    private fun finalDataPlaneTelemetry(createdAt: Long = 10L): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            proxyTelemetry =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    nativeEvents =
                        listOf(
                            NativeRuntimeEvent(
                                source = "service",
                                level = "info",
                                message = "state=outbound_only mode=vpn generation=1 final=true",
                                createdAt = createdAt,
                                kind = "data_plane_final",
                                subsystem = "data_plane",
                            ),
                        ),
                ),
            updatedAt = createdAt,
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

    private fun terminalDataPlaneEvent(
        connectionSessionId: String,
        createdAt: Long,
    ): NativeSessionEventEntity =
        NativeSessionEventEntity(
            id = "$connectionSessionId:data_plane:$createdAt",
            sessionId = null,
            connectionSessionId = connectionSessionId,
            source = "service",
            level = "info",
            message = "state=outbound_only mode=vpn generation=1 final=true event_kind=data_plane_final",
            createdAt = createdAt,
            subsystem = "data_plane",
        )

    private fun rootCauseAssessments(stores: FakeDiagnosticsHistoryStores): List<NativeSessionEventEntity> =
        stores.nativeEventsState.value.filter { event -> event.source == RuntimeRootCauseAssessmentSource }
}
