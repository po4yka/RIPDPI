package com.poyka.ripdpi.diagnostics.exit

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.FakeDiagnosticsContextProvider
import com.poyka.ripdpi.diagnostics.FakeDiagnosticsHistoryStores
import com.poyka.ripdpi.diagnostics.FakeNetworkMetadataProvider
import com.poyka.ripdpi.diagnostics.RuntimeArtifactPersister
import com.poyka.ripdpi.diagnostics.RuntimeHistoryJson
import com.poyka.ripdpi.diagnostics.RuntimeRootCauseAssessment
import com.poyka.ripdpi.diagnostics.RuntimeRootCauseAssessmentSource
import com.poyka.ripdpi.diagnostics.RuntimeRootCauseVerdict
import com.poyka.ripdpi.diagnostics.memory.NativeMemorySample
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitRuntimeReconcilerTest {
    @Test
    fun `Android memory limiter stays inconclusive without ordinary terminal seal`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value =
                listOf(
                    usageSession(id = "conn-old", startedAt = 10L, updatedAt = 40L),
                    usageSession(id = "conn-latest", startedAt = 50L, updatedAt = 100L),
                )
            val reconciler = reconciler(stores, startupAt = 180L)

            reconciler.reconcileStartupProcessExits(
                listOf(
                    exitEvent(
                        id = "application_exit:pid-4242:private-process",
                        createdAt = 130L,
                        reason = "other",
                        subtype = "android_memory_limiter",
                        importance = "service",
                    ),
                ),
            )

            val correlation = correlations(stores).single()
            val assessment = rootCauseAssessment(stores)
            val finalized = stores.usageSessionsState.value.single { session -> session.id == "conn-latest" }
            assertTrue(correlation.id.startsWith("application_exit_correlation:"))
            assertEquals(64, correlation.id.substringAfter(':').length)
            assertEquals("conn-latest", correlation.connectionSessionId)
            assertEquals("application_exit_correlation", correlation.source)
            assertEquals("process", correlation.subsystem)
            assertEquals(
                "event=process_exit_correlation verdict=inconclusive evidence=last_exit_inspector_v1 " +
                    "reason=other subtype=android_memory_limiter importance=service",
                correlation.message,
            )
            listOf("pid-4242", "private-process", "description", "trace").forEach { secret ->
                assertFalse(correlation.id.contains(secret))
                assertFalse(correlation.message.contains(secret))
            }
            assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
            assertFalse(assessment.terminalEvidenceSealed)
            assertEquals(130L, finalized.finishedAt)
            assertEquals("process_exit:inconclusive", finalized.endedReason)
            assertEquals("Failed", finalized.connectionState)
            assertEquals("degraded", finalized.health)
            assertEquals("Android reported a resource-pressure process exit.", finalized.failureMessage)
        }

    @Test
    fun `generic Android pressure exits stay inconclusive and do not seal ordinary evidence`() =
        runTest {
            listOf("low_memory", "excessive_resource_usage").forEach { reason ->
                val stores = FakeDiagnosticsHistoryStores()
                stores.usageSessionsState.value = listOf(usageSession(startedAt = 100L, updatedAt = 100L))
                val reconciler = reconciler(stores, startupAt = 200L)

                reconciler.reconcileStartupProcessExits(
                    listOf(exitEvent(createdAt = 150L, reason = reason)),
                )

                val correlation = correlations(stores).single()
                val assessment = rootCauseAssessment(stores)
                val finalized = stores.usageSessionsState.value.single()
                assertTrue(correlation.message.contains("verdict=inconclusive"))
                assertTrue(correlation.message.contains("reason=$reason"))
                assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, assessment.verdict)
                assertFalse(assessment.terminalEvidenceSealed)
                assertEquals("process_exit:inconclusive", finalized.endedReason)
            }
        }

    @Test
    fun `canonical Android exit reasons map to neutral or failed terminal lifecycle`() =
        runTest {
            val expectations =
                mapOf(
                    "unknown" to failedExit,
                    "exit_self" to neutralExit,
                    "signaled" to failedExit,
                    "low_memory" to pressureExit,
                    "crash" to failedExit,
                    "crash_native" to failedExit,
                    "anr" to failedExit,
                    "initialization_failure" to failedExit,
                    "permission_change" to neutralExit,
                    "excessive_resource_usage" to pressureExit,
                    "user_requested" to neutralExit,
                    "user_stopped" to neutralExit,
                    "dependency_died" to failedExit,
                    "other" to failedExit,
                    "freezer" to failedExit,
                    "package_state_change" to neutralExit,
                    "package_updated" to neutralExit,
                )

            expectations.forEach { (reason, expected) ->
                val stores = FakeDiagnosticsHistoryStores()
                stores.usageSessionsState.value = listOf(usageSession(startedAt = 100L, updatedAt = 100L))

                reconciler(stores, startupAt = 200L).reconcileStartupProcessExits(
                    listOf(exitEvent(createdAt = 150L, reason = reason)),
                )

                assertTrue(correlations(stores).single().message.contains("reason=$reason"))
                assertEquals(RuntimeRootCauseVerdict.INCONCLUSIVE, rootCauseAssessment(stores).verdict)
                assertFalse(rootCauseAssessment(stores).terminalEvidenceSealed)
                val finalized = stores.usageSessionsState.value.single()
                assertEquals(150L, finalized.finishedAt)
                assertEquals(expected.connectionState, finalized.connectionState)
                assertEquals(expected.health, finalized.health)
                assertEquals(expected.endedReason, finalized.endedReason)
                assertEquals(expected.failureMessage, finalized.failureMessage)
            }
        }

    @Test
    fun `newest canonical exit is matched first`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value = listOf(usageSession(startedAt = 100L, updatedAt = 100L))
            val reconciler = reconciler(stores, startupAt = 300L)

            reconciler.reconcileStartupProcessExits(
                listOf(
                    exitEvent(createdAt = 120L, reason = "low_memory"),
                    exitEvent(createdAt = 140L, reason = "excessive_resource_usage"),
                    exitEvent(createdAt = 160L, reason = "crash"),
                ),
            )

            val correlation = correlations(stores).single()
            assertTrue(correlation.message.contains("reason=crash"))
        }

    @Test
    fun `events outside bounds or malformed categories do not correlate`() =
        runTest {
            val disallowed =
                listOf(
                    exitEvent(createdAt = 90L, reason = "low_memory"),
                    exitEvent(createdAt = 210L, reason = "low_memory"),
                    exitEvent(createdAt = 120L, reason = "not_an_android_reason"),
                    exitEvent(createdAt = 121L, reason = "crash", subtype = "unexpected"),
                    exitEvent(createdAt = 122L, reason = "crash", importance = "not_an_importance"),
                )
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value = listOf(usageSession(startedAt = 100L, updatedAt = 100L))
            val reconciler = reconciler(stores, startupAt = 200L)

            reconciler.reconcileStartupProcessExits(disallowed)

            assertTrue(correlations(stores).isEmpty())
            assertTrue(rootCauseAssessments(stores).isEmpty())
            assertNull(
                stores
                    .usageSessionsState
                    .value
                    .single()
                    .finishedAt,
            )
        }

    @Test
    fun `window older than ten minutes does not correlate`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value = listOf(usageSession(startedAt = 0L, updatedAt = 0L))
            val reconciler = reconciler(stores, startupAt = 600_001L)

            reconciler.reconcileStartupProcessExits(
                listOf(exitEvent(createdAt = 0L, reason = "low_memory")),
            )

            assertTrue(correlations(stores).isEmpty())
            assertNull(
                stores
                    .usageSessionsState
                    .value
                    .single()
                    .finishedAt,
            )
        }

    @Test
    fun `events beyond the newly recorded sixteen are ignored`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value = listOf(usageSession(startedAt = 100L, updatedAt = 100L))
            val reconciler = reconciler(stores, startupAt = 400L)
            val firstSixteen =
                (0 until 16).map { index ->
                    exitEvent(createdAt = 110L + index.toLong(), reason = "not_an_android_reason")
                }

            reconciler.reconcileStartupProcessExits(
                firstSixteen + exitEvent(createdAt = 300L, reason = "low_memory"),
            )

            assertTrue(correlations(stores).isEmpty())
        }

    @Test
    fun `distinct exits close their nearest unfinished VPN sessions one to one`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value =
                listOf(
                    usageSession(id = "conn-old", startedAt = 10L, updatedAt = 10L),
                    usageSession(id = "conn-new", startedAt = 100L, updatedAt = 100L),
                )
            val reconciler = reconciler(stores, startupAt = 200L)

            reconciler.reconcileStartupProcessExits(
                listOf(
                    exitEvent(id = "application_exit:150:ordinal-0", createdAt = 150L, reason = "crash"),
                    exitEvent(id = "application_exit:90:ordinal-0", createdAt = 90L, reason = "user_requested"),
                ),
            )

            assertEquals(
                setOf("conn-old", "conn-new"),
                correlations(stores).mapNotNull { it.connectionSessionId }.toSet(),
            )
            assertEquals(2, correlations(stores).map { it.id }.distinct().size)
            val sessions = stores.usageSessionsState.value.associateBy { it.id }
            assertEquals(90L, sessions.getValue("conn-old").finishedAt)
            assertEquals(150L, sessions.getValue("conn-new").finishedAt)
        }

    @Test
    fun `replayed exit identity cannot close an older unfinished session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value =
                listOf(
                    usageSession(id = "conn-old", startedAt = 10L, updatedAt = 10L),
                    usageSession(id = "conn-new", startedAt = 100L, updatedAt = 100L),
                )
            val reconciler = reconciler(stores, startupAt = 200L)
            val exit =
                exitEvent(
                    id = "application_exit:150:ordinal-0",
                    createdAt = 150L,
                    reason = "user_stopped",
                )

            reconciler.reconcileStartupProcessExits(listOf(exit))
            reconciler.reconcileStartupProcessExits(listOf(exit))

            val sessions = stores.usageSessionsState.value.associateBy { it.id }
            assertEquals(150L, sessions.getValue("conn-new").finishedAt)
            assertNull(sessions.getValue("conn-old").finishedAt)
            assertEquals(1, correlations(stores).size)
            assertEquals("Stopped", sessions.getValue("conn-new").connectionState)
            assertEquals("process_exit:stopped", sessions.getValue("conn-new").endedReason)
            assertNull(sessions.getValue("conn-new").failureMessage)
        }

    @Test
    fun `retry correlation must exactly match its canonical privacy safe projection`() =
        runTest {
            val mutations =
                listOf<Pair<String, (NativeSessionEventEntity) -> NativeSessionEventEntity>>(
                    "source" to { event -> event.copy(source = "service") },
                    "subsystem" to { event -> event.copy(subsystem = "vpn") },
                    "message" to { event -> event.copy(message = "event=process_exit_correlation verdict=failed") },
                    "createdAt" to { event -> event.copy(createdAt = event.createdAt + 1L) },
                    "level" to { event -> event.copy(level = "error") },
                    "session" to { event -> event.copy(sessionId = "scan-forged") },
                    "runtime" to { event -> event.copy(runtimeId = "runtime-forged") },
                    "mode" to { event -> event.copy(mode = "vpn") },
                    "policy" to { event -> event.copy(policySignature = "policy-forged") },
                    "fingerprint" to { event -> event.copy(fingerprintHash = "fingerprint-forged") },
                    "missing connection" to { event -> event.copy(connectionSessionId = null) },
                    "unknown connection" to { event -> event.copy(connectionSessionId = "conn-forged") },
                )

            mutations.forEach { (name, mutate) ->
                val stores = FakeDiagnosticsHistoryStores()
                stores.usageSessionsState.value = listOf(usageSession(startedAt = 100L, updatedAt = 100L))
                val reconciler = reconciler(stores, startupAt = 200L)
                val exit = exitEvent(createdAt = 150L, reason = "crash")
                seedRetryCorrelation(stores, reconciler, exit)
                val canonical = correlations(stores).single()
                stores.nativeEventsState.value =
                    stores.nativeEventsState.value.map { event ->
                        if (event.id == canonical.id) mutate(event) else event
                    }

                reconciler.reconcileStartupProcessExits(listOf(exit))

                assertNull(
                    "$name must not close the session",
                    stores.usageSessionsState.value
                        .single()
                        .finishedAt,
                )
                assertTrue("$name must not persist an assessment", rootCauseAssessments(stores).isEmpty())
            }
        }

    @Test
    fun `retry correlation cannot target a session newer than the exit`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value =
                listOf(
                    usageSession(id = "conn-eligible", startedAt = 100L, updatedAt = 100L),
                    usageSession(id = "conn-future", startedAt = 160L, updatedAt = 160L),
                )
            val reconciler = reconciler(stores, startupAt = 200L)
            val exit = exitEvent(createdAt = 150L, reason = "crash")
            seedRetryCorrelation(stores, reconciler, exit)
            val canonical = correlations(stores).single()
            stores.nativeEventsState.value =
                stores.nativeEventsState.value.map { event ->
                    if (event.id == canonical.id) event.copy(connectionSessionId = "conn-future") else event
                }

            reconciler.reconcileStartupProcessExits(listOf(exit))

            stores.usageSessionsState.value.forEach { session -> assertNull(session.finishedAt) }
            assertTrue(rootCauseAssessments(stores).isEmpty())
        }

    @Test
    fun `same timestamp and ordinal with different safe categories have distinct identities`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value =
                listOf(
                    usageSession(id = "conn-a", startedAt = 100L, updatedAt = 100L),
                    usageSession(id = "conn-b", startedAt = 90L, updatedAt = 90L),
                )
            val sameSourceIdentity = "application_exit:150:ordinal-0"

            reconciler(stores, startupAt = 200L).reconcileStartupProcessExits(
                listOf(
                    exitEvent(id = sameSourceIdentity, createdAt = 150L, reason = "crash"),
                    exitEvent(id = sameSourceIdentity, createdAt = 150L, reason = "anr"),
                ),
            )

            assertEquals(2, correlations(stores).size)
            assertEquals(2, correlations(stores).map(NativeSessionEventEntity::id).distinct().size)
            assertEquals(2, stores.usageSessionsState.value.count { session -> session.finishedAt == 150L })
        }

    @Test
    fun `assessment failure leaves session unfinished and retry replaces correlation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            stores.usageSessionsState.value = listOf(usageSession(startedAt = 100L, updatedAt = 100L))
            var failAssessment = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.source == RuntimeRootCauseAssessmentSource && failAssessment) {
                    failAssessment = false
                    error("injected assessment failure")
                }
            }
            val reconciler = reconciler(stores, startupAt = 200L)
            val exit = exitEvent(createdAt = 150L, reason = "other", subtype = "android_memory_limiter")

            val failed =
                runCatching { reconciler.reconcileStartupProcessExits(listOf(exit)) }
                    .exceptionOrNull()
            assertTrue(failed is IllegalStateException)
            assertNull(
                stores
                    .usageSessionsState
                    .value
                    .single()
                    .finishedAt,
            )

            reconciler.reconcileStartupProcessExits(listOf(exit))

            assertEquals(1, correlations(stores).size)
            assertEquals(1, rootCauseAssessments(stores).size)
            assertEquals(
                150L,
                stores
                    .usageSessionsState
                    .value
                    .single()
                    .finishedAt,
            )
            assertEquals(
                "process_exit:inconclusive",
                stores
                    .usageSessionsState
                    .value
                    .single()
                    .endedReason,
            )
        }

    private fun reconciler(
        stores: FakeDiagnosticsHistoryStores,
        startupAt: Long,
    ): DefaultProcessExitRuntimeReconciler =
        DefaultProcessExitRuntimeReconciler(
            bypassUsageHistoryStore = stores,
            artifactReadStore = stores,
            artifactWriteStore = stores,
            runtimeArtifactPersister = artifactPersister(stores),
            clock = { startupAt },
        )

    private fun artifactPersister(stores: FakeDiagnosticsHistoryStores): RuntimeArtifactPersister =
        RuntimeArtifactPersister(
            artifactReadStore = stores,
            artifactWriteStore = stores,
            failureArtifactWriteStore = stores,
            historyRetentionStore = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = DefaultServiceStateStore(),
            nativeMemoryProbe = { NativeMemorySample(nativeHeapBytes = 0, processRssBytes = 0) },
        )

    private suspend fun seedRetryCorrelation(
        stores: FakeDiagnosticsHistoryStores,
        reconciler: DefaultProcessExitRuntimeReconciler,
        exit: NativeSessionEventEntity,
    ) {
        stores.beforeInsertNativeSessionEvent = { event ->
            if (event.source == RuntimeRootCauseAssessmentSource) error("injected assessment failure")
        }
        val failed = runCatching { reconciler.reconcileStartupProcessExits(listOf(exit)) }.exceptionOrNull()
        assertTrue(failed is IllegalStateException)
        stores.beforeInsertNativeSessionEvent = {}
        assertNull(
            stores.usageSessionsState.value
                .first()
                .finishedAt,
        )
    }

    private fun usageSession(
        id: String = "conn-a",
        startedAt: Long,
        updatedAt: Long,
        serviceMode: String = Mode.VPN.name,
        connectionState: String = AppStatus.Running.name,
    ): BypassUsageSessionEntity =
        BypassUsageSessionEntity(
            id = id,
            startedAt = startedAt,
            finishedAt = null,
            updatedAt = updatedAt,
            serviceMode = serviceMode,
            connectionState = connectionState,
            approachProfileId = null,
            approachProfileName = null,
            strategyId = "strategy",
            strategyLabel = "Strategy",
            strategyJson = "{}",
            networkType = "wifi",
            txBytes = 0,
            rxBytes = 0,
            totalErrors = 0,
            routeChanges = 0,
            restartCount = 0,
            endedReason = null,
        )

    private fun exitEvent(
        id: String = "application_exit:exit",
        createdAt: Long,
        reason: String,
        subtype: String = "none",
        importance: String = "service",
    ): NativeSessionEventEntity =
        NativeSessionEventEntity(
            id = id,
            sessionId = null,
            connectionSessionId = null,
            source = DefaultLastExitInspector.Source,
            level = "warn",
            message =
                "process_exit reason=$reason;subtype=$subtype;importance=$importance;pss=medium;rss=high",
            createdAt = createdAt,
            subsystem = DefaultLastExitInspector.Subsystem,
        )

    private fun correlations(stores: FakeDiagnosticsHistoryStores): List<NativeSessionEventEntity> =
        stores.nativeEventsState.value.filter { event -> event.source == ProcessExitCorrelationSource }

    private fun rootCauseAssessments(stores: FakeDiagnosticsHistoryStores): List<NativeSessionEventEntity> =
        stores.nativeEventsState.value.filter { event -> event.source == RuntimeRootCauseAssessmentSource }

    private fun rootCauseAssessment(stores: FakeDiagnosticsHistoryStores): RuntimeRootCauseAssessment =
        RuntimeHistoryJson.decodeFromString(
            RuntimeRootCauseAssessment.serializer(),
            rootCauseAssessments(stores).single().message.substringAfter("runtime_root_cause_assessment "),
        )

    private data class ExpectedTerminalExit(
        val connectionState: String,
        val health: String,
        val endedReason: String,
        val failureMessage: String?,
    )

    private companion object {
        val neutralExit =
            ExpectedTerminalExit(
                connectionState = "Stopped",
                health = "idle",
                endedReason = "process_exit:stopped",
                failureMessage = null,
            )
        val failedExit =
            ExpectedTerminalExit(
                connectionState = "Failed",
                health = "degraded",
                endedReason = "process_exit:inconclusive",
                failureMessage = "Android reported a process exit.",
            )
        val pressureExit =
            ExpectedTerminalExit(
                connectionState = "Failed",
                health = "degraded",
                endedReason = "process_exit:inconclusive",
                failureMessage = "Android reported a resource-pressure process exit.",
            )
    }
}
