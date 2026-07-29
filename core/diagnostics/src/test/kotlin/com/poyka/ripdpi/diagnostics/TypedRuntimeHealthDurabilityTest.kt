package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.memory.NativeMemorySample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedRuntimeHealthDurabilityTest {
    @Test
    fun `dns success recovery retries after failed or cancelled write`() =
        runTest {
            retryFailures().forEach { createFailure ->
                val fixture = armedDnsFailureFixture()

                fixture.assertDnsTransitionRetries(createFailure) {
                    persistRuntimeEvents(dnsTelemetry(queries = 3, failures = 2, updatedAt = 4L), ConnectionSessionId)
                }
            }
        }

    @Test
    fun `dns counter rollback retries after failed or cancelled write`() =
        runTest {
            retryFailures().forEach { createFailure ->
                val fixture = armedDnsFailureFixture()

                fixture.assertDnsTransitionRetries(createFailure) {
                    persistRuntimeEvents(dnsTelemetry(queries = 0, failures = 0, updatedAt = 4L), ConnectionSessionId)
                }
            }
        }

    @Test
    fun `terminal producer switch retries after failed or cancelled write`() =
        runTest {
            retryFailures().forEach { createFailure ->
                val fixture = armedProxyDnsFailureFixture()
                val terminalTelemetry =
                    dnsTelemetry(
                        proxyQueries = 3,
                        proxyFailures = 2,
                        tunnelQueries = 4,
                        tunnelFailures = 2,
                        updatedAt = 4L,
                        includeTerminalEvent = true,
                    )

                fixture.assertDnsTransitionRetries(createFailure) {
                    persistTerminalRuntimeEvents(terminalTelemetry, ConnectionSessionId)
                }
                assertEquals(1, fixture.terminalRuntimeEvents().size)
            }
        }

    @Test
    fun `terminal relay recovery retries after failed or cancelled write`() =
        runTest {
            retryFailures().forEach { createFailure ->
                val stores = FakeDiagnosticsHistoryStores()
                val persister = createArtifactPersister(stores)
                persister.persistRuntimeEvents(relayTelemetry(failed = true, updatedAt = 1L), ConnectionSessionId)
                var failNextRecovery = true
                stores.beforeInsertNativeSessionEvent = { event ->
                    if (failNextRecovery && event.isTypedRecovery("relay")) {
                        failNextRecovery = false
                        throw createFailure()
                    }
                }
                val terminalTelemetry = relayTelemetry(failed = false, updatedAt = 2L, includeTerminalEvent = true)

                val failure =
                    runCatching {
                        persister.persistTerminalRuntimeEvents(terminalTelemetry, ConnectionSessionId)
                    }.exceptionOrNull()
                persister.persistTerminalRuntimeEvents(terminalTelemetry, ConnectionSessionId)

                assertEquals(createFailure().javaClass, failure?.javaClass)
                assertEquals(
                    listOf(
                        "event=relay_runtime_state evidence=relay_health_transition_v1 " +
                            "state=running health=ok relay_failed=false",
                    ),
                    stores.typedRuntimeEvents("relay").map { event -> event.message },
                )
                assertEquals(1, stores.terminalRuntimeEvents().size)
            }
        }

    @Test
    fun `terminal snapshot applies dns threshold and relay failure transitions`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val persister = createArtifactPersister(stores)
            persister.persistRuntimeEvents(
                combinedTelemetry(queries = 0, failures = 0, failedRelay = false, updatedAt = 1L),
                ConnectionSessionId,
            )
            persister.persistRuntimeEvents(
                combinedTelemetry(queries = 1, failures = 1, failedRelay = false, updatedAt = 2L),
                ConnectionSessionId,
            )

            persister.persistTerminalRuntimeEvents(
                combinedTelemetry(
                    queries = 2,
                    failures = 2,
                    failedRelay = true,
                    updatedAt = 3L,
                    includeTerminalEvent = true,
                ),
                ConnectionSessionId,
            )

            assertEquals(
                "event=dns_runtime_state evidence=dns_counter_transition_v1 state=failure_threshold",
                stores.typedRuntimeEvents("dns").single().message,
            )
            assertTrue(
                stores
                    .typedRuntimeEvents("relay")
                    .single()
                    .message
                    .contains("relay_failed=true"),
            )
            assertEquals(1, stores.terminalRuntimeEvents().size)
        }

    private suspend fun armedDnsFailureFixture(): RetryFixture {
        val stores = FakeDiagnosticsHistoryStores()
        val persister = createArtifactPersister(stores)
        persister.persistRuntimeEvents(dnsTelemetry(queries = 0, failures = 0, updatedAt = 1L), ConnectionSessionId)
        persister.persistRuntimeEvents(dnsTelemetry(queries = 1, failures = 1, updatedAt = 2L), ConnectionSessionId)
        persister.persistRuntimeEvents(dnsTelemetry(queries = 2, failures = 2, updatedAt = 3L), ConnectionSessionId)
        return RetryFixture(stores, persister)
    }

    private suspend fun armedProxyDnsFailureFixture(): RetryFixture {
        val stores = FakeDiagnosticsHistoryStores()
        val persister = createArtifactPersister(stores)
        persister.persistRuntimeEvents(dnsTelemetry(1, 0, 0, 0, 1L), ConnectionSessionId)
        persister.persistRuntimeEvents(dnsTelemetry(2, 1, 0, 0, 2L), ConnectionSessionId)
        persister.persistRuntimeEvents(dnsTelemetry(3, 2, 0, 0, 3L), ConnectionSessionId)
        return RetryFixture(stores, persister)
    }

    private data class RetryFixture(
        val stores: FakeDiagnosticsHistoryStores,
        val persister: RuntimeArtifactPersister,
    ) {
        suspend fun assertDnsTransitionRetries(
            createFailure: () -> Throwable,
            transition: suspend RuntimeArtifactPersister.() -> Unit,
        ) {
            var failNextRecovery = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (failNextRecovery && event.isTypedRecovery("dns")) {
                    failNextRecovery = false
                    throw createFailure()
                }
            }

            val failure = runCatching { persister.transition() }.exceptionOrNull()
            persister.transition()

            assertEquals(createFailure().javaClass, failure?.javaClass)
            assertEquals(
                listOf("event=dns_runtime_state evidence=dns_counter_transition_v1 state=recovered"),
                stores.typedRuntimeEvents("dns").map { event -> event.message },
            )
        }

        fun terminalRuntimeEvents() = stores.terminalRuntimeEvents()
    }
}

private fun retryFailures(): List<() -> Throwable> =
    listOf(
        { IllegalStateException("injected typed runtime write failure") },
        { CancellationException("injected typed runtime write cancellation") },
    )

private fun createArtifactPersister(stores: FakeDiagnosticsHistoryStores): RuntimeArtifactPersister =
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

private fun FakeDiagnosticsHistoryStores.typedRuntimeEvents(subsystem: String) =
    nativeEventsState.value.filter { event ->
        event.source == "service_telemetry_state" && event.subsystem == subsystem
    }

private fun FakeDiagnosticsHistoryStores.terminalRuntimeEvents() =
    nativeEventsState.value.filter { event -> event.id.startsWith("runtime_terminal_event:$ConnectionSessionId:") }

private fun com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity.isTypedRecovery(subsystem: String): Boolean =
    this.subsystem == subsystem &&
        when (subsystem) {
            "dns" -> message.contains("state=recovered")
            "relay" -> message.contains("relay_failed=false")
            else -> false
        }

private fun dnsTelemetry(
    queries: Long,
    failures: Long,
    updatedAt: Long,
): ServiceTelemetrySnapshot =
    ServiceTelemetrySnapshot(
        tunnelTelemetry =
            NativeRuntimeSnapshot(
                source = "tunnel",
                dnsQueriesTotal = queries,
                dnsFailuresTotal = failures,
            ),
        updatedAt = updatedAt,
    )

private fun dnsTelemetry(
    proxyQueries: Long,
    proxyFailures: Long,
    tunnelQueries: Long,
    tunnelFailures: Long,
    updatedAt: Long,
    includeTerminalEvent: Boolean = false,
): ServiceTelemetrySnapshot =
    ServiceTelemetrySnapshot(
        proxyTelemetry =
            NativeRuntimeSnapshot(
                source = "proxy",
                dnsQueriesTotal = proxyQueries,
                dnsFailuresTotal = proxyFailures,
                nativeEvents = terminalEvents(includeTerminalEvent),
            ),
        tunnelTelemetry =
            NativeRuntimeSnapshot(
                source = "tunnel",
                dnsQueriesTotal = tunnelQueries,
                dnsFailuresTotal = tunnelFailures,
            ),
        updatedAt = updatedAt,
    )

private fun relayTelemetry(
    failed: Boolean,
    updatedAt: Long,
    includeTerminalEvent: Boolean = false,
): ServiceTelemetrySnapshot =
    combinedTelemetry(
        queries = 0,
        failures = 0,
        failedRelay = failed,
        updatedAt = updatedAt,
        includeTerminalEvent = includeTerminalEvent,
    )

private fun combinedTelemetry(
    queries: Long,
    failures: Long,
    failedRelay: Boolean,
    updatedAt: Long,
    includeTerminalEvent: Boolean = false,
): ServiceTelemetrySnapshot =
    ServiceTelemetrySnapshot(
        proxyTelemetry =
            NativeRuntimeSnapshot(
                source = "proxy",
                nativeEvents = terminalEvents(includeTerminalEvent),
            ),
        tunnelTelemetry =
            NativeRuntimeSnapshot(
                source = "tunnel",
                dnsQueriesTotal = queries,
                dnsFailuresTotal = failures,
            ),
        relayTelemetry =
            NativeRuntimeSnapshot(
                source = "relay",
                state = if (failedRelay) "failed" else "running",
                health = if (failedRelay) "failed" else "ok",
            ),
        relayTelemetryStatus =
            RuntimeTelemetryStatus(
                state = if (failedRelay) RuntimeTelemetryState.EngineError else RuntimeTelemetryState.Snapshot,
            ),
        updatedAt = updatedAt,
    )

private fun terminalEvents(include: Boolean): List<NativeRuntimeEvent> =
    if (include) {
        listOf(
            NativeRuntimeEvent(
                source = "service",
                level = "info",
                message = "state=evidence_unavailable mode=vpn generation=1 final=true",
                createdAt = 1L,
                kind = "data_plane_final",
                subsystem = "data_plane",
            ),
        )
    } else {
        emptyList()
    }

private const val ConnectionSessionId = "typed-runtime-retry"
