package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
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
    fun `corrupt terminal outbox fails closed without exposing payload`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val coordinatorScope = monitorScope()
            val coordinator = createSessionCoordinator(stores, serviceStateStore, coordinatorScope)
            val canary = "sensitive-canary-material"
            stores.terminalOutboxState.value =
                listOf(
                    DiagnosticsDurableStateEntity(
                        key = "runtime_terminal_outbox:corrupt",
                        value = "{not-json-$canary",
                        updatedAt = 1L,
                    ),
                )

            val error = runCatching { coordinator.handleStatusChange(AppStatus.Halted, Mode.VPN) }.exceptionOrNull()

            assertEquals("Invalid terminal outbox state", error?.message)
            assertFalse(error?.message.orEmpty().contains(canary))
            assertEquals(1, stores.getPendingTerminalOutboxes().size)
            coordinatorScope.cancel()
        }

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
    fun `terminal event retry is not suppressed after a failed durable write`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)
            val telemetry = finalDataPlaneTelemetry()
            var armed = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (armed && event.id.startsWith("runtime_terminal_event:")) {
                    armed = false
                    error("injected terminal event write failure")
                }
            }

            assertTrue(
                runCatching {
                    persister.persistTerminalRuntimeEvents(telemetry, ConnectionSessionId)
                }.isFailure,
            )
            persister.persistTerminalRuntimeEvents(telemetry, ConnectionSessionId)

            assertEquals(
                1,
                stores.nativeEventsState.value.count { event ->
                    event.id.startsWith("runtime_terminal_event:$ConnectionSessionId:")
                },
            )
        }

    @Test
    fun `fresh coordinator preserves assessment committed before outbox completion`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val firstStateStore = DefaultServiceStateStore()
            val firstScope = monitorScope()
            val firstCoordinator = createSessionCoordinator(stores, firstStateStore, firstScope)
            firstCoordinator.registerNetworkTransitionFlush { true }
            var armed = true
            stores.afterInsertNativeSessionEvent = { event ->
                if (armed && event.source == RuntimeRootCauseAssessmentSource) {
                    armed = false
                    error("injected post-assessment process death")
                }
            }

            firstStateStore.setStatus(AppStatus.Running, Mode.VPN)
            firstCoordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
            firstStateStore.updateTelemetry(finalDataPlaneTelemetry())
            firstStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertTrue(
                runCatching {
                    firstCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)
                }.isFailure,
            )
            assertTrue(decodeAssessment(stores).terminalEvidenceSealed)
            assertEquals(1, stores.getPendingTerminalOutboxes().size)
            firstScope.cancel()
            stores.afterInsertNativeSessionEvent = {}

            val restoredScope = monitorScope()
            val restoredCoordinator =
                createSessionCoordinator(stores, DefaultServiceStateStore(), restoredScope)
            restoredCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

            assertEquals(1, rootCauseAssessments(stores).size)
            assertTrue(decodeAssessment(stores).terminalEvidenceSealed)
            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            restoredScope.cancel()
        }

    @Test
    fun `failure or cancellation at every terminal phase cannot reopen the detached session`() =
        runTest {
            val failures =
                listOf<() -> Throwable>(
                    { IllegalStateException("injected terminal failure") },
                    { CancellationException("injected terminal cancellation") },
                )

            TerminalFailurePhase.entries.forEach { phase ->
                failures.forEach { createFailure ->
                    assertRestartCreatesFreshSession(phase, createFailure)
                }
            }
        }

    @Test
    fun `failed outbox begin is retried before admitting a fresh running session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val coordinatorScope = monitorScope()
            val coordinator = createSessionCoordinator(stores, serviceStateStore, coordinatorScope)
            coordinator.registerNetworkTransitionFlush { true }

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            coordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
            val finishedSessionId =
                stores.usageSessionsState.value
                    .single()
                    .id
            var firstAdmission: NetworkTransitionAdmission? = null
            coordinator.withNetworkTransitionAdmission { admission -> firstAdmission = admission }
            assertEquals(finishedSessionId, requireNotNull(firstAdmission).connectionSessionId)
            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry())

            var failBegin = true
            stores.beforeUpsertBypassUsageSession = { session ->
                if (failBegin && session.finishedAt != null) {
                    failBegin = false
                    error("injected terminal outbox begin failure")
                }
            }
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertTrue(
                runCatching {
                    coordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)
                }.isFailure,
            )
            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertEquals(
                null,
                stores.usageSessionsState.value
                    .single()
                    .finishedAt,
            )
            var admissionAfterFailure: NetworkTransitionAdmission? = null
            coordinator.withNetworkTransitionAdmission { admission -> admissionAfterFailure = admission }
            assertEquals(null, admissionAfterFailure)

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            coordinator.handleStatusChange(AppStatus.Running, Mode.VPN)

            val sessions = stores.usageSessionsState.value
            val restarted = sessions.single { session -> session.id != finishedSessionId }
            assertEquals(2, sessions.size)
            assertTrue(sessions.single { session -> session.id == finishedSessionId }.finishedAt != null)
            assertEquals(null, restarted.finishedAt)
            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertTrue(decodeAssessment(stores).terminalEvidenceSealed)
            var restartedAdmission: NetworkTransitionAdmission? = null
            coordinator.withNetworkTransitionAdmission { admission -> restartedAdmission = admission }
            val admitted = requireNotNull(restartedAdmission)
            assertEquals(restarted.id, admitted.connectionSessionId)
            assertTrue(admitted.epoch > requireNotNull(firstAdmission).epoch)
            coordinatorScope.cancel()
        }

    @Test
    fun `fresh coordinator reconstructs missing assessment from a finished session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val firstStateStore = DefaultServiceStateStore()
            val firstScope = monitorScope()
            val firstCoordinator = createSessionCoordinator(stores, firstStateStore, firstScope)
            firstCoordinator.registerNetworkTransitionFlush { true }
            armTerminalFailure(stores, TerminalFailurePhase.ROOT_CAUSE_ASSESSMENT) {
                IllegalStateException("injected process-death boundary")
            }

            firstStateStore.setStatus(AppStatus.Running, Mode.VPN)
            firstCoordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
            firstStateStore.updateTelemetry(finalDataPlaneTelemetry())
            firstStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertTrue(
                runCatching {
                    firstCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)
                }.isFailure,
            )
            assertTrue(
                stores.usageSessionsState.value
                    .single()
                    .finishedAt != null,
            )
            assertTrue(rootCauseAssessments(stores).isEmpty())
            firstScope.cancel()

            val restoredStateStore = DefaultServiceStateStore()
            val restoredScope = monitorScope()
            val restoredCoordinator = createSessionCoordinator(stores, restoredStateStore, restoredScope)
            restoredCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

            val assessment = decodeAssessment(stores)
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertTrue(assessment.terminalEvidenceSealed)
            restoredScope.cancel()
        }

    @Test
    fun `missing remembered policy completes outbox with unsealed assessment`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val firstStateStore = DefaultServiceStateStore()
            val firstScope = monitorScope()
            val activePolicyStore = MutableActiveConnectionPolicyStore().apply { set(activeRememberedPolicy()) }
            val firstCoordinator = createSessionCoordinator(stores, firstStateStore, firstScope, activePolicyStore)
            firstCoordinator.registerNetworkTransitionFlush { true }
            armTerminalFailure(stores, TerminalFailurePhase.POLICY_FINALIZATION) {
                IllegalStateException("injected policy checkpoint boundary")
            }

            firstStateStore.setStatus(AppStatus.Running, Mode.VPN)
            firstCoordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
            firstCoordinator.handleFailure(Sender.Proxy, FailureReason.NativeError("policy failure proof"))
            firstStateStore.updateTelemetry(finalDataPlaneTelemetry())
            firstStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertTrue(
                runCatching {
                    firstCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)
                }.isFailure,
            )
            assertEquals(1, stores.getPendingTerminalOutboxes().size)
            stores.rememberedPoliciesState.value = emptyList()
            firstScope.cancel()

            val restoredStateStore = DefaultServiceStateStore()
            val restoredScope = monitorScope()
            val restoredCoordinator = createSessionCoordinator(stores, restoredStateStore, restoredScope)
            restoredCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertFalse(decodeAssessment(stores).terminalEvidenceSealed)
            restoredScope.cancel()
        }

    private suspend fun TestScope.assertRestartCreatesFreshSession(
        phase: TerminalFailurePhase,
        createFailure: () -> Throwable,
    ) {
        val stores = FakeDiagnosticsHistoryStores()
        val serviceStateStore = DefaultServiceStateStore()
        val coordinatorScope = monitorScope()
        val activePolicyStore = MutableActiveConnectionPolicyStore()
        if (phase == TerminalFailurePhase.POLICY_FINALIZATION) {
            activePolicyStore.set(activeRememberedPolicy())
        }
        val coordinator = createSessionCoordinator(stores, serviceStateStore, coordinatorScope, activePolicyStore)
        coordinator.registerNetworkTransitionFlush { true }

        serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
        coordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
        val finishedSessionId =
            stores.usageSessionsState.value
                .single()
                .id
        serviceStateStore.updateTelemetry(finalDataPlaneTelemetry())
        if (phase == TerminalFailurePhase.POLICY_FINALIZATION) {
            coordinator.handleFailure(Sender.Proxy, FailureReason.NativeError("policy failure proof"))
        }
        armTerminalFailure(stores, phase, createFailure)
        serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)

        val terminalFailure =
            runCatching {
                coordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)
            }.exceptionOrNull()
        assertTrue("$phase did not fail with ${createFailure().javaClass.simpleName}", terminalFailure != null)
        assertEquals(1, stores.getPendingTerminalOutboxes().size)
        coordinatorScope.cancel()

        val restoredStateStore = DefaultServiceStateStore()
        val restoredScope = monitorScope()
        val restoredCoordinator = createSessionCoordinator(stores, restoredStateStore, restoredScope)
        restoredStateStore.setStatus(AppStatus.Running, Mode.VPN)
        restoredCoordinator.handleStatusChange(AppStatus.Running, Mode.VPN)

        val sessions = stores.usageSessionsState.value
        val restarted = sessions.single { session -> session.id != finishedSessionId }
        assertEquals(2, sessions.size)
        assertTrue(sessions.single { session -> session.id == finishedSessionId }.finishedAt != null)
        assertEquals(null, restarted.finishedAt)
        assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
        assertEquals(1, rootCauseAssessments(stores).size)
        assertTrue(decodeAssessment(stores).terminalEvidenceSealed)
        if (phase == TerminalFailurePhase.POLICY_FINALIZATION) {
            val persistedPolicy = stores.getRememberedNetworkPolicy("terminal-policy", Mode.VPN.preferenceValue)
            assertEquals(1, persistedPolicy?.failureCount)
            assertEquals(1, persistedPolicy?.consecutiveFailureCount)
        }
        restoredScope.cancel()
    }

    private fun armTerminalFailure(
        stores: FakeDiagnosticsHistoryStores,
        phase: TerminalFailurePhase,
        createFailure: () -> Throwable,
    ) {
        var armed = true

        fun failOnce() {
            if (armed) {
                armed = false
                throw createFailure()
            }
        }
        when (phase) {
            TerminalFailurePhase.RUNTIME_EVENTS -> {
                stores.beforeInsertNativeSessionEvent = { event ->
                    if (event.subsystem == "data_plane") failOnce()
                }
            }

            TerminalFailurePhase.TERMINAL_SAMPLE -> {
                stores.beforeInsertTelemetrySample = { failOnce() }
            }

            TerminalFailurePhase.POLICY_FINALIZATION -> {
                stores.beforeCheckpointTerminalPolicy = { failOnce() }
            }

            TerminalFailurePhase.SESSION_UPSERT -> {
                stores.beforeCheckpointTerminalSession = { failOnce() }
            }

            TerminalFailurePhase.ROOT_CAUSE_ASSESSMENT -> {
                stores.beforeInsertNativeSessionEvent = { event ->
                    if (event.source == RuntimeRootCauseAssessmentSource) failOnce()
                }
            }
        }
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
        activeConnectionPolicyStore: ActiveConnectionPolicyStore = emptyActiveConnectionPolicyStore(),
    ): RuntimeSessionCoordinator =
        RuntimeSessionCoordinator(
            appSettingsRepository = FakeAppSettingsRepository(),
            profileCatalog = stores,
            bypassUsageHistoryStore = stores,
            terminalOutboxStore = stores,
            rememberedNetworkPolicyRecordStore = stores,
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            activeConnectionPolicyStore = activeConnectionPolicyStore,
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

    private fun activeRememberedPolicy(): ActiveConnectionPolicy =
        ActiveConnectionPolicy(
            mode = Mode.VPN,
            policy =
                RememberedNetworkPolicyJson(
                    fingerprintHash = "terminal-policy",
                    mode = Mode.VPN.preferenceValue,
                    summary =
                        NetworkFingerprintSummary(
                            transport = "wifi",
                            networkState = "validated",
                            identityKind = "wifi",
                            privateDnsMode = "system",
                            dnsServerCount = 1,
                        ),
                    proxyConfigJson = "{}",
                ),
            matchedPolicy =
                RememberedNetworkPolicyEntity(
                    fingerprintHash = "terminal-policy",
                    mode = Mode.VPN.preferenceValue,
                    summaryJson = "{}",
                    proxyConfigJson = "{}",
                    source = RememberedNetworkPolicySource.MANUAL_SESSION.encodeStorageValue(),
                    status = RememberedNetworkPolicyStatusValidated,
                    firstObservedAt = 1L,
                    updatedAt = 1L,
                ),
            usedRememberedPolicy = true,
            fingerprintHash = "terminal-policy",
            policySignature = "terminal-policy-signature",
            appliedAt = 1L,
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

    private enum class TerminalFailurePhase {
        RUNTIME_EVENTS,
        TERMINAL_SAMPLE,
        POLICY_FINALIZATION,
        SESSION_UPSERT,
        ROOT_CAUSE_ASSESSMENT,
    }

    private class MutableActiveConnectionPolicyStore : ActiveConnectionPolicyStore {
        private val state = MutableStateFlow<Map<Mode, ActiveConnectionPolicy>>(emptyMap())
        override val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>> = state

        fun set(policy: ActiveConnectionPolicy) {
            state.value = mapOf(policy.mode to policy)
        }
    }
}
