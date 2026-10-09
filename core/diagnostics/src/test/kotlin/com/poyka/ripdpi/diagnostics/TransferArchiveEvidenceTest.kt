package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferArchiveEvidenceTest {
    @Test
    fun `archive and text summary preserve validated transfer evidence`() {
        val evidence =
            TransferEvidence(
                runs =
                    listOf(
                        TransferMeasurement(
                            runIndex = 1,
                            runCount = 1,
                            receivedBodyByteCount = 3,
                            elapsedMs = 100,
                            firstBodyByteMs = 10,
                            lastBodyProgressMs = 20,
                            terminationReason = "idle_timeout",
                            samples = listOf(TransferProgressSample(0, 0), TransferProgressSample(20, 3)),
                        ),
                    ),
            )
        val details =
            listOf(ProbeDetail("transferEvidence", Json.encodeToString(TransferEvidence.serializer(), evidence)))
        val entity =
            ProbeResultEntity(
                "result",
                "session",
                "throughput_window",
                "private.example",
                "throughput_failed",
                Json.encodeToString(ListSerializer(ProbeDetail.serializer()), details),
                1,
            )
        val redacted = DiagnosticsArchiveRedactor(Json).redact(entity)
        val decoded = Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), redacted.detailJson)
        assertEquals(evidence, parseTransferEvidence(decoded))
        assertEquals("redacted", redacted.target)
        val lines =
            transferEvidenceSummaryLines(
                listOf(
                    ProbeResult(
                        "throughput_window",
                        "private.example",
                        "throughput_failed",
                        details + ProbeDetail("transferNetworkScope", "unverified"),
                    ),
                ),
            )
        assertNotNull(lines.singleOrNull())
        assertTrue(lines.single().contains("receivedBodyByteCount=3"))
        assertTrue(lines.single().contains("terminationReason=idle_timeout"))
        assertTrue(lines.single().contains("networkScope=unverified"))
        assertTrue(lines.none { it.contains("private.example") })
    }

    @Test
    fun `archive discards unvalidated nested transfer evidence`() {
        val details = listOf(ProbeDetail("transferEvidence", """{"version":1,"runs":[],"unexpected":"private-text"}"""))
        val entity =
            ProbeResultEntity(
                id = "transfer-result",
                sessionId = "transfer-session",
                probeType = "throughput_window",
                target = "private.example",
                outcome = "throughput_failed",
                detailJson = Json.encodeToString(ListSerializer(ProbeDetail.serializer()), details),
                createdAt = 1,
            )
        val result = DiagnosticsArchiveRedactor(Json).redact(entity)
        val decoded = Json.decodeFromString(ListSerializer(ProbeDetail.serializer()), result.detailJson)

        assertEquals("unavailable", decoded.single().value)
        assertEquals("redacted", result.target)
    }
}
