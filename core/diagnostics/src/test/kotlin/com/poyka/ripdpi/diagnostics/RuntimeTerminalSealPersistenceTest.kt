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
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.TerminalPolicyDependencyDurableStatePrefix
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class RuntimeTerminalSealPersistenceTest : RuntimeTerminalPersistenceTestSupport() {
    @Test
    fun `startup recovery drains more than one bounded terminal outbox page`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val sessions =
                (0 until 65).map { index ->
                    finishedUsageSession(
                        id = "recovery-$index",
                        finishedAt = 1_000L + index,
                    )
                }
            stores.usageSessionsState.value = sessions
            stores.terminalOutboxState.value =
                sessions.map { session ->
                    PendingTerminalSession(
                        activeSession = session,
                        finishedSession = session,
                        telemetry = null,
                        createdAt = requireNotNull(session.finishedAt),
                        terminalEvidenceSealed = false,
                        policyOutcome = null,
                        artifactBatch = RuntimeTerminalArtifactBatch(events = emptyList()),
                    ).toMarker(PendingTerminalPhase.ROOT_CAUSE_ASSESSMENT)
                }
            val restoredScope = monitorScope()
            val restoredCoordinator =
                createSessionCoordinator(stores, DefaultServiceStateStore(), restoredScope)

            restoredCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertEquals(65, rootCauseAssessments(stores).size)
            restoredScope.cancel()
        }

    @Test
    fun `startup recovery stops when a terminal outbox batch makes no progress`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = finishedUsageSession(id = "stuck-recovery", finishedAt = 1_000L)
            stores.usageSessionsState.value = listOf(session)
            stores.terminalOutboxState.value =
                listOf(
                    PendingTerminalSession(
                        activeSession = session,
                        finishedSession = session,
                        telemetry = null,
                        createdAt = requireNotNull(session.finishedAt),
                        terminalEvidenceSealed = false,
                        policyOutcome = null,
                        artifactBatch = RuntimeTerminalArtifactBatch(events = emptyList()),
                    ).toMarker(PendingTerminalPhase.ROOT_CAUSE_ASSESSMENT),
                )
            val outbox =
                RuntimeTerminalOutbox(
                    usageHistoryStore = stores,
                    outboxStore = stores,
                    policyRecordStore = stores,
                    artifactPersister = createArtifactPersister(stores),
                    rememberedPolicySessionTracker =
                        RememberedPolicySessionTracker(
                            rememberedNetworkPolicyStore =
                                DefaultRememberedNetworkPolicyStore(stores, TestDiagnosticsHistoryClock()),
                            policyHandoverEventStore = FakePolicyHandoverEventStore(),
                        ),
                )
            var recoveryAttempts = 0

            val complete = outbox.recoverAll { recoveryAttempts += 1 }

            assertFalse(complete)
            assertEquals(1, recoveryAttempts)
            assertEquals(1, stores.getPendingTerminalOutboxes().size)
        }

    @Test
    fun `startup recovery reports incomplete when bounded batch cap leaves markers`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val sessions =
                (0 until 65).map { index ->
                    finishedUsageSession(
                        id = "bounded-recovery-$index",
                        finishedAt = 1_000L + index,
                    )
                }
            stores.usageSessionsState.value = sessions
            stores.terminalOutboxState.value =
                sessions.map { session ->
                    PendingTerminalSession(
                        activeSession = session,
                        finishedSession = session,
                        telemetry = null,
                        createdAt = requireNotNull(session.finishedAt),
                        terminalEvidenceSealed = false,
                        policyOutcome = null,
                        artifactBatch = RuntimeTerminalArtifactBatch(events = emptyList()),
                    ).toMarker(PendingTerminalPhase.ROOT_CAUSE_ASSESSMENT)
                }
            val outbox = createTerminalOutbox(stores)

            val firstPassComplete =
                outbox.recoverAll(maxBatches = 1) { recovered -> outbox.persist(recovered) }

            assertFalse(firstPassComplete)
            assertEquals(1, stores.getPendingTerminalOutboxes().size)
            assertTrue(outbox.recoverAll { recovered -> outbox.persist(recovered) })
            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
        }

    @Test
    fun `corrupt terminal outbox is discarded without blocking valid recovery or exposing payload`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val firstStateStore = DefaultServiceStateStore()
            val firstScope = monitorScope()
            val firstCoordinator = createSessionCoordinator(stores, firstStateStore, firstScope)
            stores.beforeCheckpointTerminalOutbox = { error("injected process death") }

            firstStateStore.setStatus(AppStatus.Running, Mode.VPN)
            firstCoordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
            firstStateStore.updateTelemetry(finalDataPlaneTelemetry())
            firstStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertTrue(runCatching { firstCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN) }.isFailure)
            firstScope.cancel()
            stores.beforeCheckpointTerminalOutbox = {}

            val canary = "sensitive-canary-material"
            stores.terminalOutboxState.value =
                stores.terminalOutboxState.value +
                DiagnosticsDurableStateEntity(
                    key = "runtime_terminal_outbox:corrupt",
                    value = "{not-json-$canary",
                    updatedAt = 1L,
                )

            val restoredScope = monitorScope()
            val restoredCoordinator =
                createSessionCoordinator(stores, DefaultServiceStateStore(), restoredScope)
            val error =
                runCatching { restoredCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN) }.exceptionOrNull()

            assertEquals(null, error)
            assertFalse(error?.message.orEmpty().contains(canary))
            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertEquals(1, rootCauseAssessments(stores).size)
            restoredScope.cancel()
        }

    @Test
    fun `version one terminal marker migrates to incomplete unsealed evidence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val firstStateStore = DefaultServiceStateStore()
            val firstScope = monitorScope()
            val firstCoordinator = createSessionCoordinator(stores, firstStateStore, firstScope)
            stores.beforeCheckpointTerminalOutbox = { error("injected process death") }

            firstStateStore.setStatus(AppStatus.Running, Mode.VPN)
            firstCoordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
            firstStateStore.updateTelemetry(finalDataPlaneTelemetry())
            firstStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertTrue(runCatching { firstCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN) }.isFailure)
            firstScope.cancel()
            stores.beforeCheckpointTerminalOutbox = {}

            val marker = stores.terminalOutboxState.value.single()
            assertTrue(marker.value.contains("\"schemaVersion\":2"))
            val connectionSessionId = marker.key.removePrefix("runtime_terminal_outbox:")
            val createdAt = requireNotNull(stores.getBypassUsageSession(connectionSessionId)?.finishedAt)
            stores.terminalOutboxState.value =
                listOf(
                    marker.copy(
                        value =
                            """{"connectionSessionId":"$connectionSessionId","createdAt":$createdAt,""" +
                                """"terminalEvidenceSealed":true,"phase":"RUNTIME_EVENTS"}""",
                    ),
                )

            val restoredScope = monitorScope()
            val restoredCoordinator =
                createSessionCoordinator(stores, DefaultServiceStateStore(), restoredScope)
            restoredCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertFalse(decodeAssessment(stores).terminalEvidenceSealed)
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, decodeAssessment(stores).verdict)
            restoredScope.cancel()
        }

    @Test
    fun `schema valid terminal marker with mismatched key is quarantined`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val firstStateStore = DefaultServiceStateStore()
            val firstScope = monitorScope()
            val firstCoordinator = createSessionCoordinator(stores, firstStateStore, firstScope)
            stores.beforeCheckpointTerminalOutbox = { error("injected process death") }

            firstStateStore.setStatus(AppStatus.Running, Mode.VPN)
            firstCoordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
            firstStateStore.updateTelemetry(finalDataPlaneTelemetry())
            firstStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            assertTrue(runCatching { firstCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN) }.isFailure)
            firstScope.cancel()
            stores.beforeCheckpointTerminalOutbox = {}

            val marker = stores.terminalOutboxState.value.single()
            stores.terminalOutboxState.value = listOf(marker.copy(key = "runtime_terminal_outbox:mismatched"))

            val restoredScope = monitorScope()
            val restoredCoordinator =
                createSessionCoordinator(stores, DefaultServiceStateStore(), restoredScope)
            restoredCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertFalse(decodeAssessment(stores).terminalEvidenceSealed)
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, decodeAssessment(stores).verdict)
            restoredScope.cancel()
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
    fun `same coordinator reconciles every marker advanced before cancellation`() =
        runTest {
            for (cancelAfterCheckpoint in 1..5) {
                val stores = FakeDiagnosticsHistoryStores()
                val serviceStateStore = DefaultServiceStateStore()
                val monitorScope = monitorScope()
                val coordinator = createSessionCoordinator(stores, serviceStateStore, monitorScope)
                var checkpointCount = 0
                stores.afterCheckpointTerminalOutbox = {
                    checkpointCount += 1
                    if (checkpointCount == cancelAfterCheckpoint) {
                        throw CancellationException("injected after durable checkpoint")
                    }
                }

                serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
                coordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
                serviceStateStore.updateTelemetry(finalDataPlaneTelemetry())
                serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)

                assertTrue(runCatching { coordinator.handleStatusChange(AppStatus.Halted, Mode.VPN) }.isFailure)
                assertEquals(1, stores.getPendingTerminalOutboxes().size)

                stores.afterCheckpointTerminalOutbox = {}
                coordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

                assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
                assertEquals(1, rootCauseAssessments(stores).size)
                monitorScope.cancel()
            }
        }

    @Test
    fun `same coordinator reconciles outbox removed before cancellation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val monitorScope = monitorScope()
            val coordinator = createSessionCoordinator(stores, serviceStateStore, monitorScope)
            var injectCancellation = true
            stores.afterCompleteTerminalOutbox = {
                if (injectCancellation) {
                    injectCancellation = false
                    throw CancellationException("injected after durable completion")
                }
            }

            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            coordinator.handleStatusChange(AppStatus.Running, Mode.VPN)
            serviceStateStore.updateTelemetry(finalDataPlaneTelemetry())
            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)

            assertTrue(runCatching { coordinator.handleStatusChange(AppStatus.Halted, Mode.VPN) }.isFailure)
            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())

            stores.afterCompleteTerminalOutbox = {}
            coordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertEquals(1, rootCauseAssessments(stores).size)
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
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class RuntimeTerminalRecoveryPersistenceTest : RuntimeTerminalPersistenceTestSupport() {
    @Test
    fun `retained outbox marker blocks fresh admission until retry drains it`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val finishedSession = finishedUsageSession(id = "retained-recovery", finishedAt = 1_000L)
            val retainedMarker =
                PendingTerminalSession(
                    activeSession = finishedSession,
                    finishedSession = finishedSession,
                    telemetry = null,
                    createdAt = requireNotNull(finishedSession.finishedAt),
                    terminalEvidenceSealed = false,
                    policyOutcome = null,
                    artifactBatch = RuntimeTerminalArtifactBatch(events = emptyList()),
                ).toMarker(PendingTerminalPhase.ROOT_CAUSE_ASSESSMENT)
            stores.usageSessionsState.value = listOf(finishedSession)
            stores.terminalOutboxState.value = listOf(retainedMarker)
            stores.afterCompleteTerminalOutbox = {
                stores.terminalOutboxState.value = listOf(retainedMarker)
            }
            val serviceStateStore = DefaultServiceStateStore()
            val coordinatorScope = monitorScope()
            val coordinator = createSessionCoordinator(stores, serviceStateStore, coordinatorScope)
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)

            assertTrue(runCatching { coordinator.handleStatusChange(AppStatus.Running, Mode.VPN) }.isFailure)
            var blockedAdmission: NetworkTransitionAdmission? = null
            coordinator.withNetworkTransitionAdmission { admission -> blockedAdmission = admission }
            assertNull(blockedAdmission)
            assertEquals(listOf(retainedMarker), stores.getPendingTerminalOutboxes())

            stores.afterCompleteTerminalOutbox = {}
            coordinator.handleStatusChange(AppStatus.Running, Mode.VPN)

            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            var retriedAdmission: NetworkTransitionAdmission? = null
            coordinator.withNetworkTransitionAdmission { admission -> retriedAdmission = admission }
            assertTrue(retriedAdmission != null)
            coordinatorScope.cancel()
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
            assertFalse(
                stores
                    .getPendingTerminalOutboxes()
                    .single()
                    .value
                    .contains("terminal-policy"),
            )
            val dependencyKey =
                stores.terminalOutboxState.value
                    .single { state -> state.key.startsWith(TerminalPolicyDependencyDurableStatePrefix) }
                    .key
            stores.rememberedPoliciesState.value = emptyList()
            firstScope.cancel()

            val restoredStateStore = DefaultServiceStateStore()
            val restoredScope = monitorScope()
            val restoredCoordinator = createSessionCoordinator(stores, restoredStateStore, restoredScope)
            restoredCoordinator.handleStatusChange(AppStatus.Halted, Mode.VPN)

            assertTrue(stores.getPendingTerminalOutboxes().isEmpty())
            assertNull(stores.getTerminalOutbox(dependencyKey))
            assertFalse(decodeAssessment(stores).terminalEvidenceSealed)
            restoredScope.cancel()
        }
}

internal abstract class RuntimeTerminalPersistenceTestSupport {
    protected suspend fun TestScope.assertRestartCreatesFreshSession(
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

    protected fun armTerminalFailure(
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

    protected fun TestScope.monitorScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, _ -> },
        )

    protected fun createMonitor(
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

    protected fun createArtifactPersister(
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

    protected fun createTerminalOutbox(stores: FakeDiagnosticsHistoryStores): RuntimeTerminalOutbox =
        RuntimeTerminalOutbox(
            usageHistoryStore = stores,
            outboxStore = stores,
            policyRecordStore = stores,
            artifactPersister = createArtifactPersister(stores),
            rememberedPolicySessionTracker =
                RememberedPolicySessionTracker(
                    rememberedNetworkPolicyStore =
                        DefaultRememberedNetworkPolicyStore(stores, TestDiagnosticsHistoryClock()),
                    policyHandoverEventStore = FakePolicyHandoverEventStore(),
                ),
        )

    protected fun createSessionCoordinator(
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

    protected fun emptyActiveConnectionPolicyStore(): ActiveConnectionPolicyStore =
        object : ActiveConnectionPolicyStore {
            override val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>> = MutableStateFlow(emptyMap())
        }

    protected fun activeRememberedPolicy(): ActiveConnectionPolicy =
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

    protected fun finalDataPlaneTelemetry(): ServiceTelemetrySnapshot =
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

    protected fun noisyEventsWithBackwardClockRecovery(): List<NativeSessionEventEntity> =
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

    protected fun transition(
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

    protected fun event(
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

    protected fun decodeAssessment(stores: FakeDiagnosticsHistoryStores): RuntimeRootCauseAssessment =
        RuntimeHistoryJson.decodeFromString(
            RuntimeRootCauseAssessment.serializer(),
            rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
        )

    protected fun rootCauseAssessments(stores: FakeDiagnosticsHistoryStores): List<NativeSessionEventEntity> =
        stores.nativeEventsState.value.filter { event -> event.source == RuntimeRootCauseAssessmentSource }

    protected fun finishedUsageSession(
        id: String,
        finishedAt: Long,
    ): BypassUsageSessionEntity =
        BypassUsageSessionEntity(
            id = id,
            startedAt = finishedAt - 100L,
            finishedAt = finishedAt,
            updatedAt = finishedAt,
            serviceMode = Mode.VPN.name,
            connectionState = AppStatus.Halted.name,
            approachProfileId = null,
            approachProfileName = null,
            strategyId = "strategy",
            strategyLabel = "Strategy",
            strategyJson = "{}",
            networkType = "unknown",
            txBytes = 0L,
            rxBytes = 0L,
            totalErrors = 0L,
            routeChanges = 0L,
            restartCount = 0,
            endedReason = "stopped",
        )

    protected companion object {
        const val ConnectionSessionId = "conn-a"
    }

    protected enum class TerminalFailurePhase {
        RUNTIME_EVENTS,
        TERMINAL_SAMPLE,
        POLICY_FINALIZATION,
        SESSION_UPSERT,
        ROOT_CAUSE_ASSESSMENT,
    }

    protected class MutableActiveConnectionPolicyStore : ActiveConnectionPolicyStore {
        private val state = MutableStateFlow<Map<Mode, ActiveConnectionPolicy>>(emptyMap())
        override val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>> = state

        fun set(policy: ActiveConnectionPolicy) {
            state.value = mapOf(policy.mode to policy)
        }
    }
}
