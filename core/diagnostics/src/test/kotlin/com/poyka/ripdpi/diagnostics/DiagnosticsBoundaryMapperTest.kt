package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.RawPathExecutionSettlement
import com.poyka.ripdpi.data.RawPathExecutionSettlementOutcome
import com.poyka.ripdpi.data.RawPathRuntimeContext
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.finalization.RawPathSettlementContextKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsBoundaryMapperTest {
    @Test
    fun `undecodable stored scan report maps to a session without a report`() {
        val mapper = DiagnosticsBoundaryMapper(Json { ignoreUnknownKeys = true })

        val session =
            mapper.toDiagnosticScanSession(
                ScanSessionEntity(
                    id = "scan-legacy",
                    profileId = "profile-1",
                    pathMode = "RAW_PATH",
                    serviceMode = null,
                    status = "completed",
                    summary = "Stored summary",
                    reportJson = """{"schemaVersion":5,"sessionId":"scan-legacy"}""",
                    startedAt = 1L,
                    finishedAt = 2L,
                ),
            )

        assertEquals("scan-legacy", session.id)
        assertNull(session.report)
    }

    @Test
    fun `undecodable stored profile request maps to a profile without a request`() {
        val mapper = DiagnosticsBoundaryMapper(Json { ignoreUnknownKeys = true })

        val profile =
            mapper.toDiagnosticProfile(
                DiagnosticProfileEntity(
                    id = "profile-1",
                    name = "Legacy",
                    source = "bundled",
                    version = 1,
                    requestJson = """{"profileId":"profile-1"}""",
                    updatedAt = 1L,
                ),
            )

        assertEquals("profile-1", profile.id)
        assertNull(profile.request)
    }

    @Test
    fun `expected raw path settlement does not log a context decode warning`() {
        val json = Json { ignoreUnknownKeys = true }
        val result =
            RawPathExecutionResult(
                settlement =
                    RawPathExecutionSettlement(
                        rawWindowGeneration = 7L,
                        resumeIntentGeneration = 8L,
                        outcome = RawPathExecutionSettlementOutcome.RestoreNotRequired,
                        runtimeWasRunning = false,
                        resumeRequired = false,
                        postRuntimeContext = RawPathRuntimeContext(),
                    ),
                executionOutcome = RawPathExecutionOutcome.Completed,
            )
        val logWriter = RecordingLogWriter()
        val previousWriters = Logger.mutableConfig.logWriterList
        val previousSeverity = Logger.mutableConfig.minSeverity

        try {
            Logger.setLogWriters(logWriter)
            Logger.setMinSeverity(Severity.Verbose)

            val mapped =
                DiagnosticsBoundaryMapper(json).toDiagnosticContextSnapshot(
                    DiagnosticContextEntity(
                        id = "raw-path-settlement:scan-1",
                        sessionId = "scan-1",
                        contextKind = RawPathSettlementContextKind,
                        payloadJson = json.encodeToString(result),
                        capturedAt = 10L,
                    ),
                )

            assertEquals(
                SettlementMappingObservation(
                    context = null,
                    rawPathExecutionResult = result,
                    warningMessages = emptyList(),
                ),
                SettlementMappingObservation(
                    context = mapped.context,
                    rawPathExecutionResult = mapped.rawPathExecutionResult,
                    warningMessages = logWriter.warningMessages,
                ),
            )
        } finally {
            Logger.setLogWriters(previousWriters)
            Logger.setMinSeverity(previousSeverity)
        }
    }
}

private data class SettlementMappingObservation(
    val context: DiagnosticContextModel?,
    val rawPathExecutionResult: RawPathExecutionResult?,
    val warningMessages: List<String>,
)

private class RecordingLogWriter : LogWriter() {
    val warningMessages = mutableListOf<String>()

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        if (severity == Severity.Warn) {
            warningMessages += message
        }
    }
}
