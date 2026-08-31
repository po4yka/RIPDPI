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
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
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
internal class RuntimeHistoryMonitorPersistenceTest : RuntimeHistoryMonitorPersistenceTestSupport() {
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
    fun `runtime event dedupe retries exact event after failed insert`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)
            var failNextWrite = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message == "dedupe-retry" && failNextWrite) {
                    failNextWrite = false
                    error("injected runtime event failure")
                }
            }

            val failed =
                runCatching {
                    persister.persistRuntimeEvents(telemetryWithEvent("dedupe-retry", createdAt = 1L), "conn-a")
                }.exceptionOrNull()
            persister.persistRuntimeEvents(telemetryWithEvent("dedupe-retry", createdAt = 1L), "conn-a")

            assertTrue(failed is IllegalStateException)
            assertEquals(listOf("dedupe-retry"), stores.nativeEventsState.value.map { event -> event.message })
        }

    @Test
    fun `runtime event dedupe retries exact event after cancelled insert`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)
            var cancelNextWrite = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message == "dedupe-cancel" && cancelNextWrite) {
                    cancelNextWrite = false
                    throw CancellationException("injected runtime event cancellation")
                }
            }

            val cancelled =
                runCatching {
                    persister.persistRuntimeEvents(telemetryWithEvent("dedupe-cancel", createdAt = 1L), "conn-a")
                }.exceptionOrNull()
            persister.persistRuntimeEvents(telemetryWithEvent("dedupe-cancel", createdAt = 1L), "conn-a")

            assertTrue(cancelled is CancellationException)
            assertEquals(listOf("dedupe-cancel"), stores.nativeEventsState.value.map { event -> event.message })
        }

    @Test
    fun `runtime event dedupe serializes concurrent exact duplicates`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message == "dedupe-concurrent") {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
            }

            val first =
                launch {
                    persister.persistRuntimeEvents(telemetryWithEvent("dedupe-concurrent", createdAt = 1L), "conn-a")
                }
            firstWriteStarted.await()
            val second =
                launch {
                    persister.persistRuntimeEvents(telemetryWithEvent("dedupe-concurrent", createdAt = 1L), "conn-a")
                }
            runCurrent()
            releaseFirstWrite.complete(Unit)
            joinAll(first, second)

            assertEquals(listOf("dedupe-concurrent"), stores.nativeEventsState.value.map { event -> event.message })
        }

    @Test
    fun `runtime event dedupe scopes identical events by connection session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(telemetryWithEvent("shared-event", createdAt = 1L), "conn-a")
            persister.persistRuntimeEvents(telemetryWithEvent("shared-event", createdAt = 1L), "conn-b")

            val events = stores.nativeEventsState.value.filter { event -> event.message == "shared-event" }
            assertEquals(listOf("conn-a", "conn-b"), events.mapNotNull { event -> event.connectionSessionId }.sorted())
        }

    @Test
    fun `runtime persistence ignores terminal data plane event without an active session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(finalDataPlaneTelemetry(createdAt = 1L), connectionSessionId = null)

            assertTrue(stores.nativeEventsState.value.isEmpty())
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
            val monitor =
                createMonitor(
                    stores,
                    serviceStateStore,
                    monitorScope,
                    networkTransitionFlush = { true },
                )

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
                    networkTransitionFlush = { _ ->
                        flushCalls += 1
                        true
                    },
                )

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry(createdAt = System.currentTimeMillis()))
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
            val monitor =
                createMonitor(
                    stores,
                    serviceStateStore,
                    monitorScope,
                    networkTransitionFlush = { true },
                )

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
    fun `runtime protect failure reaches the classifier through the canonical envelope`() =
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
                                        source = "vpn_protect",
                                        level = "debug",
                                        message = "vpn protect backend=jni outcome=rejected",
                                        createdAt = 1L,
                                        kind = "vpn_protect",
                                        runtimeId = "private-runtime",
                                        mode = "private-mode",
                                        policySignature = "private-policy",
                                        fingerprintHash = "private-fingerprint",
                                        subsystem = "protect",
                                    ),
                                ),
                        ),
                    updatedAt = 1L,
                )

            persister.persistRuntimeEvents(telemetry, connectionSessionId = "conn-a")
            stores.nativeEventsState.value +=
                terminalDataPlaneEvent("conn-a", createdAt = 2L).copy(
                    message = "state=evidence_unavailable mode=vpn generation=1 final=true event_kind=data_plane_final",
                )
            persister.persistTerminalRootCauseAssessment(
                connectionSessionId = "conn-a",
                createdAt = 3L,
                terminalEvidenceSealed = true,
            )

            val protectEvent = stores.nativeEventsState.value.single { event -> event.subsystem == "protect" }
            val assessment = decodeRootCauseAssessment(rootCauseAssessments(stores).single())
            assertEquals("service", protectEvent.source)
            assertEquals("warn", protectEvent.level)
            assertEquals("event=protect_failed event_kind=protect_failure", protectEvent.message)
            assertFalse(protectEvent.message.contains("backend"))
            assertFalse(protectEvent.message.contains("outcome"))
            assertEquals(RuntimeRootCauseVerdict.VPN_PATH_LOSS, assessment.verdict)
            assertEquals(listOf("protect_failure"), assessment.evidenceRefs.map { it.category })
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class RuntimeHealthSignalPersistenceTest : RuntimeHistoryMonitorPersistenceTestSupport() {
    @Test
    fun `dns runtime health threshold persists one deterministic typed event`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(dnsTelemetry(queries = 0, failures = 0, updatedAt = 1L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 1, failures = 1, updatedAt = 2L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 2, failures = 2, updatedAt = 3L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 2, failures = 2, updatedAt = 4L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 2, failures = 2, updatedAt = 4L), "conn-b")
            stores.nativeEventsState.value +=
                terminalDataPlaneEvent("conn-a", createdAt = 4L).copy(
                    message = "state=evidence_unavailable mode=vpn generation=1 final=true event_kind=data_plane_final",
                )
            persister.persistTerminalRootCauseAssessment(
                connectionSessionId = "conn-a",
                createdAt = 5L,
                terminalEvidenceSealed = true,
            )

            val typedEvent = typedRuntimeEvents(stores, "dns").single()
            val assessment =
                RuntimeHistoryJson.decodeFromString(
                    RuntimeRootCauseAssessment.serializer(),
                    rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
                )
            assertEquals("typed_runtime_state:dns:conn-a", typedEvent.id)
            assertEquals("service_telemetry_state", typedEvent.source)
            assertEquals("dns", typedEvent.subsystem)
            assertEquals("warn", typedEvent.level)
            assertEquals(
                "event=dns_runtime_state evidence=dns_counter_transition_v1 state=failure_threshold",
                typedEvent.message,
            )
            assertEquals(RuntimeRootCauseVerdict.DNS_FAILURE, assessment.verdict)
            assertEquals(RuntimeRootCauseConfidence.MEDIUM, assessment.confidence)
            assertTrue(assessment.terminalEvidenceSealed)
            assertTrue(stores.nativeEventsState.value.none { event -> event.id == "typed_runtime_state:dns:conn-b" })
        }

    @Test
    fun `dns runtime health source switch does not synthesize failure threshold`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(
                dnsTelemetry(
                    proxyQueries = 1,
                    proxyFailures = 0,
                    tunnelQueries = 0,
                    tunnelFailures = 0,
                    updatedAt = 1L,
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(
                    proxyQueries = 2,
                    proxyFailures = 1,
                    tunnelQueries = 0,
                    tunnelFailures = 0,
                    updatedAt = 2L,
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(
                    proxyQueries = 2,
                    proxyFailures = 1,
                    tunnelQueries = 3,
                    tunnelFailures = 2,
                    updatedAt = 3L,
                ),
                "conn-a",
            )

            assertTrue(typedRuntimeEvents(stores, "dns").isEmpty())
        }

    @Test
    fun `dns runtime health runtime switch does not synthesize failure threshold`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(
                dnsTelemetry(queries = 1, failures = 0, updatedAt = 1L, serviceStartedAt = 100L),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(queries = 2, failures = 1, updatedAt = 2L, serviceStartedAt = 100L),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(queries = 3, failures = 2, updatedAt = 3L, serviceStartedAt = 200L),
                "conn-a",
            )

            assertTrue(typedRuntimeEvents(stores, "dns").isEmpty())
        }

    @Test
    fun `dns runtime health source switch replaces active failure before sealed assessment`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(
                dnsTelemetry(
                    proxyQueries = 1,
                    proxyFailures = 0,
                    tunnelQueries = 0,
                    tunnelFailures = 0,
                    updatedAt = 1L,
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(
                    proxyQueries = 2,
                    proxyFailures = 1,
                    tunnelQueries = 0,
                    tunnelFailures = 0,
                    updatedAt = 2L,
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(
                    proxyQueries = 3,
                    proxyFailures = 2,
                    tunnelQueries = 0,
                    tunnelFailures = 0,
                    updatedAt = 3L,
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(
                    proxyQueries = 3,
                    proxyFailures = 2,
                    tunnelQueries = 4,
                    tunnelFailures = 2,
                    updatedAt = 4L,
                ),
                "conn-a",
            )
            stores.nativeEventsState.value +=
                terminalDataPlaneEvent("conn-a", createdAt = 4L).copy(
                    message = "state=evidence_unavailable mode=vpn generation=1 final=true event_kind=data_plane_final",
                )
            persister.persistTerminalRootCauseAssessment(
                connectionSessionId = "conn-a",
                createdAt = 5L,
                terminalEvidenceSealed = true,
            )

            val typedEvent = typedRuntimeEvents(stores, "dns").single()
            val assessment = decodeRootCauseAssessment(rootCauseAssessments(stores).single())
            assertEquals("info", typedEvent.level)
            assertEquals(
                "event=dns_runtime_state evidence=dns_counter_transition_v1 state=recovered",
                typedEvent.message,
            )
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        }

    @Test
    fun `dns runtime health runtime switch replaces active failure before sealed assessment`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(
                dnsTelemetry(
                    queries = 1,
                    failures = 0,
                    updatedAt = 1L,
                    serviceStartedAt = 100L,
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(
                    queries = 2,
                    failures = 1,
                    updatedAt = 2L,
                    serviceStartedAt = 100L,
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(
                    queries = 3,
                    failures = 2,
                    updatedAt = 3L,
                    serviceStartedAt = 100L,
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                dnsTelemetry(
                    queries = 4,
                    failures = 2,
                    updatedAt = 4L,
                    serviceStartedAt = 200L,
                ),
                "conn-a",
            )
            stores.nativeEventsState.value +=
                terminalDataPlaneEvent("conn-a", createdAt = 4L).copy(
                    message = "state=evidence_unavailable mode=vpn generation=1 final=true event_kind=data_plane_final",
                )
            persister.persistTerminalRootCauseAssessment(
                connectionSessionId = "conn-a",
                createdAt = 5L,
                terminalEvidenceSealed = true,
            )

            val typedEvent = typedRuntimeEvents(stores, "dns").single()
            val assessment = decodeRootCauseAssessment(rootCauseAssessments(stores).single())
            assertEquals("info", typedEvent.level)
            assertEquals(
                "event=dns_runtime_state evidence=dns_counter_transition_v1 state=recovered",
                typedEvent.message,
            )
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        }

    @Test
    fun `dns runtime health threshold remains inconclusive before terminal seal`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(dnsTelemetry(queries = 0, failures = 0, updatedAt = 1L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 1, failures = 1, updatedAt = 2L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 2, failures = 2, updatedAt = 3L), "conn-a")
            persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 5L)

            val assessment =
                RuntimeHistoryJson.decodeFromString(
                    RuntimeRootCauseAssessment.serializer(),
                    rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
                )
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertFalse(assessment.terminalEvidenceSealed)
        }

    @Test
    fun `dns runtime health success replaces threshold with recovered`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(dnsTelemetry(queries = 0, failures = 0, updatedAt = 1L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 1, failures = 1, updatedAt = 2L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 2, failures = 2, updatedAt = 3L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 3, failures = 2, updatedAt = 4L), "conn-a")
            persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 5L)

            val typedEvent = typedRuntimeEvents(stores, "dns").single()
            val assessment =
                RuntimeHistoryJson.decodeFromString(
                    RuntimeRootCauseAssessment.serializer(),
                    rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
                )
            assertEquals("info", typedEvent.level)
            assertEquals(
                "event=dns_runtime_state evidence=dns_counter_transition_v1 state=recovered",
                typedEvent.message,
            )
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        }

    @Test
    fun `dns runtime health rollback resets active failure to inconclusive`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(dnsTelemetry(queries = 0, failures = 0, updatedAt = 1L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 1, failures = 1, updatedAt = 2L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 2, failures = 2, updatedAt = 3L), "conn-a")
            persister.persistRuntimeEvents(dnsTelemetry(queries = 0, failures = 0, updatedAt = 4L), "conn-a")
            persister.persistTerminalRootCauseAssessment("conn-a", createdAt = 5L)

            val typedEvent = typedRuntimeEvents(stores, "dns").single()
            val assessment =
                RuntimeHistoryJson.decodeFromString(
                    RuntimeRootCauseAssessment.serializer(),
                    rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
                )
            assertEquals(
                "event=dns_runtime_state evidence=dns_counter_transition_v1 state=recovered",
                typedEvent.message,
            )
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
        }

    @Test
    fun `relay runtime health persists only categorical typed state`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(
                relayTelemetry(
                    updatedAt = 1L,
                    state = "failed",
                    health = "fd=33 private.example",
                    status =
                        RuntimeTelemetryStatus(
                            state = RuntimeTelemetryState.EngineError,
                            message = "private.example fd=33",
                            causeClass = "java.io.IOException",
                        ),
                ),
                "conn-a",
            )
            persister.persistRuntimeEvents(
                relayTelemetry(
                    updatedAt = 2L,
                    state = "running",
                    health = "ok",
                    status = RuntimeTelemetryStatus(state = RuntimeTelemetryState.Snapshot),
                ),
                "conn-a",
            )

            val typedEvent = typedRuntimeEvents(stores, "relay").single()
            assertEquals("typed_runtime_state:relay:conn-a", typedEvent.id)
            assertEquals("service_telemetry_state", typedEvent.source)
            assertEquals("relay", typedEvent.subsystem)
            assertEquals("info", typedEvent.level)
            assertEquals(
                "event=relay_runtime_state evidence=relay_health_transition_v1 " +
                    "state=running health=ok relay_failed=false",
                typedEvent.message,
            )
            assertFalse(typedEvent.message.contains("private.example"))
            assertFalse(typedEvent.message.contains("fd=33"))
            assertFalse(typedEvent.message.contains("java.io.IOException"))
        }

    @Test
    fun `terminal relay runtime failure reaches the root cause assessment`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)

            persister.persistRuntimeEvents(
                relayTelemetry(
                    updatedAt = 1L,
                    state = "failed",
                    health = "failed",
                    status = RuntimeTelemetryStatus(state = RuntimeTelemetryState.EngineError),
                ),
                "conn-a",
            )
            stores.nativeEventsState.value +=
                terminalDataPlaneEvent("conn-a", createdAt = 2L).copy(
                    message = "state=evidence_unavailable mode=vpn generation=1 final=true event_kind=data_plane_final",
                )
            persister.persistTerminalRootCauseAssessment(
                connectionSessionId = "conn-a",
                createdAt = 3L,
                terminalEvidenceSealed = true,
            )

            val typedEvent = typedRuntimeEvents(stores, "relay").single()
            val assessment = decodeRootCauseAssessment(rootCauseAssessments(stores).single())
            assertEquals(
                "event=relay_runtime_state evidence=relay_health_transition_v1 " +
                    "state=failed health=failed relay_failed=true",
                typedEvent.message,
            )
            assertEquals(RuntimeRootCauseVerdict.RELAY_RUNTIME_FAILURE, assessment.verdict)
            assertEquals(listOf("relay_runtime_failure"), assessment.evidenceRefs.map { it.category })
            assertTrue(assessment.terminalEvidenceSealed)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class RuntimeLifecyclePersistenceTest : RuntimeHistoryMonitorPersistenceTestSupport() {
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
}

internal abstract class RuntimeHistoryMonitorPersistenceTestSupport {
    protected fun kotlinx.coroutines.test.TestScope.monitorScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, _ -> },
        )

    protected fun createMonitor(
        stores: FakeDiagnosticsHistoryStores,
        serviceStateStore: DefaultServiceStateStore,
        scope: CoroutineScope,
        deviceRuntimeEvidenceStore: DeviceRuntimeEvidenceStore = DefaultDeviceRuntimeEvidenceStore(),
        networkTransitionFlush: (suspend (NetworkTransitionAdmission) -> Boolean)? = null,
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

    protected fun createArtifactPersister(
        stores: FakeDiagnosticsHistoryStores,
        serviceStateStore: DefaultServiceStateStore = DefaultServiceStateStore(),
    ): RuntimeArtifactPersister =
        RuntimeArtifactPersister(
            artifactReadStore = stores,
            artifactWriteStore = stores,
            failureArtifactWriteStore = stores,
            historyRetentionStore = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            nativeMemoryProbe = { NativeMemorySample(nativeHeapBytes = 0, processRssBytes = 0) },
        )

    protected fun telemetryWithEvent(
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

    protected fun finalDataPlaneTelemetry(createdAt: Long = 10L): ServiceTelemetrySnapshot =
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

    protected fun handoverTelemetry(
        state: String,
        updatedAt: Long,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            networkHandoverState = state,
            updatedAt = updatedAt,
        )

    protected fun dnsTelemetry(
        queries: Long,
        failures: Long,
        updatedAt: Long,
        serviceStartedAt: Long? = null,
        restartCount: Int = 0,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            tunnelTelemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    dnsQueriesTotal = queries,
                    dnsFailuresTotal = failures,
                ),
            serviceStartedAt = serviceStartedAt,
            restartCount = restartCount,
            updatedAt = updatedAt,
        )

    protected fun dnsTelemetry(
        proxyQueries: Long,
        proxyFailures: Long,
        tunnelQueries: Long,
        tunnelFailures: Long,
        updatedAt: Long,
        serviceStartedAt: Long? = null,
        restartCount: Int = 0,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            proxyTelemetry =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    dnsQueriesTotal = proxyQueries,
                    dnsFailuresTotal = proxyFailures,
                ),
            tunnelTelemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    dnsQueriesTotal = tunnelQueries,
                    dnsFailuresTotal = tunnelFailures,
                ),
            serviceStartedAt = serviceStartedAt,
            restartCount = restartCount,
            updatedAt = updatedAt,
        )

    protected fun relayTelemetry(
        updatedAt: Long,
        state: String,
        health: String,
        status: RuntimeTelemetryStatus,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            relayTelemetry =
                NativeRuntimeSnapshot(
                    source = "relay",
                    state = state,
                    health = health,
                ),
            relayTelemetryStatus = status,
            updatedAt = updatedAt,
        )

    protected fun handoverEvents(stores: FakeDiagnosticsHistoryStores) =
        stores.nativeEventsState.value.filter { event ->
            event.source == "android_device_state" && event.message.contains("trigger=handover")
        }

    protected fun terminalDataPlaneEvent(
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

    protected fun rootCauseAssessments(stores: FakeDiagnosticsHistoryStores): List<NativeSessionEventEntity> =
        stores.nativeEventsState.value.filter { event -> event.source == RuntimeRootCauseAssessmentSource }

    protected fun decodeRootCauseAssessment(event: NativeSessionEventEntity): RuntimeRootCauseAssessment =
        RuntimeHistoryJson.decodeFromString(
            RuntimeRootCauseAssessment.serializer(),
            event.message.substringAfter("runtime_root_cause_assessment "),
        )

    protected fun typedRuntimeEvents(
        stores: FakeDiagnosticsHistoryStores,
        subsystem: String,
    ): List<NativeSessionEventEntity> =
        stores.nativeEventsState.value.filter { event ->
            event.source == "service_telemetry_state" && event.subsystem == subsystem
        }
}
