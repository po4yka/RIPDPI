package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryConnectionDetailUiFactoryTest {
    private val factory = HistoryConnectionDetailUiFactory(DiagnosticsUiCoreSupport())

    @Test
    fun `connection detail maps highlights dedupes contexts and ignores malformed payloads`() {
        val detail =
            factory.toConnectionDetail(
                session = historyConnectionSession(finishedAt = null, health = "degraded"),
                snapshots =
                    listOf(
                        historySnapshot(connectionSessionId = "connection-1"),
                        NetworkSnapshotEntity(
                            id = "broken",
                            sessionId = null,
                            connectionSessionId = "connection-1",
                            snapshotKind = "passive",
                            payloadJson = "{broken",
                            capturedAt = 1L,
                        ),
                    ),
                contexts =
                    listOf(
                        historyContext(id = "context-1", connectionSessionId = "connection-1"),
                        historyContext(id = "context-2", connectionSessionId = "connection-1"),
                        DiagnosticContextEntity(
                            id = "broken",
                            sessionId = null,
                            connectionSessionId = "connection-1",
                            contextKind = "post_scan",
                            payloadJson = "{broken",
                            capturedAt = 1L,
                        ),
                    ),
                telemetry = listOf(historyTelemetry(connectionSessionId = "connection-1", createdAt = 999L)),
                events = listOf(historyEvent(connectionSessionId = "connection-1")),
            )

        assertEquals("connection-1", detail.session.id)
        assertEquals(DiagnosticsTone.Warning, detail.session.tone)
        assertTrue(detail.highlights.any { it.label == "Failure class" && it.value == "dns_tampering" })
        assertTrue(detail.highlights.any { it.label == "Strategy" && it.value == "hostfake + quic_burst" })
        assertTrue(detail.highlights.any { it.label == "Retries" && it.value == "3" })
        assertEquals(1, detail.snapshots.size)
        assertEquals(4, detail.contextGroups.size)
        assertEquals("Field telemetry", detail.contextGroups.last().title)
        assertTrue(detail.contextGroups.last().fields.any { it.label == "Network fingerprint" })
        assertEquals(1, detail.events.size)
    }

    @Test
    fun `connection row derives summary and duration metrics`() {
        val row =
            factory.toConnectionRowUiModel(
                historyConnectionSession(
                    state = "Failed",
                    mode = "Proxy",
                    health = "healthy",
                    failureMessage = "Socket closed",
                    startedAt = 0L,
                    finishedAt = 125_000L,
                    updatedAt = 125_000L,
                ),
            )

        assertEquals("Proxy failed", row.title)
        assertEquals("Socket closed", row.summary)
        assertTrue(row.metrics.any { it.label == "Duration" && it.value == "2m 5s" })
        assertEquals(DiagnosticsTone.Negative, row.tone)
    }
}
