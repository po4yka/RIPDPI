package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.buildRelaySequenceGaps
import com.poyka.ripdpi.diagnostics.export.selectRelayAttemptTraceEvents
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveRelayHydrationExporterTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `unsequenced relay internals do not create sequence gaps`() {
        val events =
            listOf(
                relayHydrationEvent(sequence = 1L, createdAt = 10L),
                relayHydrationEvent(
                    id = "relay-internal-unsequenced",
                    sequence = null,
                    createdAt = 11L,
                    stage = null,
                    outcome = null,
                    message = "internal relay telemetry",
                ),
                relayHydrationEvent(sequence = 2L, createdAt = 12L),
            )

        val retained = selectRelayAttemptTraceEvents(primaryEvents = events, globalEvents = emptyList())
        val gaps = buildRelaySequenceGaps(retained, events, emptyMap())

        assertEquals(listOf(1L, 2L), retained.map { it.attemptSequence })
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `source reported relay ring drops remain typed sequence gaps`() {
        val retained =
            listOf(
                relayHydrationEvent(sequence = 1L, createdAt = 10L),
                relayHydrationEvent(sequence = 3L, createdAt = 12L),
            )

        val gaps = buildRelaySequenceGaps(retained, retained, mapOf("connection-relay" to 1L))

        assertEquals("source_reported_connection_drop", gaps.single().reason)
    }

    @Test
    fun `createArchive hydrates complete relay attempt discovered from retained tail event`() =
        runTest {
            val fixture = relayHydrationFixture()

            val archive = createArchiveExporter(fixture.stores).createArchive(archiveRequest(fixture.session.id))

            ZipFile(archive.absolutePath).use { zip ->
                assertEquals(listOf(1L, 2L, 3L), relayTraceSequences(zip))
                assertTrue(relayTraceCompleteness(zip).getValue("sequenceGaps").jsonArray.isEmpty())
                assertCompletenessReason(
                    zip = zip,
                    code = "source_window_omitted",
                    count = DiagnosticsArchiveFormat.sessionEventLimit - 1,
                )
            }
        }

    @Test
    fun `createArchive omits oversized relay attempt instead of exporting partial trace`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = relaySession("session-relay-oversized-attempt", "Oversized relay attempt")
            seedSingleSessionStore(stores, session)
            stores.nativeEventsState.value =
                (1L..(DiagnosticsArchiveFormat.sessionEventLimit.toLong() + 1L)).map { sequence ->
                    relayHydrationEvent(
                        sessionId = session.id,
                        connectionSessionId = "connection-relay-oversized",
                        runtimeId = "runtime-relay-oversized",
                        attemptId = 7L,
                        sequence = sequence,
                        createdAt = sequence,
                    )
                }

            val archive = createArchiveExporter(stores).createArchive(archiveRequest(session.id))

            ZipFile(archive.absolutePath).use { zip ->
                assertTrue(relayTraceRecords(zip).isEmpty())
                assertEquals("0", relayTraceCompleteness(zip).getValue("retainedEventCount").jsonPrimitive.content)
                assertCompletenessReason(zip, "budget_omitted", 1)
            }
        }

    private suspend fun relayHydrationFixture(): RelayHydrationFixture {
        val stores = FakeDiagnosticsHistoryStores()
        val session = relaySession("session-relay-hydration-noise", "Relay hydration behind noise")
        seedSingleSessionStore(stores, session)
        stores.nativeEventsState.value = relayHydrationEvents(session.id)
        return RelayHydrationFixture(stores, session)
    }

    private fun relayHydrationEvents(sessionId: String): List<NativeSessionEventEntity> {
        val target = Triple("connection-relay-hidden", "runtime-relay-hidden", 42L)
        return listOf(relayTargetEvent(sessionId, target, 3L, 1_000L)) +
            List(DiagnosticsArchiveFormat.sessionEventLimit + 5) { index ->
                relayHydrationEvent(
                    id = "relay-noise-$index",
                    sessionId = sessionId,
                    connectionSessionId = "connection-noise",
                    runtimeId = "runtime-noise",
                    attemptId = index.toLong(),
                    sequence = 1L,
                    createdAt = 100L + index,
                )
            } +
            listOf(relayTargetEvent(sessionId, target, 1L, 10L), relayTargetEvent(sessionId, target, 2L, 11L))
    }

    private fun relayTargetEvent(
        sessionId: String,
        target: Triple<String, String, Long>,
        sequence: Long,
        createdAt: Long,
    ) = relayHydrationEvent(
        sessionId = sessionId,
        connectionSessionId = target.first,
        runtimeId = target.second,
        attemptId = target.third,
        sequence = sequence,
        createdAt = createdAt,
    )

    private fun relayTraceSequences(zip: ZipFile): List<Long> =
        relayTraceRecords(zip).map { record ->
            record
                .getValue("sequence")
                .jsonPrimitive.content
                .toLong()
        }

    private fun assertCompletenessReason(
        zip: ZipFile,
        code: String,
        count: Int,
    ) {
        val reasons = completenessRoot(zip).getValue("reasons").jsonArray
        assertTrue(
            reasons.any { reason ->
                val value = reason.jsonObject
                value.getValue("section").jsonPrimitive.content == "relay_attempt_traces" &&
                    value.getValue("code").jsonPrimitive.content == code &&
                    value.getValue("count").jsonPrimitive.content == count.toString()
            },
        )
    }

    private fun relayTraceRecords(zip: ZipFile): List<JsonObject> =
        zip
            .getInputStream(zip.getEntry("relay-attempt-traces.jsonl"))
            .bufferedReader()
            .readLines()
            .filter(String::isNotBlank)
            .map { line -> json.parseToJsonElement(line).jsonObject }

    private fun relayTraceCompleteness(zip: ZipFile): JsonObject =
        completenessRoot(zip).getValue("relayAttemptTraces").jsonObject

    private fun completenessRoot(zip: ZipFile): JsonObject =
        json
            .parseToJsonElement(
                zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
            ).jsonObject
}

private data class RelayHydrationFixture(
    val stores: FakeDiagnosticsHistoryStores,
    val session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity,
)

private fun relaySession(
    id: String,
    summary: String,
) = diagnosticsSession(
    id = id,
    profileId = "default",
    pathMode = ScanPathMode.IN_PATH.name,
    summary = summary,
).copy(serviceMode = "vpn")

private fun archiveRequest(sessionId: String) =
    DiagnosticsArchiveRequest(
        requestedSessionId = sessionId,
        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
        requestedAt = 24L,
    )

private fun relayHydrationEvent(
    id: String? = null,
    sessionId: String = "session-relay",
    connectionSessionId: String = "connection-relay",
    runtimeId: String = "runtime-relay",
    attemptId: Long = 1L,
    sequence: Long?,
    createdAt: Long,
    stage: String? = "tcp_connect",
    outcome: String? = "succeeded",
    message: String = "relay trace",
) = NativeSessionEventEntity(
    id = id ?: "relay-attempt-$attemptId-${sequence ?: "internal"}-$createdAt",
    sessionId = sessionId,
    connectionSessionId = connectionSessionId,
    source = "relay",
    level = "info",
    message = message,
    createdAt = createdAt,
    runtimeId = runtimeId,
    subsystem = "relay",
    attemptId = attemptId,
    attemptSequence = sequence,
    stage = stage,
    outcome = outcome,
)
