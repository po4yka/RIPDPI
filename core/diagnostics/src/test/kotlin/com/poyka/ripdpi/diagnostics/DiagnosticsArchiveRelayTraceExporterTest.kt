package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.memory.NativeMemorySample
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveRelayTraceExporterTest : DiagnosticsArchiveExporterTestBase() {
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

            assertEquals(15, archive.schemaVersion)
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
                                        createdAt = 101L,
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
                    updatedAt = 101L,
                )
            val connectionSessionId = "connection-relay-drop-accounting"

            persister.persistConnectionSample(connectionSessionId, telemetry)
            persister.persistRuntimeEvents(telemetry, connectionSessionId)
            persister.persistTerminalTelemetrySample(
                connectionSessionId = connectionSessionId,
                telemetry = telemetry,
                createdAt = 102L,
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
                        requestedAt = 103L,
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
}
