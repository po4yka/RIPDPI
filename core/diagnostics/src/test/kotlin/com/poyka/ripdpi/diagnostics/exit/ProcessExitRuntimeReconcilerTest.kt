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
    fun `correlates latest unfinished vpn session and finalizes after assessment`() =
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
                        reason = "low_memory",
                        subtype = "none",
                        importance = "service",
                    ),
                ),
            )

            val correlation = correlations(stores).single()
            val assessment = rootCauseAssessment(stores)
            val finalized = stores.usageSessionsState.value.single { session -> session.id == "conn-latest" }
            assertEquals("application_exit_correlation:conn-latest", correlation.id)
            assertEquals("conn-latest", correlation.connectionSessionId)
            assertEquals("application_exit_correlation", correlation.source)
            assertEquals("process", correlation.subsystem)
            assertEquals(
                "event=process_exit_correlation verdict=oem_process_kill evidence=last_exit_inspector_v1 " +
                    "reason=low_memory subtype=none importance=service",
                correlation.message,
            )
            listOf("pid-4242", "private-process", "description", "trace").forEach { secret ->
                assertFalse(correlation.id.contains(secret))
                assertFalse(correlation.message.contains(secret))
            }
            assertEquals(RuntimeRootCauseVerdict.OEM_PROCESS_KILL, assessment.verdict)
            assertTrue(assessment.terminalEvidenceSealed)
            assertEquals(130L, finalized.finishedAt)
            assertEquals("process_exit:oem_process_kill", finalized.endedReason)
        }

    @Test
    fun `only newest of first sixteen qualifying exits can correlate`() =
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
            assertTrue(correlation.message.contains("reason=excessive_resource_usage"))
        }

    @Test
    fun `events outside bounds or disallowed categories do not correlate`() =
        runTest {
            val disallowed =
                listOf(
                    exitEvent(createdAt = 90L, reason = "low_memory"),
                    exitEvent(createdAt = 210L, reason = "low_memory"),
                    exitEvent(createdAt = 120L, reason = "crash"),
                    exitEvent(createdAt = 121L, reason = "anr"),
                    exitEvent(createdAt = 122L, reason = "signaled"),
                    exitEvent(createdAt = 123L, reason = "user_requested"),
                    exitEvent(createdAt = 124L, reason = "user_stopped"),
                    exitEvent(createdAt = 125L, reason = "freezer"),
                    exitEvent(createdAt = 126L, reason = "unknown"),
                    exitEvent(createdAt = 127L, reason = "low_memory", importance = "cached"),
                    exitEvent(createdAt = 128L, reason = "other", subtype = "none"),
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
                    exitEvent(createdAt = 110L + index.toLong(), reason = "crash")
                }

            reconciler.reconcileStartupProcessExits(
                firstSixteen + exitEvent(createdAt = 300L, reason = "low_memory"),
            )

            assertTrue(correlations(stores).isEmpty())
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
        }

    private fun reconciler(
        stores: FakeDiagnosticsHistoryStores,
        startupAt: Long,
    ): DefaultProcessExitRuntimeReconciler =
        DefaultProcessExitRuntimeReconciler(
            bypassUsageHistoryStore = stores,
            artifactWriteStore = stores,
            runtimeArtifactPersister = artifactPersister(stores),
            clock = { startupAt },
        )

    private fun artifactPersister(stores: FakeDiagnosticsHistoryStores): RuntimeArtifactPersister =
        RuntimeArtifactPersister(
            artifactReadStore = stores,
            artifactWriteStore = stores,
            historyRetentionStore = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = DefaultServiceStateStore(),
            nativeMemoryProbe = { NativeMemorySample(nativeHeapBytes = 0, processRssBytes = 0) },
        )

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
}
