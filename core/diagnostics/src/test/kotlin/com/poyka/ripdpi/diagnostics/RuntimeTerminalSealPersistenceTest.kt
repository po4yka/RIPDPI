package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.memory.NativeMemorySample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeTerminalSealPersistenceTest {
    @Test
    fun `terminal assessment waits for admitted telemetry persistence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val insertStarted = CompletableDeferred<Unit>()
            val releaseInsert = CompletableDeferred<Unit>()
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message.contains("event_kind=data_plane_final")) {
                    insertStarted.complete(Unit)
                    releaseInsert.await()
                }
            }
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry())
            runCurrent()
            insertStarted.await()
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            assertTrue(rootCauseAssessments(stores).isEmpty())
            releaseInsert.complete(Unit)
            runCurrent()
            assertTrue(decodeAssessment(stores).terminalEvidenceSealed)
            monitorScope.cancel()
        }

    @Test
    fun `terminal assessment waits for admitted failure persistence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val insertStarted = CompletableDeferred<Unit>()
            val releaseInsert = CompletableDeferred<Unit>()
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message.contains("blocked failure")) {
                    insertStarted.complete(Unit)
                    releaseInsert.await()
                }
            }
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry())
            runCurrent()
            serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError("blocked failure"))
            runCurrent()
            insertStarted.await()
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            assertTrue(rootCauseAssessments(stores).isEmpty())
            releaseInsert.complete(Unit)
            runCurrent()
            assertEquals(1, rootCauseAssessments(stores).size)
            monitorScope.cancel()
        }

    @Test
    fun `exhausted final telemetry capture leaves terminal assessment unsealed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            runCurrent()

            val assessment = decodeAssessment(stores)
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertFalse(assessment.terminalEvidenceSealed)
            monitorScope.cancel()
        }

    @Test
    fun `terminal assessment keeps monotonic recovery outside the noisy wall clock window`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.nativeEventsState.value = noisyEventsWithBackwardClockRecovery()
            val persister = createArtifactPersister(stores)

            persister.persistTerminalRootCauseAssessment(
                connectionSessionId = ConnectionSessionId,
                createdAt = 2_000L,
                terminalEvidenceSealed = true,
            )

            val assessment = decodeAssessment(stores)
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertTrue(assessment.terminalEvidenceSealed)
            assertTrue(assessment.evidenceRefs.isEmpty())
            assertEquals(67, assessment.evidenceEventCount)
        }

    @Test
    fun `failed or cancelled terminal assessment cannot reopen the finished session`() =
        runTest {
            val failures =
                listOf<() -> Throwable>(
                    { IllegalStateException("injected assessment failure") },
                    { CancellationException("injected assessment cancellation") },
                )

            failures.forEach { createFailure ->
                assertRestartCreatesFreshSession(createFailure)
            }
        }

    private suspend fun TestScope.assertRestartCreatesFreshSession(createFailure: () -> Throwable) {
        val stores = FakeDiagnosticsHistoryStores()
        val serviceStateStore = DefaultServiceStateStore()
        val coordinatorScope = monitorScope()
        val coordinator = createSessionCoordinator(stores, serviceStateStore, coordinatorScope)
        var failNextAssessment = true
        stores.beforeInsertNativeSessionEvent = { event ->
            if (event.source == RuntimeRootCauseAssessmentSource && failNextAssessment) {
                failNextAssessment = false
                throw createFailure()
            }
        }
        coordinator.registerNetworkTransitionFlush { true }

        serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
        coordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
        val finishedSessionId =
            stores.usageSessionsState.value
                .single()
                .id
        serviceStateStore.updateTelemetry(finalDataPlaneTelemetry())
        serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)

        val terminalFailure =
            runCatching {
                coordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)
            }.exceptionOrNull()
        assertTrue(terminalFailure != null)
        assertTrue(
            stores.usageSessionsState.value
                .single()
                .finishedAt != null,
        )

        serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
        coordinator.handleStatusChange(AppStatus.Running, Mode.VPN)

        val sessions = stores.usageSessionsState.value
        val restarted = sessions.single { session -> session.id != finishedSessionId }
        assertEquals(2, sessions.size)
        assertTrue(sessions.single { session -> session.id == finishedSessionId }.finishedAt != null)
        assertEquals(null, restarted.finishedAt)
        coordinatorScope.cancel()
    }

    private fun TestScope.monitorScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, _ -> },
        )

    private fun createMonitor(
        stores: FakeDiagnosticsHistoryStores,
        serviceStateStore: DefaultServiceStateStore,
        scope: CoroutineScope,
    ): RuntimeHistoryStartup =
        createRuntimeHistoryMonitor(
            appSettingsRepository = FakeAppSettingsRepository(),
            stores = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            networkTransitionFlush = { true },
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

    private fun createSessionCoordinator(
        stores: FakeDiagnosticsHistoryStores,
        serviceStateStore: DefaultServiceStateStore,
        scope: CoroutineScope,
    ): RuntimeSessionCoordinator =
        RuntimeSessionCoordinator(
            appSettingsRepository = FakeAppSettingsRepository(),
            profileCatalog = stores,
            bypassUsageHistoryStore = stores,
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            activeConnectionPolicyStore = emptyActiveConnectionPolicyStore(),
            rememberedPolicySessionTracker =
                RememberedPolicySessionTracker(
                    rememberedNetworkPolicyStore =
                        DefaultRememberedNetworkPolicyStore(stores, TestDiagnosticsHistoryClock()),
                    policyHandoverEventStore = FakePolicyHandoverEventStore(),
                ),
            artifactPersister = createArtifactPersister(stores, serviceStateStore),
            deviceStateEventRecorder =
                DefaultDeviceStateEventRecorder(
                    provider = FakeDeviceStateProvider(),
                    artifactWriteStore = stores,
                    clock = TestDeviceStateEventClock(),
                    scope = scope,
                ),
            scope = scope,
        )

    private fun emptyActiveConnectionPolicyStore(): ActiveConnectionPolicyStore =
        object : ActiveConnectionPolicyStore {
            override val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>> = MutableStateFlow(emptyMap())
        }

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
                                message = "state=evidence_unavailable mode=vpn generation=1 final=true",
                                createdAt = System.currentTimeMillis(),
                                kind = "data_plane_final",
                                subsystem = "data_plane",
                            ),
                        ),
                ),
            updatedAt = System.currentTimeMillis(),
        )

    private fun noisyEventsWithBackwardClockRecovery(): List<NativeSessionEventEntity> =
        List(70) { index ->
            event(
                id = "noise-$index",
                source = "service",
                message = "noise=$index",
                createdAt = 1_000L + index,
                subsystem = "service",
            )
        } +
            listOf(
                event(
                    id = "data-plane-final",
                    source = "service",
                    message =
                        "state=evidence_unavailable mode=vpn generation=1 final=true " +
                            "event_kind=data_plane_final",
                    createdAt = 1_999L,
                    subsystem = "data_plane",
                ),
                transition(
                    "kind=capabilities_changed;path=non_vpn;internet=present;" +
                        "validated=present;generation=1;sequence=1",
                    100L,
                ),
                transition("kind=lost;generation=1;sequence=2", 200L),
                transition(
                    "kind=capabilities_changed;path=non_vpn;internet=present;" +
                        "validated=present;generation=2;sequence=3",
                    50L,
                ),
            )

    private fun transition(
        message: String,
        createdAt: Long,
    ): NativeSessionEventEntity =
        event(
            id = "$ConnectionSessionId:network_transition:$message",
            source = "android_network_callback",
            message = message,
            createdAt = createdAt,
            subsystem = "network_transition",
        )

    private fun event(
        id: String,
        source: String,
        message: String,
        createdAt: Long,
        subsystem: String,
    ): NativeSessionEventEntity =
        NativeSessionEventEntity(
            id = id,
            sessionId = null,
            connectionSessionId = ConnectionSessionId,
            source = source,
            level = "info",
            message = message,
            createdAt = createdAt,
            subsystem = subsystem,
        )

    private fun decodeAssessment(stores: FakeDiagnosticsHistoryStores): RuntimeRootCauseAssessment =
        RuntimeHistoryJson.decodeFromString(
            RuntimeRootCauseAssessment.serializer(),
            rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
        )

    private fun rootCauseAssessments(stores: FakeDiagnosticsHistoryStores): List<NativeSessionEventEntity> =
        stores.nativeEventsState.value.filter { event -> event.source == RuntimeRootCauseAssessmentSource }

    private companion object {
        const val ConnectionSessionId = "conn-a"
    }
}
