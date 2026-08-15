@file:Suppress("TooManyFunctions")

package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassApproachVerificationState
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchTrigger
import com.poyka.ripdpi.diagnostics.DiagnosticsScanTriggerType
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.StrategyProbeProgressLane
import com.poyka.ripdpi.diagnostics.resolveLegalSafetyPolicy
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale
import com.poyka.ripdpi.data.displayLabel as displayNetworkLabel
import com.poyka.ripdpi.diagnostics.displayLabel as displayStrategyLabel

private const val FingerprintHashShortLength = 12
private const val FingerprintHashPrefixLength = 6
private const val FingerprintHashSuffixLength = 4
private const val SuccessRateHighThreshold = 0.75f

internal fun DiagnosticsUiFactorySupport.toProfileOptionUiModel(
    profile: DiagnosticProfile,
): DiagnosticsProfileOptionUiModel {
    val request = profile.request
    val policyDecision = request?.resolveLegalSafetyPolicy()
    return DiagnosticsProfileOptionUiModel(
        id = profile.id,
        name = profile.name,
        source = profile.source,
        kind = request?.kind ?: ScanKind.CONNECTIVITY,
        strategyProbeSuiteId = request?.strategyProbeSuiteId,
        family = request?.family ?: com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.GENERAL,
        regionTag = request?.regionTag,
        executionPolicy =
            DiagnosticsExecutionPolicyUiModel(
                manualOnly = request?.executionPolicy?.manualOnly == true,
                allowBackground = request?.executionPolicy?.allowBackground == true,
                requiresRawPath = request?.executionPolicy?.requiresRawPath == true,
                probePersistencePolicy =
                    request?.executionPolicy?.probePersistencePolicy
                        ?: com.poyka.ripdpi.diagnostics.ProbePersistencePolicy.MANUAL_ONLY,
            ),
        manualOnly = request?.executionPolicy?.manualOnly == true,
        packRefs = request?.packRefs.orEmpty().toImmutableList(),
        policyAccess =
            policyDecision?.access ?: com.poyka.ripdpi.diagnostics.DiagnosticsJurisdictionProfileAccess.ALLOWED,
        requiresExplicitConsent = policyDecision?.requiresExplicitConsent == true,
    )
}

internal fun DiagnosticsUiFactorySupport.toSessionRowUiModel(
    session: com.poyka.ripdpi.diagnostics.DiagnosticScanSession,
): DiagnosticsSessionRowUiModel {
    val base = core.toSessionRowUiModel(session)
    val summary = core.displaySessionSummary(context, session)
    return base.copy(
        title = summary,
        summary = summary,
    )
}

internal fun DiagnosticsUiFactorySupport.toProbeResultUiModel(
    index: Int,
    pathMode: com.poyka.ripdpi.diagnostics.ScanPathMode,
    result: com.poyka.ripdpi.diagnostics.ProbeResult,
    reportResults: List<com.poyka.ripdpi.diagnostics.ProbeResult> = emptyList(),
): DiagnosticsProbeResultUiModel = core.toProbeResultUiModel(index, pathMode, result, reportResults)

internal fun DiagnosticsUiFactorySupport.toDiagnosisUiModel(diagnosis: Diagnosis): DiagnosticsDiagnosisUiModel {
    val isConfirmGood = diagnosis.code == "confirm_good_dpi_suspected"
    val isConnectionConcurrency = diagnosis.code == "connection_concurrency_interaction"
    return DiagnosticsDiagnosisUiModel(
        code = diagnosis.code,
        summary =
            if (isConfirmGood) {
                context.getString(R.string.diagnostics_confirm_good_dpi_summary)
            } else if (isConnectionConcurrency) {
                context.getString(R.string.diagnostics_connection_concurrency_summary)
            } else {
                diagnosis.summary
            },
        severity = diagnosis.severity,
        target = diagnosis.target,
        tone =
            when (diagnosis.severity.lowercase(Locale.US)) {
                "negative", "error", "blocked" -> DiagnosticsTone.Negative
                "warning", "degraded" -> DiagnosticsTone.Warning
                "positive", "ok" -> DiagnosticsTone.Positive
                else -> DiagnosticsTone.Info
            },
        evidence =
            (
                if (isConfirmGood) {
                    listOf(
                        context.getString(R.string.diagnostics_confirm_good_dpi_evidence_reality),
                        context.getString(R.string.diagnostics_confirm_good_dpi_evidence_quic),
                    )
                } else if (isConnectionConcurrency) {
                    diagnosis.evidence
                } else {
                    diagnosis.evidence
                }
            ).toImmutableList(),
        recommendation =
            if (isConfirmGood) {
                context.getString(R.string.diagnostics_confirm_good_dpi_recommendation)
            } else if (isConnectionConcurrency && diagnosis.severity == "warning") {
                context.getString(R.string.diagnostics_connection_concurrency_recommendation)
            } else {
                diagnosis.recommendation
            },
    )
}

internal fun DiagnosticsUiFactorySupport.toRememberedNetworkUiModel(
    policy: DiagnosticsRememberedPolicy,
    activeConnectionPolicy: DiagnosticActiveConnectionPolicy?,
): DiagnosticsRememberedNetworkUiModel {
    val summary = policy.summary
    val signature = policy.strategySignature
    val ctx = context
    val isCurrentMatch = activeConnectionPolicy?.matchedPolicy?.id == policy.id
    return DiagnosticsRememberedNetworkUiModel(
        id = policy.id,
        title =
            summary?.displayNetworkLabel()
                ?: ctx.getString(
                    R.string.diagnostics_network_fallback_title,
                    policy.fingerprintHash.take(FingerprintHashShortLength),
                ),
        subtitle =
            listOf(
                policy.mode.uppercase(Locale.US),
                policy.source.displaySourceLabel(ctx),
                if (isCurrentMatch) ctx.getString(R.string.diagnostics_current_match) else null,
            ).filterNotNull().joinToString(" · "),
        status = policy.status.displayStatusLabel(ctx),
        statusTone = policy.status.statusTone(),
        source = policy.source.displaySourceLabel(ctx),
        strategyLabel =
            signature?.displayStrategyLabel()
                ?: ctx.getString(R.string.diagnostics_no_strategy_signature),
        lastValidatedLabel = policy.lastValidatedAt?.let(::formatTimestamp),
        lastAppliedLabel = policy.lastAppliedAt?.let(::formatTimestamp),
        successCount = policy.successCount,
        failureCount = policy.failureCount,
        isCurrentMatch = isCurrentMatch,
    )
}

internal fun DiagnosticsUiFactorySupport.toAutomaticProbeCalloutUiModel(
    session: com.poyka.ripdpi.diagnostics.DiagnosticScanSession,
): DiagnosticsAutomaticProbeCalloutUiModel {
    val detail =
        session.launchTrigger
            ?.displaySummaryLabel(context)
            ?.let { summary ->
                context.getString(
                    R.string.diagnostics_overview_automatic_probe_detail_handover_format,
                    formatTimestamp(session.finishedAt ?: session.startedAt),
                    summary,
                )
            }
            ?: context.getString(
                R.string.diagnostics_overview_automatic_probe_detail_format,
                formatTimestamp(session.finishedAt ?: session.startedAt),
            )
    return DiagnosticsAutomaticProbeCalloutUiModel(
        title = context.getString(R.string.diagnostics_overview_automatic_probe_title),
        summary = core.displaySessionSummary(context, session),
        detail = detail,
        actionLabel = context.getString(R.string.diagnostics_open_history_action),
    )
}

internal fun DiagnosticsUiFactorySupport.toEventUiModel(event: DiagnosticEvent): DiagnosticsEventUiModel =
    core.toEventUiModel(event)

private val connectivityPhaseOrder =
    listOf("dns", "reachability", "quic", "tcp", "service", "circumvention", "telegram", "throughput")
private val strategyProbePhaseOrder = listOf("tcp", "quic")

private fun String.toPhaseLabel(ctx: StringResolver): String =
    when (this) {
        "dns" -> ctx.getString(R.string.diagnostics_phase_dns)
        "reachability" -> ctx.getString(R.string.diagnostics_phase_reach)
        "quic" -> ctx.getString(R.string.diagnostics_phase_quic)
        "tcp" -> ctx.getString(R.string.diagnostics_phase_tcp)
        "service" -> ctx.getString(R.string.diagnostics_phase_svc)
        "circumvention" -> ctx.getString(R.string.diagnostics_phase_adaptation)
        "telegram" -> ctx.getString(R.string.diagnostics_phase_tg)
        "throughput" -> ctx.getString(R.string.diagnostics_phase_rate)
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
    completedProbes: List<CompletedProbeUiModel> = emptyList(),
    candidateTimeline: List<StrategyCandidateTimelineEntryUiModel> = emptyList(),
    dnsBaselineStatus: DnsBaselineStatus? = null,
    dpiFailureClass: DpiFailureClass? = null,
    networkContext: ScanNetworkContextUiModel? = null,
): DiagnosticsProgressUiModel {
    // Clamped here rather than only at the bar: the ETA formula below divides by `fraction` and
    // multiplies by `1 - fraction`, so an over-count from any earlier stage would yield a
    // negative ETA.
    val fraction =
        if (progress.totalSteps <= 0) {
            0f
        } else {
            (progress.completedSteps.toFloat() / progress.totalSteps.toFloat()).coerceIn(0f, 1f)
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
                label = phase.toPhaseLabel(context),
                state = state,
                tone = state.tone(),
            )
        }
    val strategyProbeProgress =
        progress.strategyProbeProgress?.let { liveProgress ->
            DiagnosticsStrategyProbeLiveProgressUiModel(
                lane =
                    when (liveProgress.lane) {
                        StrategyProbeProgressLane.TCP -> DiagnosticsStrategyProbeProgressLaneUiModel.TCP
                        StrategyProbeProgressLane.QUIC -> DiagnosticsStrategyProbeProgressLaneUiModel.QUIC
                    },
                candidateIndex = liveProgress.candidateIndex,
                candidateTotal = liveProgress.candidateTotal,
                candidateId = liveProgress.candidateId,
                candidateLabel = liveProgress.candidateLabel,
                succeededTargets = liveProgress.succeededTargets,
                totalTargets = liveProgress.totalTargets,
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
        scanStartedAtMs = scanStartedAt,
        phaseSteps = phaseSteps.toImmutableList(),
        currentProbeLabel = strategyProbeProgress?.candidateLabel ?: progress.message,
        strategyProbeProgress = strategyProbeProgress,
        dnsBaselineStatus = dnsBaselineStatus,
        dpiFailureClass = dpiFailureClass,
        networkContext = networkContext,
        candidateTimeline = candidateTimeline.toImmutableList(),
        completedProbes = completedProbes.toImmutableList(),
    )
}

internal fun scanCompletedTone(latestSession: DiagnosticsSessionRowUiModel?): DiagnosticsTone =
    latestSession?.tone ?: DiagnosticsTone.Neutral

internal fun String.displayStatusLabel(ctx: StringResolver): String =
    when (lowercase(Locale.US)) {
        "validated" -> ctx.getString(R.string.diagnostics_status_validated)
        "suppressed" -> ctx.getString(R.string.diagnostics_status_suppressed)
        else -> ctx.getString(R.string.diagnostics_status_observed)
    }

internal fun String.statusTone(): DiagnosticsTone =
    when (lowercase(Locale.US)) {
        "validated" -> DiagnosticsTone.Positive
        "suppressed" -> DiagnosticsTone.Warning
        else -> DiagnosticsTone.Info
    }

internal fun RememberedNetworkPolicySource.displaySourceLabel(ctx: StringResolver): String =
    when (this) {
        RememberedNetworkPolicySource.MANUAL_SESSION -> {
            ctx.getString(R.string.diagnostics_source_manual_session)
        }

        RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND -> {
            ctx.getString(R.string.diagnostics_source_automatic_probing_background)
        }

        RememberedNetworkPolicySource.AUTOMATIC_PROBING_MANUAL -> {
            ctx.getString(R.string.diagnostics_source_automatic_probing_manual)
        }

        RememberedNetworkPolicySource.AUTOMATIC_AUDIT_MANUAL -> {
            ctx.getString(R.string.diagnostics_source_automatic_audit_manual)
        }

        RememberedNetworkPolicySource.STRATEGY_PROBE_MANUAL -> {
            ctx.getString(R.string.diagnostics_source_strategy_probe_manual)
        }

        RememberedNetworkPolicySource.UNKNOWN -> {
            ctx.getString(R.string.diagnostics_source_unknown)
        }
    }

internal fun DiagnosticsScanLaunchOrigin.displayLabel(ctx: StringResolver): String =
    when (this) {
        DiagnosticsScanLaunchOrigin.USER_INITIATED -> {
            ctx.getString(R.string.diagnostics_scan_launch_user_initiated)
        }

        DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND -> {
            ctx.getString(R.string.diagnostics_scan_launch_automatic_background)
        }

        DiagnosticsScanLaunchOrigin.DNS_CORRECTED_REPROBE -> {
            ctx.getString(R.string.diagnostics_scan_launch_dns_corrected_reprobe)
        }

        DiagnosticsScanLaunchOrigin.UNKNOWN -> {
            ctx.getString(R.string.diagnostics_scan_launch_unknown)
        }
    }

internal fun DiagnosticsScanTriggerType.displayLabel(ctx: StringResolver): String =
    when (this) {
        DiagnosticsScanTriggerType.POLICY_HANDOVER -> ctx.getString(R.string.diagnostics_scan_trigger_policy_handover)
        DiagnosticsScanTriggerType.UNKNOWN -> ctx.getString(R.string.diagnostics_scan_trigger_unknown)
    }

internal fun DiagnosticsScanLaunchTrigger.displaySummaryLabel(ctx: StringResolver): String? {
    val classificationLabel = classification?.displayTriggerClassification()
    return when (type) {
        DiagnosticsScanTriggerType.POLICY_HANDOVER -> {
            classificationLabel?.let {
                ctx.getString(R.string.diagnostics_scan_trigger_handover_summary_format, it)
            } ?: ctx.getString(R.string.diagnostics_scan_trigger_policy_handover)
        }

        DiagnosticsScanTriggerType.UNKNOWN -> {
            classificationLabel ?: ctx.getString(R.string.diagnostics_scan_trigger_unknown)
        }
    }
}

internal fun String.displayTriggerClassification(): String =
    split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }

internal fun String?.shortFingerprintHash(): String? {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) {
        return null
    }
    return when {
        value.length <= FingerprintHashShortLength -> value
        else -> "${value.take(FingerprintHashPrefixLength)}...${value.takeLast(FingerprintHashSuffixLength)}"
    }
}

internal fun BypassApproachSummary.toDiagnosticsTone(): DiagnosticsTone =
    when {
        verificationState == BypassApproachVerificationState.NOT_EVALUATED -> DiagnosticsTone.Neutral

        verificationState == BypassApproachVerificationState.CONFIRMED_WORKING &&
            recentRuntimeHealth.totalErrors == 0L && recentRuntimeHealth.restartCount == 0 -> DiagnosticsTone.Positive

        verificationState == BypassApproachVerificationState.EVALUATED_PARTIAL_SUCCESS ||
            verificationState == BypassApproachVerificationState.CONFIRMED_WORKING -> DiagnosticsTone.Warning

        else -> DiagnosticsTone.Negative
    }

/**
 * Tone for the SUCCESS metric badge specifically.
 *
 * Unlike [toDiagnosticsTone] (used for the overall row), this leaves only a
 * genuinely not-evaluated approach neutral. A completed evaluation with no
 * successful reports is negative evidence.
 */
internal fun BypassApproachSummary.successMetricTone(): DiagnosticsTone {
    val rate = validatedSuccessRate
    return if (verificationState == BypassApproachVerificationState.NOT_EVALUATED) {
        DiagnosticsTone.Neutral
    } else {
        when {
            rate == null -> DiagnosticsTone.Negative
            rate >= SuccessRateHighThreshold -> DiagnosticsTone.Positive
            rate > 0f -> DiagnosticsTone.Warning
            else -> DiagnosticsTone.Negative
        }
    }
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
    return listOfNotNull(title, subtitle, summary, pathMode, serviceMode, status, triggerClassification).any {
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
