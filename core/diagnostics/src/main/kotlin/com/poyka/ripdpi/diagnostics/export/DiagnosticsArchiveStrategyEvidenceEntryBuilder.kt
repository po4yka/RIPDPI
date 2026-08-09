package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal class DiagnosticsArchiveStrategyEvidenceEntryBuilder(
    private val redactor: DiagnosticsArchiveRedactor,
    private val json: Json,
) {
    private val attemptsEntryBuilder = DiagnosticsArchiveAttemptsEntryBuilder(json)
    private val capabilitiesEntryBuilder = DiagnosticsArchiveCapabilitiesEntryBuilder(json)
    private val emissionReceiptsEntryBuilder = DiagnosticsArchiveEmissionReceiptsEntryBuilder(json)
    private val protocolMilestonesEntryBuilder = DiagnosticsArchiveProtocolMilestonesEntryBuilder(json)
    private val resolverTraceEntryBuilder = DiagnosticsArchiveResolverTraceEntryBuilder(json)
    private val decisionTraceEntryBuilder = DiagnosticsArchiveDecisionTraceEntryBuilder(json)

    fun buildRoot(
        selection: DiagnosticsArchiveSelection,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    ): List<DiagnosticsArchiveEntry> {
        val report = redactor.redact(selection.primaryReport)
        val sessionId = selection.primarySession?.id
        val profileId = selection.primarySession?.profileId
        return buildList {
            add(buildExecutionPlanEntry("execution-plan.json", sessionId, profileId, report))
            add(capabilitiesEntryBuilder.buildRoot(selection, report))
            add(
                attemptsEntryBuilder.build(
                    "attempts.jsonl",
                    sessionId,
                    profileId,
                    report,
                    selection.primaryReport,
                    targetAliases,
                ),
            )
            add(emissionReceiptsEntryBuilder.buildRoot(selection, report))
            add(protocolMilestonesEntryBuilder.buildRoot(selection, report, targetAliases))
            add(resolverTraceEntryBuilder.buildRoot(selection, report, targetAliases))
            add(decisionTraceEntryBuilder.build("decision-trace.json", sessionId, profileId, report))
            add(buildStrategyMatrixEntry("strategy-matrix.json", sessionId, profileId, report))
        }
    }

    fun buildStage(
        prefix: String,
        stage: DiagnosticsArchiveCompositeStageSelection,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    ): List<DiagnosticsArchiveEntry> {
        val report = redactor.redact(stage.report)
        val sessionId = stage.session?.id
        val profileId = stage.session?.profileId
        return buildList {
            add(buildExecutionPlanEntry("$prefix/execution-plan.json", sessionId, profileId, report))
            add(capabilitiesEntryBuilder.buildStage(prefix, stage, report))
            add(
                attemptsEntryBuilder.build(
                    "$prefix/attempts.jsonl",
                    sessionId,
                    profileId,
                    report,
                    stage.report,
                    targetAliases,
                ),
            )
            add(emissionReceiptsEntryBuilder.buildStage(prefix, stage, report))
            add(protocolMilestonesEntryBuilder.buildStage(prefix, stage, report, targetAliases))
            add(resolverTraceEntryBuilder.buildStage(prefix, stage, report, targetAliases))
            add(decisionTraceEntryBuilder.build("$prefix/decision-trace.json", sessionId, profileId, report))
            add(buildStrategyMatrixEntry("$prefix/strategy-matrix.json", sessionId, profileId, report))
        }
    }

    private fun buildExecutionPlanEntry(
        name: String,
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
    ): DiagnosticsArchiveEntry =
        jsonEntry(
            name = name,
            serializer = ExecutionPlanArchivePayload.serializer(),
            value =
                ExecutionPlanArchivePayload(
                    sessionId = sessionId,
                    profileId = profileId,
                    executionPlan = report?.executionPlan,
                ),
        )

    private fun buildStrategyMatrixEntry(
        name: String,
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
    ): DiagnosticsArchiveEntry =
        jsonEntry(
            name = name,
            serializer = StrategyMatrixArchivePayload.serializer(),
            value =
                StrategyMatrixArchivePayload(
                    sessionId = sessionId,
                    profileId = profileId,
                    strategyProbeReport = report?.strategyProbeReport,
                ),
        )

    private fun <T> jsonEntry(
        name: String,
        serializer: KSerializer<T>,
        value: T,
    ): DiagnosticsArchiveEntry =
        DiagnosticsArchiveEntry(
            name = name,
            bytes = json.encodeToString(serializer, value).toByteArray(),
        )
}
