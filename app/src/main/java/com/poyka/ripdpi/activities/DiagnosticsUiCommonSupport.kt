package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.decodeSummary
import com.poyka.ripdpi.data.displayLabel as displayNetworkLabel
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ScanRequest
import com.poyka.ripdpi.diagnostics.displayLabel as displayStrategyLabel
import com.poyka.ripdpi.services.ActiveConnectionPolicy
import java.util.Date
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer

internal fun DiagnosticsUiFactorySupport.decodeReport(reportJson: String): ScanReport? =
    runCatching { json.decodeFromString(ScanReport.serializer(), reportJson) }.getOrNull()

internal fun DiagnosticsUiFactorySupport.decodeRequest(profile: DiagnosticProfileEntity): ScanRequest? =
    runCatching { json.decodeFromString(ScanRequest.serializer(), profile.requestJson) }.getOrNull()

internal fun DiagnosticsUiFactorySupport.decodeProbeDetails(detailJson: String): List<ProbeDetail> =
    runCatching { json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson) }.getOrElse { emptyList() }

internal fun DiagnosticsUiFactorySupport.decodeStrategySignature(payload: String?): BypassStrategySignature? =
    payload?.takeIf { it.isNotBlank() }?.let {
        runCatching { json.decodeFromString(BypassStrategySignature.serializer(), it) }.getOrNull()
    }

internal fun DiagnosticsUiFactorySupport.decodeContext(entity: DiagnosticContextEntity): DiagnosticContextModel? =
    runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), entity.payloadJson) }.getOrNull()

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
    session: ScanSessionEntity,
): DiagnosticsSessionRowUiModel {
    val report = session.reportJson?.let(::decodeReport)
    val metrics =
        buildList {
            add(DiagnosticsMetricUiModel(label = "Path", value = session.pathMode))
            add(DiagnosticsMetricUiModel(label = "Mode", value = session.serviceMode ?: "Unknown"))
            report?.results?.size?.let {
                add(DiagnosticsMetricUiModel(label = "Probes", value = it.toString()))
            }
        }
    return DiagnosticsSessionRowUiModel(
        id = session.id,
        profileId = session.profileId,
        title = session.summary,
        subtitle = "${session.pathMode} · ${session.serviceMode ?: "Unknown"} · ${formatTimestamp(session.startedAt)}",
        pathMode = session.pathMode,
        serviceMode = session.serviceMode ?: "Unknown",
        status = session.status,
        startedAtLabel = formatTimestamp(session.startedAt),
        summary = session.summary,
        metrics = metrics,
        tone = toneForOutcome(session.status),
    )
}

internal fun DiagnosticsUiFactorySupport.toProbeResultUiModel(
    index: Int,
    result: ProbeResultEntity,
): DiagnosticsProbeResultUiModel =
    decodeProbeDetails(result.detailJson).let { details ->
        DiagnosticsProbeResultUiModel(
            id = "${result.sessionId}-$index-${result.probeType}-${result.target}",
            probeType = result.probeType,
            target = result.target,
            outcome = result.outcome,
            probeRetryCount = com.poyka.ripdpi.diagnostics.deriveProbeRetryCount(details),
            tone = toneForOutcome(result.outcome),
            details = details.map { DiagnosticsFieldUiModel(it.key, it.value) },
        )
    }

internal fun DiagnosticsUiFactorySupport.toProbeResultUiModel(
    index: Int,
    result: ProbeResult,
): DiagnosticsProbeResultUiModel =
    DiagnosticsProbeResultUiModel(
        id = "report-$index-${result.probeType}-${result.target}",
        probeType = result.probeType,
        target = result.target,
        outcome = result.outcome,
        probeRetryCount = result.probeRetryCount ?: com.poyka.ripdpi.diagnostics.deriveProbeRetryCount(result.details),
        tone = toneForOutcome(result.outcome),
        details = result.details.map { DiagnosticsFieldUiModel(it.key, it.value) },
    )

internal fun DiagnosticsUiFactorySupport.toRememberedNetworkUiModel(
    policy: RememberedNetworkPolicyEntity,
    activeConnectionPolicy: ActiveConnectionPolicy?,
): DiagnosticsRememberedNetworkUiModel {
    val summary = policy.decodeSummary(json)
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
): DiagnosticsEventUiModel =
    DiagnosticsEventUiModel(
        id = event.id,
        source = event.source.replaceFirstChar { it.uppercase() },
        severity = event.level.uppercase(Locale.US),
        message = event.message,
        createdAtLabel = formatTimestamp(event.createdAt),
        tone = toneForOutcome(event.level),
    )

internal fun DiagnosticsUiFactorySupport.toProgressUiModel(progress: ScanProgress): DiagnosticsProgressUiModel {
    val fraction =
        if (progress.totalSteps <= 0) {
            0f
        } else {
            progress.completedSteps.toFloat() / progress.totalSteps.toFloat()
        }
    return DiagnosticsProgressUiModel(
        phase = progress.phase,
        summary = progress.message,
        completedSteps = progress.completedSteps,
        totalSteps = progress.totalSteps,
        fraction = fraction,
    )
}

internal fun DiagnosticsUiFactorySupport.toneForOutcome(value: String): DiagnosticsTone {
    val normalized = value.lowercase(Locale.US)
    return when {
        normalized.contains("ok") || normalized.contains("success") || normalized.contains("completed") ->
            DiagnosticsTone.Positive

        normalized.contains("warn") ||
            normalized.contains("timeout") ||
            normalized.contains("partial") ||
            normalized.contains("running") ||
            normalized.contains("slow") ||
            normalized.contains("stalled") ->
            DiagnosticsTone.Warning

        normalized.contains("error") ||
            normalized.contains("failed") ||
            normalized.contains("blocked") ||
            normalized.contains("reset") ->
            DiagnosticsTone.Negative

        normalized.contains("info") -> DiagnosticsTone.Info
        else -> DiagnosticsTone.Neutral
    }
}

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

internal fun DiagnosticsUiFactorySupport.parsePathMode(value: String): ScanPathMode =
    runCatching { ScanPathMode.valueOf(value) }.getOrDefault(ScanPathMode.RAW_PATH)

internal fun DiagnosticsUiFactorySupport.formatTimestamp(timestamp: Long): String =
    timestampFormatter.format(Date(timestamp))

internal fun DiagnosticsUiFactorySupport.formatBytes(bytes: Long): String =
    when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000f)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000f)
        bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000f)
        else -> "$bytes B"
    }

internal fun DiagnosticsUiFactorySupport.formatBps(bps: Long): String =
    when {
        bps >= 1_000_000L -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000L -> String.format(Locale.US, "%.1f Kbps", bps / 1_000.0)
        else -> "$bps Bps"
    }

internal fun DiagnosticsUiFactorySupport.formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0L -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else -> "${seconds}s"
    }
}

internal fun DiagnosticsUiFactorySupport.redactValue(value: String?): String =
    value?.let { "redacted" } ?: "Unknown"

internal fun DiagnosticsUiFactorySupport.redactCollection(values: List<String>): String =
    if (values.isEmpty()) {
        "Unknown"
    } else {
        "redacted(${values.size})"
    }

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
