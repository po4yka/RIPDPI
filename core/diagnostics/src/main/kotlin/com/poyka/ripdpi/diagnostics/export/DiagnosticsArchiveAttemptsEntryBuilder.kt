package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

internal class DiagnosticsArchiveAttemptsEntryBuilder(
    private val json: Json,
) {
    fun build(
        name: String,
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
    ): DiagnosticsArchiveEntry =
        DiagnosticsArchiveEntry(
            name = name,
            bytes = buildJsonLines(sessionId, profileId, report).toByteArray(),
        )

    private fun buildJsonLines(
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
    ): String =
        report
            ?.strategyProbeReport
            ?.attempts
            .orEmpty()
            .joinToString(separator = "", postfix = "") { attempt ->
                val record =
                    StrategyAttemptArchiveRecord(
                        attemptVersion = attempt.attemptVersion,
                        sessionId = sessionId,
                        profileId = profileId,
                        sequence = attempt.sequence,
                        candidateIndex = attempt.candidateIndex,
                        candidateId = attempt.candidateId,
                        candidateLabel = attempt.candidateLabel,
                        candidateFamily = attempt.candidateFamily,
                        lane = attempt.lane,
                        targetAlias = "target-${attempt.targetIndex}",
                        isControl = attempt.isControl,
                        protocol = attempt.protocol,
                        round = attempt.round,
                        status = attempt.status,
                        startedAtMs = attempt.startedAtMs,
                        durationMs = attempt.durationMs,
                        retryCount = attempt.retryCount,
                        outcome = attempt.outcome,
                        reason = attempt.reason,
                    )
                json.encodeToJsonElement(StrategyAttemptArchiveRecord.serializer(), record).toString() + "\n"
            }
}
