package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.diagnostics.BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticsOutcomeBucket
import com.poyka.ripdpi.diagnostics.DiagnosticsOutcomeTaxonomy
import com.poyka.ripdpi.diagnostics.DiagnosticsOutcomeTone
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanTerminationReason
import com.poyka.ripdpi.diagnostics.deriveProbeRetryCount
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.ownedStackBrowserLaunchUrl
import kotlinx.collections.immutable.toImmutableList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

internal open class DiagnosticsUiFormatter
    @Inject
    constructor() {
        open val locale: Locale = Locale.US
        open val zoneId: ZoneId = ZoneId.systemDefault()

        open fun formatTimestamp(timestamp: Long): String =
            DateTimeFormatter
                .ofPattern("MMM d, HH:mm", locale)
                .withZone(zoneId)
                .format(Instant.ofEpochMilli(timestamp))

        open fun formatBytes(bytes: Long): String =
            when {
                bytes >= BytesPerGigabyte -> String.format(locale, "%.1f GB", bytes / BytesPerGigabyte.toFloat())
                bytes >= BytesPerMegabyte -> String.format(locale, "%.1f MB", bytes / BytesPerMegabyte.toFloat())
                bytes >= BytesPerKilobyte -> String.format(locale, "%.1f KB", bytes / BytesPerKilobyte.toFloat())
                else -> "$bytes B"
            }

        open fun formatBps(bps: Long): String =
            when {
                bps >= BitsPerMegabit -> String.format(locale, "%.1f Mbps", bps / BitsPerMegabit.toDouble())
                bps >= BitsPerKilobit -> String.format(locale, "%.1f Kbps", bps / BitsPerKilobit.toDouble())
                else -> "$bps Bps"
            }

        open fun formatDurationMs(durationMs: Long): String = formatScanDurationMs(durationMs, locale)
    }

private const val BytesPerKilobyte = 1_000L
private const val BytesPerMegabyte = 1_000_000L
private const val BytesPerGigabyte = 1_000_000_000L
private const val BitsPerKilobit = 1_000L
private const val BitsPerMegabit = 1_000_000L

/**
 * Shared duration formatter for scan-facing UI.
 *
 * Lives at the top level because two callers need it: this package's formatter class, and
 * `ScanProgressCard`, which re-derives its elapsed label every second from its own clock rather
 * than from the last engine event.
 */
internal fun formatScanDurationMs(
    durationMs: Long,
    locale: Locale,
): String {
    val totalSeconds = (durationMs / MillisecondsPerSecond).coerceAtLeast(0L)
    val hours = totalSeconds / SecondsPerHour
    val minutes = (totalSeconds % SecondsPerHour) / SecondsPerMinute
    val seconds = totalSeconds % SecondsPerMinute
    return when {
        hours > 0L -> String.format(locale, "%dh %02dm", hours, minutes)
        minutes > 0L -> String.format(locale, "%dm %02ds", minutes, seconds)
        else -> "${seconds}s"
    }
}

private const val MillisecondsPerSecond = 1_000L
private const val SecondsPerMinute = 60L
private const val SecondsPerHour = 3_600L

internal class DiagnosticsUiCoreSupport
    @Inject
    constructor(
        internal val formatter: DiagnosticsUiFormatter,
        internal val strings: StringResolver,
    )

internal data class DiagnosticsCompletionUiPresentation(
    val completionLabel: String,
    val summaryLabel: String,
    val reasonLabel: String?,
    val tone: DiagnosticsTone?,
)

internal fun DiagnosticsUiCoreSupport.toSessionRowUiModel(
    session: DiagnosticScanSession,
): DiagnosticsSessionRowUiModel {
    val report = session.report
    val pathMode = parsePathMode(session.pathMode)
    val directModeVerdict = report?.directModeVerdict
    val completionPresentation = completionPresentation(report, session.status, session.summary)
    val displaySummary = displaySessionSummary(strings, session)
    return DiagnosticsSessionRowUiModel(
        id = session.id,
        profileId = session.profileId,
        title = displaySummary,
        subtitle =
            strings.getString(
                R.string.diagnostics_session_subtitle_format,
                session.pathMode,
                session.serviceMode ?: strings.getString(R.string.diagnostics_field_unknown),
                formatTimestamp(session.startedAt),
            ),
        pathMode = session.pathMode,
        serviceMode = session.serviceMode ?: strings.getString(R.string.diagnostics_field_unknown),
        status = session.status,
        completionLabel = completionPresentation.completionLabel,
        startedAtLabel = formatTimestamp(session.startedAt),
        summary = displaySummary,
        metrics =
            buildList {
                add(
                    DiagnosticsMetricUiModel(
                        label = strings.getString(R.string.diagnostics_metric_path),
                        value = session.pathMode,
                    ),
                )
                add(
                    DiagnosticsMetricUiModel(
                        label = strings.getString(R.string.diagnostics_metric_mode),
                        value = session.serviceMode ?: strings.getString(R.string.diagnostics_field_unknown),
                    ),
                )
                report?.results?.size?.let {
                    add(
                        DiagnosticsMetricUiModel(
                            label = strings.getString(R.string.diagnostics_metric_probes),
                            value = it.toString(),
                        ),
                    )
                }
            }.toImmutableList(),
        tone =
            completionPresentation.tone
                ?: report
                    ?.results
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { results -> toneForAggregatedProbeResults(pathMode, results) }
                ?: toneForSessionStatus(session.status),
        launchOrigin = session.launchOrigin,
        triggerClassification = session.launchTrigger?.classification,
        ownedStackLaunchUrl =
            if (directModeVerdict?.result == DirectModeVerdictResult.OWNED_STACK_ONLY) {
                ownedStackBrowserLaunchUrl(directModeVerdict.authority)
            } else {
                null
            },
        ownedStackOnly = directModeVerdict?.result == DirectModeVerdictResult.OWNED_STACK_ONLY,
        directModeResult = directModeVerdict?.result,
        directModeReasonCode = directModeVerdict?.reasonCode,
        directTransportClass = directModeVerdict?.transportClass,
    )
}

internal fun DiagnosticsUiCoreSupport.displaySessionSummary(
    context: StringResolver?,
    session: DiagnosticScanSession,
): String {
    val report = session.report
    return when {
        session.summary == BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary && context != null -> {
            context.getString(R.string.diagnostics_hidden_probe_canceled_summary)
        }

        context != null && report != null -> {
            DiagnosticsUiCoreSupport(formatter, context)
                .completionPresentation(report, session.status, session.summary)
                .summaryLabel
        }

        else -> {
            session.summary
        }
    }
}

internal fun DiagnosticsUiCoreSupport.completionLabel(
    report: com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection?,
    fallback: String,
): String = completionPresentation(report, fallback, fallback).completionLabel

internal fun DiagnosticsUiCoreSupport.completionPresentation(
    report: com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection?,
    fallbackStatus: String,
    fallbackSummary: String,
): DiagnosticsCompletionUiPresentation {
    if (report == null) {
        return DiagnosticsCompletionUiPresentation(
            completionLabel = fallbackStatus,
            summaryLabel = fallbackSummary,
            reasonLabel = null,
            tone = null,
        )
    }

    val reasonLabel = report.terminationReason?.let(::terminationReasonLabel)
    val reasonTone = report.terminationReason?.completionTone()
    return when (report.completionKind) {
        ScanCompletionKind.NORMAL -> {
            DiagnosticsCompletionUiPresentation(
                completionLabel = completionKindLabel(ScanCompletionKind.NORMAL),
                summaryLabel = fallbackSummary,
                reasonLabel = reasonLabel,
                tone = null,
            )
        }

        ScanCompletionKind.PARTIAL_RESULTS -> {
            val partialLabel = completionKindLabel(ScanCompletionKind.PARTIAL_RESULTS)
            DiagnosticsCompletionUiPresentation(
                completionLabel = partialLabel,
                summaryLabel =
                    reasonLabel?.let { reason ->
                        strings.getString(R.string.diagnostics_scan_completion_partial_with_reason_format, reason)
                    } ?: partialLabel,
                reasonLabel = reasonLabel,
                tone = reasonTone ?: DiagnosticsTone.Warning,
            )
        }

        ScanCompletionKind.TERMINATED -> {
            val terminatedLabel = completionKindLabel(ScanCompletionKind.TERMINATED)
            DiagnosticsCompletionUiPresentation(
                completionLabel = reasonLabel ?: terminatedLabel,
                summaryLabel = reasonLabel ?: terminatedLabel,
                reasonLabel = reasonLabel,
                tone = reasonTone ?: DiagnosticsTone.Warning,
            )
        }
    }
}

internal fun DiagnosticsUiCoreSupport.completionKindLabel(completionKind: ScanCompletionKind): String =
    strings.getString(completionKind.labelResource())

internal fun DiagnosticsUiCoreSupport.terminationReasonLabel(reason: ScanTerminationReason): String =
    strings.getString(reason.labelResource())

private fun ScanCompletionKind.labelResource(): Int =
    when (this) {
        ScanCompletionKind.NORMAL -> R.string.diagnostics_scan_completion_normal
        ScanCompletionKind.PARTIAL_RESULTS -> R.string.diagnostics_scan_completion_partial_results
        ScanCompletionKind.TERMINATED -> R.string.diagnostics_scan_completion_terminated
    }

private fun ScanTerminationReason.labelResource(): Int =
    when (this) {
        ScanTerminationReason.NETWORK_UNAVAILABLE -> R.string.diagnostics_scan_termination_network_unavailable
        ScanTerminationReason.USER_CANCELLED -> R.string.diagnostics_scan_termination_user_cancelled
        ScanTerminationReason.DEADLINE_EXCEEDED -> R.string.diagnostics_scan_termination_deadline_exceeded
        ScanTerminationReason.ENGINE_ERROR -> R.string.diagnostics_scan_termination_engine_error
        ScanTerminationReason.WORKER_PANICKED -> R.string.diagnostics_scan_termination_worker_panicked
    }

private fun ScanTerminationReason.completionTone(): DiagnosticsTone =
    when (this) {
        ScanTerminationReason.USER_CANCELLED -> DiagnosticsTone.Neutral

        ScanTerminationReason.DEADLINE_EXCEEDED -> DiagnosticsTone.Warning

        ScanTerminationReason.NETWORK_UNAVAILABLE,
        ScanTerminationReason.ENGINE_ERROR,
        ScanTerminationReason.WORKER_PANICKED,
        -> DiagnosticsTone.Negative
    }

internal fun DiagnosticsUiCoreSupport.toProbeResultUiModel(
    index: Int,
    pathMode: ScanPathMode,
    result: ProbeResult,
    reportResults: List<ProbeResult> = emptyList(),
): DiagnosticsProbeResultUiModel =
    DiagnosticsProbeResultUiModel(
        id = "report-$index-${result.probeType}-${result.target}",
        probeType = result.probeType,
        target = result.target,
        outcome = result.outcome,
        probeRetryCount = result.probeRetryCount ?: deriveProbeRetryCount(result.details),
        tone = toneForProbeResult(pathMode, result, reportResults),
        details = result.details.map { DiagnosticsFieldUiModel(it.key, it.value) }.toImmutableList(),
    )

internal fun DiagnosticsUiCoreSupport.toEventUiModel(event: DiagnosticEvent): DiagnosticsEventUiModel =
    DiagnosticsEventUiModel(
        id = event.id,
        source = event.source.replaceFirstChar { it.uppercase(formatter.locale) },
        severity = event.level.uppercase(formatter.locale),
        message = event.message,
        createdAtLabel = formatTimestamp(event.createdAt),
        tone = toneForEventLevel(event.level),
    )

internal fun DiagnosticsUiCoreSupport.toneForProbeOutcome(
    probeType: String,
    pathMode: ScanPathMode,
    outcome: String,
): DiagnosticsTone =
    DiagnosticsOutcomeTaxonomy
        .classifyProbeOutcome(probeType = probeType, pathMode = pathMode, outcome = outcome)
        .uiTone
        .toUiTone()

internal fun DiagnosticsUiCoreSupport.toneForProbeResult(
    pathMode: ScanPathMode,
    result: ProbeResult,
    reportResults: List<ProbeResult>,
): DiagnosticsTone =
    DiagnosticsOutcomeTaxonomy
        .classifyProbeResult(pathMode = pathMode, result = result, reportResults = reportResults)
        .uiTone
        .toUiTone()

internal fun DiagnosticsUiCoreSupport.toneForEventLevel(level: String): DiagnosticsTone =
    when (level.lowercase(formatter.locale)) {
        "info" -> DiagnosticsTone.Info
        "warn", "warning" -> DiagnosticsTone.Warning
        "error", "failed" -> DiagnosticsTone.Negative
        else -> DiagnosticsTone.Neutral
    }

internal fun DiagnosticsUiCoreSupport.toneForSessionStatus(status: String): DiagnosticsTone =
    when (status.lowercase(formatter.locale)) {
        "completed", "finished" -> DiagnosticsTone.Positive
        "running", "started" -> DiagnosticsTone.Warning
        "failed" -> DiagnosticsTone.Negative
        "cancelled" -> DiagnosticsTone.Neutral
        else -> DiagnosticsTone.Neutral
    }

internal fun DiagnosticsUiCoreSupport.parsePathMode(value: String): ScanPathMode =
    runCatching { ScanPathMode.valueOf(value) }.getOrDefault(ScanPathMode.RAW_PATH)

internal fun DiagnosticsUiCoreSupport.formatTimestamp(timestamp: Long): String = formatter.formatTimestamp(timestamp)

internal fun DiagnosticsUiCoreSupport.formatBytes(bytes: Long): String = formatter.formatBytes(bytes)

internal fun DiagnosticsUiCoreSupport.formatBps(bps: Long): String = formatter.formatBps(bps)

internal fun DiagnosticsUiCoreSupport.formatDurationMs(durationMs: Long): String =
    formatter.formatDurationMs(durationMs)

internal fun DiagnosticsUiCoreSupport.redactValue(value: String?): String =
    value?.let { strings.getString(R.string.diagnostics_field_hidden) }
        ?: strings.getString(R.string.diagnostics_field_unknown)

internal fun DiagnosticsUiCoreSupport.redactCollection(values: List<String>): String =
    if (values.isEmpty()) {
        strings.getString(R.string.diagnostics_field_unknown)
    } else {
        strings.getString(R.string.diagnostics_field_hidden_count_format, values.size)
    }

internal fun DiagnosticsUiCoreSupport.aggregateBucketForProbeResults(
    pathMode: ScanPathMode,
    results: List<ProbeResult>,
): DiagnosticsOutcomeBucket? = DiagnosticsOutcomeTaxonomy.aggregateBucket(pathMode = pathMode, results = results)

private fun DiagnosticsUiCoreSupport.toneForAggregatedProbeResults(
    pathMode: ScanPathMode,
    results: List<ProbeResult>,
): DiagnosticsTone =
    when (aggregateBucketForProbeResults(pathMode, results)) {
        DiagnosticsOutcomeBucket.Healthy -> DiagnosticsTone.Positive
        DiagnosticsOutcomeBucket.Attention -> DiagnosticsTone.Warning
        DiagnosticsOutcomeBucket.Failed -> DiagnosticsTone.Negative
        DiagnosticsOutcomeBucket.Inconclusive -> DiagnosticsTone.Neutral
        null -> DiagnosticsTone.Neutral
    }

private fun DiagnosticsOutcomeTone.toUiTone(): DiagnosticsTone =
    when (this) {
        DiagnosticsOutcomeTone.Positive -> DiagnosticsTone.Positive
        DiagnosticsOutcomeTone.Warning -> DiagnosticsTone.Warning
        DiagnosticsOutcomeTone.Negative -> DiagnosticsTone.Negative
        DiagnosticsOutcomeTone.Neutral -> DiagnosticsTone.Neutral
    }
