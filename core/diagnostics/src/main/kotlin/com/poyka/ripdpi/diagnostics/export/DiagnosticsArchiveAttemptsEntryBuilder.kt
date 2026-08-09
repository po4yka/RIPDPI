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
        rawReport: EngineScanReportWire?,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    ): DiagnosticsArchiveEntry =
        DiagnosticsArchiveEntry(
            name = name,
            bytes = buildJsonLines(sessionId, profileId, report, rawReport, targetAliases).toByteArray(),
        )

    private fun buildJsonLines(
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
        rawReport: EngineScanReportWire?,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    ): String =
        report
            ?.strategyProbeReport
            ?.attempts
            .orEmpty()
            .mapIndexed { index, attempt ->
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
                        targetAlias =
                            targetAliases.aliasFor(
                                rawReport
                                    ?.strategyProbeReport
                                    ?.attempts
                                    ?.getOrNull(index)
                                    ?.target,
                            ) ?: UnknownTargetAlias,
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
                record
            }.joinToString(separator = "", postfix = "") { record ->
                json.encodeToJsonElement(StrategyAttemptArchiveRecord.serializer(), record).toString() + "\n"
            }
}

private const val UnknownTargetAlias = "target-unknown"
