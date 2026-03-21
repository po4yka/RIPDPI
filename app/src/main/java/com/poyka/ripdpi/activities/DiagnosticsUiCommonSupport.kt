package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.decodeSummary
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanProgress
import java.util.Locale
import com.poyka.ripdpi.data.displayLabel as displayNetworkLabel
import com.poyka.ripdpi.diagnostics.displayLabel as displayStrategyLabel

internal fun DiagnosticsUiFactorySupport.decodeReport(reportJson: String?): com.poyka.ripdpi.diagnostics.ScanReport? =
    core.decodeReport(reportJson)

internal fun DiagnosticsUiFactorySupport.decodeRequest(profile: DiagnosticProfileEntity) =
    core.decodeRequest(profile)

internal fun DiagnosticsUiFactorySupport.decodeProbeDetails(detailJson: String) =
    core.decodeProbeDetails(detailJson)

internal fun DiagnosticsUiFactorySupport.decodeStrategySignature(payload: String?) =
    core.decodeStrategySignature(payload)

internal fun DiagnosticsUiFactorySupport.decodeContext(entity: com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity) =
    core.decodeContext(entity)

internal fun DiagnosticsUiFactorySupport.toProfileOptionUiModel(
    profile: DiagnosticProfileEntity,
): DiagnosticsProfileOptionUiModel {
    val request = decodeRequest(profile)
    return DiagnosticsProfileOptionUiModel(
        id = profile.id,
        name = profile.name,
        source = profile.source,
        kind = request?.kind ?: ScanKind.CONNECTIVITY,
        strategyProbeSuiteId = request?.strategyProbe?.suiteId,
    )
}

internal fun DiagnosticsUiFactorySupport.toSessionRowUiModel(
    session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity,
): DiagnosticsSessionRowUiModel = core.toSessionRowUiModel(session)

internal fun DiagnosticsUiFactorySupport.toProbeResultUiModel(
    index: Int,
    result: com.poyka.ripdpi.data.diagnostics.ProbeResultEntity,
): DiagnosticsProbeResultUiModel = core.toProbeResultUiModel(index, result)

internal fun DiagnosticsUiFactorySupport.toProbeResultUiModel(
    index: Int,
    result: com.poyka.ripdpi.diagnostics.ProbeResult,
): DiagnosticsProbeResultUiModel = core.toProbeResultUiModel(index, result)

internal fun DiagnosticsUiFactorySupport.toRememberedNetworkUiModel(
    policy: RememberedNetworkPolicyEntity,
    activeConnectionPolicy: ActiveConnectionPolicy?,
): DiagnosticsRememberedNetworkUiModel {
    val summary = policy.decodeSummary(core.json)
    val signature = decodeStrategySignature(policy.strategySignatureJson)
    val isCurrentMatch = activeConnectionPolicy?.matchedPolicy?.id == policy.id
    return DiagnosticsRememberedNetworkUiModel(
        id = policy.id,
        title = summary?.displayNetworkLabel() ?: "Network ${policy.fingerprintHash.take(12)}",
        subtitle =
            listOf(
                policy.mode.uppercase(Locale.US),
                policy.source.displaySourceLabel(),
                if (isCurrentMatch) "Current match" else null,
            ).filterNotNull().joinToString(" · "),
        status = policy.status.displayStatusLabel(),
        statusTone = policy.status.statusTone(),
        source = policy.source.displaySourceLabel(),
        strategyLabel = signature?.displayStrategyLabel() ?: "No strategy signature captured",
        lastValidatedLabel = policy.lastValidatedAt?.let(::formatTimestamp),
        lastAppliedLabel = policy.lastAppliedAt?.let(::formatTimestamp),
        successCount = policy.successCount,
        failureCount = policy.failureCount,
        isCurrentMatch = isCurrentMatch,
    )
}

internal fun DiagnosticsUiFactorySupport.toEventUiModel(
    event: com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity,
): DiagnosticsEventUiModel = core.toEventUiModel(event)

private val connectivityPhaseOrder = listOf("dns", "reachability", "tcp", "telegram")
private val strategyProbePhaseOrder = listOf("tcp", "quic")

private fun String.toPhaseLabel(): String =
    when (this) {
        "dns" -> "DNS"
        "reachability" -> "Reach"
        "tcp" -> "TCP"
        "telegram" -> "TG"
        "quic" -> "QUIC"
        else -> replaceFirstChar { it.uppercase() }
    }

private fun PhaseState.tone(): DiagnosticsTone =
    when (this) {
        PhaseState.Completed -> DiagnosticsTone.Positive
        PhaseState.Active -> DiagnosticsTone.Warning
        PhaseState.Pending -> DiagnosticsTone.Neutral
    }

internal fun DiagnosticsUiFactorySupport.toProgressUiModel(
    progress: ScanProgress,
    scanKind: ScanKind,
    isFullAudit: Boolean,
    scanStartedAt: Long,
    now: Long = System.currentTimeMillis(),
    completedProbes: List<CompletedProbeUiModel> = emptyList(),
): DiagnosticsProgressUiModel {
    val fraction =
        if (progress.totalSteps <= 0) {
            0f
        } else {
            progress.completedSteps.toFloat() / progress.totalSteps.toFloat()
        }
    val elapsedMs = (now - scanStartedAt).coerceAtLeast(0L)
    val elapsedLabel = formatDurationMs(elapsedMs)
    val etaLabel =
        if (fraction >= 0.1f && elapsedMs > 0L) {
            val etaMs = (elapsedMs / fraction * (1f - fraction)).toLong()
            "~${formatDurationMs(etaMs)} remaining"
        } else {
            null
        }
    val phaseOrder =
        if (scanKind == ScanKind.STRATEGY_PROBE) strategyProbePhaseOrder else connectivityPhaseOrder
    val isFinished = progress.phase == "finished"
    val currentIndex = phaseOrder.indexOf(progress.phase)
    val phaseSteps =
        phaseOrder.mapIndexed { index, phase ->
            val state =
                when {
                    isFinished -> PhaseState.Completed
                    currentIndex < 0 -> PhaseState.Pending
                    index < currentIndex -> PhaseState.Completed
                    index == currentIndex -> PhaseState.Active
                    else -> PhaseState.Pending
                }
            PhaseStepUiModel(
                label = phase.toPhaseLabel(),
                state = state,
                tone = state.tone(),
            )
        }
    return DiagnosticsProgressUiModel(
        phase = progress.phase,
        summary = progress.message,
        completedSteps = progress.completedSteps,
        totalSteps = progress.totalSteps,
        fraction = fraction,
        scanKind = scanKind,
        isFullAudit = isFullAudit,
        elapsedLabel = elapsedLabel,
        etaLabel = etaLabel,
        phaseSteps = phaseSteps,
        currentProbeLabel = progress.message,
        completedProbes = completedProbes,
    )
}

internal fun DiagnosticsUiFactorySupport.toneForOutcome(value: String): DiagnosticsTone = core.toneForOutcome(value)

internal fun scanCompletedTone(latestSession: DiagnosticsSessionRowUiModel?): DiagnosticsTone =
    latestSession?.tone ?: DiagnosticsTone.Neutral

internal fun String.displayStatusLabel(): String =
    when (lowercase(Locale.US)) {
        "validated" -> "Validated"
        "suppressed" -> "Suppressed"
        else -> "Observed"
    }

internal fun String.statusTone(): DiagnosticsTone =
    when (lowercase(Locale.US)) {
        "validated" -> DiagnosticsTone.Positive
        "suppressed" -> DiagnosticsTone.Warning
        else -> DiagnosticsTone.Info
    }

internal fun String.displaySourceLabel(): String =
    when (lowercase(Locale.US)) {
        "strategy_probe" -> "Strategy probe"
        else -> "Manual session"
    }

internal fun BypassApproachSummary.toDiagnosticsTone(): DiagnosticsTone =
    when {
        verificationState.equals("unverified", ignoreCase = true) -> DiagnosticsTone.Neutral
        (validatedSuccessRate ?: 0f) >= 0.75f -> DiagnosticsTone.Positive
        (validatedSuccessRate ?: 0f) > 0f -> DiagnosticsTone.Warning
        else -> DiagnosticsTone.Negative
    }

internal fun DiagnosticsUiFactorySupport.parsePathMode(value: String) = core.parsePathMode(value)

internal fun DiagnosticsUiFactorySupport.formatTimestamp(timestamp: Long): String = core.formatTimestamp(timestamp)

internal fun DiagnosticsUiFactorySupport.formatBytes(bytes: Long): String = core.formatBytes(bytes)

internal fun DiagnosticsUiFactorySupport.formatBps(bps: Long): String = core.formatBps(bps)

internal fun DiagnosticsUiFactorySupport.formatDurationMs(durationMs: Long): String = core.formatDurationMs(durationMs)

internal fun DiagnosticsUiFactorySupport.redactValue(value: String?): String = core.redactValue(value)

internal fun DiagnosticsUiFactorySupport.redactCollection(values: List<String>): String = core.redactCollection(values)

internal fun DiagnosticsSessionRowUiModel.matchesQuery(query: String): Boolean {
    if (query.isBlank()) {
        return true
    }
    val normalized = query.lowercase(Locale.US)
    return listOf(title, subtitle, summary, pathMode, serviceMode, status).any {
        it.lowercase(Locale.US).contains(normalized)
    }
}

internal fun DiagnosticsEventUiModel.matchesQuery(query: String): Boolean {
    if (query.isBlank()) {
        return true
    }
    val normalized = query.lowercase(Locale.US)
    return listOf(source, severity, message).any { it.lowercase(Locale.US).contains(normalized) }
}
