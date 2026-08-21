package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.export.buildRelaySequenceGaps
import com.poyka.ripdpi.diagnostics.memory.NativeMemorySample
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveRelayTraceExporterTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `relay gap reason ignores drops from another connection`() {
        fun event(sequence: Long) =
            NativeSessionEventEntity(
                id = "event-$sequence",
                sessionId = "session-a",
                connectionSessionId = "connection-a",
                source = "relay",
                level = "info",
                message = "relay stage",
                createdAt = sequence,
                runtimeId = "runtime-a",
                subsystem = "relay",
                attemptId = 1L,
                attemptSequence = sequence,
                stage = "tcp_connect",
                outcome = "succeeded",
            )
        val events = listOf(event(1L), event(3L))

        val gaps =
            buildRelaySequenceGaps(
                retainedEvents = events,
                allEvents = events,
                droppedEventsByConnection = mapOf("connection-b" to 9L),
            )

        assertEquals("retention_or_source_gap", gaps.single().reason)
    }

    @Test
    fun `unsupported only relay attempt remains visible in completeness`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-unsupported-relay-attempt",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Unsupported relay attempt",
                )
            seedSingleSessionStore(stores, session)
            val unsupported =
                NativeSessionEventEntity(
                    id = "unsupported-only",
                    sessionId = session.id,
                    connectionSessionId = "connection-a",
                    source = "relay",
                    level = "info",
                    message = "future relay stage",
                    createdAt = 1L,
                    runtimeId = "runtime-a",
                    subsystem = "relay",
                    attemptId = 1L,
                    attemptSequence = 1L,
                    stage = "future_stage",
                    outcome = "future_outcome",
                )
            stores.nativeEventsState.value = listOf(unsupported)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 24L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val completeness =
                    json
                        .parseToJsonElement(
                            zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
                        ).jsonObject
                val trace = completeness.getValue("relayAttemptTraces").jsonObject
                val reasons = completeness.getValue("reasons").jsonArray

                assertEquals("1", trace.getValue("unsupportedAttemptCount").jsonPrimitive.content)
                assertTrue(
                    reasons.any { reason ->
                        val value = reason.jsonObject
                        value.getValue("code").jsonPrimitive.content == "unsupported_attempt" &&
                            value.getValue("count").jsonPrimitive.content == "1"
                    },
                )
            }
        }

    @Test
    fun `createArchive keeps retained relay trace count and explains sequence gaps without raw identifiers`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-relay-trace-gaps",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Relay trace gaps",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            val rawConnectionId = "connection-session-private-7"
            val rawRuntimeId = "runtime-relay-private-7"
            stores.nativeEventsState.value = relayTraceGapEvents(session.id, rawConnectionId, rawRuntimeId)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 24L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                assertRelayTraceGapArchive(zip, rawConnectionId, rawRuntimeId)
            }
        }

    private fun relayTraceGapEvents(
        sessionId: String,
        rawConnectionId: String,
        rawRuntimeId: String,
    ): List<NativeSessionEventEntity> =
        listOf(
            relayTraceGapEvent(sessionId, rawConnectionId, rawRuntimeId, 10, "relay_stream", "succeeded"),
            relayTraceGapEvent(
                sessionId,
                rawConnectionId,
                rawRuntimeId,
                1,
                "unsupported_stage",
                "unsupported_outcome",
                message = "unexportable typed relay event",
            ),
            relayTraceGapEvent(sessionId, rawConnectionId, rawRuntimeId, 7, "reality_tls", "succeeded"),
            relayTraceGapEvent(sessionId, rawConnectionId, rawRuntimeId, 6, "tcp_connect", "started"),
        )

    private fun relayTraceGapEvent(
        sessionId: String,
        rawConnectionId: String,
        rawRuntimeId: String,
        sequence: Long,
        stage: String,
        outcome: String,
        message: String = "relay trace",
    ) = NativeSessionEventEntity(
        id = "relay-trace-event-$sequence",
        sessionId = sessionId,
        connectionSessionId = rawConnectionId,
        source = "relay",
        level = "info",
        message = message,
        createdAt = 100L + sequence,
        runtimeId = rawRuntimeId,
        subsystem = "relay",
        attemptId = 7L,
        attemptSequence = sequence,
        stage = stage,
        outcome = outcome,
    )

    private fun assertRelayTraceGapArchive(
        zip: ZipFile,
        rawConnectionId: String,
        rawRuntimeId: String,
    ) {
        val traceRaw = zip.getInputStream(zip.getEntry("relay-attempt-traces.jsonl")).bufferedReader().readText()
        val records =
            traceRaw
                .lineSequence()
                .filter(String::isNotBlank)
                .map { line -> json.parseToJsonElement(line).jsonObject }
                .toList()
        val completenessRaw = zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText()
        val traceCompleteness =
            json
                .parseToJsonElement(completenessRaw)
                .jsonObject
                .getValue("relayAttemptTraces")
                .jsonObject

        assertRelayTraceRecords(records, traceCompleteness.getValue("retainedEventCount").jsonPrimitive.content)
        assertRelaySequenceGaps(traceCompleteness.getValue("sequenceGaps").jsonArray)
        assertFalse(traceRaw.contains(rawConnectionId))
        assertFalse(traceRaw.contains(rawRuntimeId))
        assertFalse(completenessRaw.contains(rawConnectionId))
        assertFalse(completenessRaw.contains(rawRuntimeId))
    }

    private fun assertRelayTraceRecords(
        records: List<kotlinx.serialization.json.JsonObject>,
        retainedEventCount: String,
    ) {
        assertEquals(
            listOf(6L, 7L, 10L),
            records.map {
                it
                    .getValue("sequence")
                    .jsonPrimitive.content
                    .toLong()
            },
        )
        assertEquals(3, records.size)
        assertEquals(records.size.toString(), retainedEventCount)

        val attemptRefs = records.map { it.getValue("attemptRef").jsonPrimitive.content }
        assertTrue(attemptRefs.all { it.matches(Regex("attempt-[0-9]+")) })
        assertEquals(1, attemptRefs.toSet().size)
    }

    private fun assertRelaySequenceGaps(sequenceGaps: kotlinx.serialization.json.JsonArray) {
        assertEquals(
            setOf("1-1", "2-5", "8-9"),
            sequenceGaps
                .map { gap ->
                    "${gap.jsonObject.getValue("from").jsonPrimitive.content}-" +
                        gap.jsonObject
                            .getValue("to")
                            .jsonPrimitive.content
                }.toSet(),
        )
        assertTrue(
            sequenceGaps.any { gap ->
                gap.jsonObject
                    .getValue("reason")
                    .jsonPrimitive.content == "unsupported_event"
            },
        )
    }

    @Test
    fun `createArchive exports ordered privacy safe partial VLESS Reality attempt trace`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-vless-reality-partial",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Partial relay failure",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            val rawUuid = "11111111-1111-1111-1111-111111111111"
            val rawEndpoint = "203.0.113.9:443"
            val rawCredential = "password=super-secret-token"

            fun stageEvent(
                sequence: Long,
                stage: String,
                outcome: String,
                failure: Boolean = false,
            ) = NativeSessionEventEntity(
                id = "relay-stage-$sequence",
                sessionId = session.id,
                connectionSessionId = "connection-session-7",
                source = "relay",
                level = if (failure) "error" else "info",
                message =
                    if (failure) {
                        "Reality failed uuid=$rawUuid endpoint=$rawEndpoint $rawCredential"
                    } else {
                        "observed relay stage"
                    },
                createdAt = 100L + sequence,
                runtimeId = "runtime-relay-7",
                subsystem = "relay",
                attemptId = 7,
                attemptSequence = sequence,
                stage = stage,
                outcome = outcome,
                durationMs = sequence * 10,
                failureStage = if (failure) "reality_tls" else null,
                failureClass = if (failure) "tls_handshake_failure" else null,
                ioErrorKind = if (failure) "connection_refused" else null,
            )
            stores.nativeEventsState.value =
                listOf(
                    stageEvent(3, "reality_tls", "started"),
                    stageEvent(1, "tcp_connect", "started"),
                    stageEvent(4, "reality_tls", "failed", failure = true),
                    stageEvent(2, "tcp_connect", "succeeded"),
                )

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 24L,
                    ),
                )

            assertEquals(12, archive.schemaVersion)
            ZipFile(archive.absolutePath).use { zip ->
                val traceEntry = zip.getEntry("relay-attempt-traces.jsonl")
                assertNotNull(traceEntry)
                val trace = zip.getInputStream(requireNotNull(traceEntry)).bufferedReader().readText()
                val records =
                    trace
                        .lineSequence()
                        .filter(String::isNotBlank)
                        .map { line -> json.parseToJsonElement(line).jsonObject }
                        .toList()
                assertEquals(
                    listOf(
                        "tcp_connect:started",
                        "tcp_connect:succeeded",
                        "reality_tls:started",
                        "reality_tls:failed",
                    ),
                    records.map { record ->
                        "${record.getValue("stage").jsonPrimitive.content}:" +
                            record.getValue("outcome").jsonPrimitive.content
                    },
                )
                val failure = records.last()
                assertEquals("reality_tls", failure.getValue("failureStage").jsonPrimitive.content)
                assertEquals("tls_handshake_failure", failure.getValue("failureClass").jsonPrimitive.content)
                assertEquals("connection_refused", failure.getValue("ioErrorKind").jsonPrimitive.content)
                assertEquals("not_established", failure.getValue("causalInference").jsonPrimitive.content)
                assertTrue(listOf(rawUuid, rawEndpoint, rawCredential).none(trace::contains))

                val completeness =
                    json
                        .parseToJsonElement(
                            zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
                        ).jsonObject
                val traceCompleteness = completeness.getValue("relayAttemptTraces").jsonObject
                assertEquals("4", traceCompleteness.getValue("retainedEventCount").jsonPrimitive.content)
            }
        }

    @Test
    fun `createArchive counts cumulative relay drops once across live and terminal persistence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-relay-drop-accounting",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Relay drop accounting",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            val persister =
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
            val telemetry =
                ServiceTelemetrySnapshot(
                    relayTelemetry =
                        NativeRuntimeSnapshot(
                            source = "relay",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "relay",
                                        level = "info",
                                        message = "event=relay_attempt_stage",
                                        createdAt = 15L,
                                        kind = "relay_attempt_stage",
                                        runtimeId = "7",
                                        subsystem = "relay",
                                        attemptId = 7L,
                                        attemptSequence = 1L,
                                        stage = "tcp_connect",
                                        outcome = "succeeded",
                                    ),
                                ),
                            nativeEventsDropped = 7L,
                        ),
                    updatedAt = 15L,
                )
            val connectionSessionId = "connection-relay-drop-accounting"

            persister.persistConnectionSample(connectionSessionId, telemetry)
            persister.persistRuntimeEvents(telemetry, connectionSessionId)
            persister.persistTerminalTelemetrySample(
                connectionSessionId = connectionSessionId,
                telemetry = telemetry,
                createdAt = 16L,
                networkTypeFallback = "wifi",
                publicIpFallback = null,
                connectionState = "Stopped",
            )
            persister.persistTerminalRuntimeEvents(telemetry, connectionSessionId)

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 17L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val completeness =
                    json.decodeFromString(
                        DiagnosticsArchiveCompletenessPayload.serializer(),
                        zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
                    )
                assertEquals(1, completeness.relayAttemptTraces.retainedEventCount)
                assertEquals(7L, completeness.relayAttemptTraces.droppedEventCount)
            }
        }

    @Test
    fun `createArchive exports privacy safe relay health decision provenance`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-relay-health-decision",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Relay health decision",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            stores.nativeEventsState.value =
                listOf(relayHealthDecisionEvent())

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 17L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val trace =
                    zip
                        .getInputStream(zip.getEntry("relay-health-decisions.jsonl"))
                        .bufferedReader()
                        .readText()
                val record = json.parseToJsonElement(trace.trim()).jsonObject
                assertEquals(
                    listOf(
                        "attempt-1",
                        "fixture-opaque-profile-token",
                        "vless_reality",
                        "vless_auth",
                        "application_http",
                        "42",
                        "confirmed_failed",
                        "persistent_network",
                        "completed",
                        "unavailable",
                        "runtime-1",
                        "not_established",
                    ),
                    listOf(
                        record.getValue("attemptId").jsonPrimitive.content,
                        record.getValue("opaqueProfileId").jsonPrimitive.content,
                        record.getValue("transport").jsonPrimitive.content,
                        record.getValue("failureStage").jsonPrimitive.content,
                        record.getValue("targetCategory").jsonPrimitive.content,
                        record.getValue("positiveEvidenceWatermark").jsonPrimitive.content,
                        record.getValue("decision").jsonPrimitive.content,
                        record.getValue("cooldownScope").jsonPrimitive.content,
                        record.getValue("cleanupReceipt").jsonPrimitive.content,
                        record.getValue("connectionCorrelation").jsonPrimitive.content,
                        record.getValue("runtimeCorrelation").jsonPrimitive.content,
                        record.getValue("causalInference").jsonPrimitive.content,
                    ),
                )
                assertTrue(
                    listOf("dad-phone", "203.0.113.9:443", "super-secret-token").none(trace::contains),
                )
            }
        }

    private fun relayHealthDecisionEvent() =
        NativeSessionEventEntity(
            id = "relay-health-decision-attempt-1",
            sessionId = null,
            connectionSessionId = null,
            source = "app",
            level = "warn",
            message = "profile=dad-phone endpoint=203.0.113.9:443 password=super-secret-token",
            createdAt = 15L,
            runtimeId = "runtime-relay-1",
            subsystem = "relay_health_decision",
            healthAttemptId = "attempt-1",
            relayProfileToken = "fixture-opaque-profile-token",
            relayTransport = "vless_reality",
            failureStage = "vless_auth",
            relayTargetCategory = "application_http",
            positiveEvidenceWatermark = 42L,
            relayHealthDecision = "confirmed_failed",
            cooldownScope = "persistent_network",
            cleanupReceipt = "completed",
        )

    @Test
    fun `createArchive marks incomplete relay decision provenance unavailable`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-incomplete-relay-health-decision",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Incomplete relay health decision",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            stores.nativeEventsState.value =
                listOf(
                    NativeSessionEventEntity(
                        id = "incomplete-relay-health-decision",
                        source = "app",
                        level = "info",
                        message = "ssid=private endpoint=203.0.113.1 password=secret",
                        createdAt = 15L,
                        subsystem = "relay_health_decision",
                    ),
                )

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 17L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val trace =
                    zip
                        .getInputStream(zip.getEntry("relay-health-decisions.jsonl"))
                        .bufferedReader()
                        .readText()
                val record = json.parseToJsonElement(trace.trim()).jsonObject
                assertEquals(
                    List(10) { "unavailable" },
                    listOf(
                        record.getValue("attemptId").jsonPrimitive.content,
                        record.getValue("opaqueProfileId").jsonPrimitive.content,
                        record.getValue("transport").jsonPrimitive.content,
                        record.getValue("failureStage").jsonPrimitive.content,
                        record.getValue("targetCategory").jsonPrimitive.content,
                        record.getValue("decision").jsonPrimitive.content,
                        record.getValue("cooldownScope").jsonPrimitive.content,
                        record.getValue("cleanupReceipt").jsonPrimitive.content,
                        record.getValue("connectionCorrelation").jsonPrimitive.content,
                        record.getValue("runtimeCorrelation").jsonPrimitive.content,
                    ),
                )
                assertTrue(listOf("private", "203.0.113.1", "secret").none(trace::contains))
                val completeness =
                    json.decodeFromString(
                        DiagnosticsArchiveCompletenessPayload.serializer(),
                        zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
                    )
                assertEquals(1, completeness.relayAttemptTraces.retainedDecisionCount)
            }
        }
}
