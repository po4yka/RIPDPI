@file:Suppress("MagicNumber")

package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.deriveProbeRetryCount
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
                bytes >= 1_000_000_000L -> String.format(locale, "%.1f GB", bytes / 1_000_000_000f)
                bytes >= 1_000_000L -> String.format(locale, "%.1f MB", bytes / 1_000_000f)
                bytes >= 1_000L -> String.format(locale, "%.1f KB", bytes / 1_000f)
                else -> "$bytes B"
            }

        open fun formatBps(bps: Long): String =
            when {
                bps >= 1_000_000L -> String.format(locale, "%.1f Mbps", bps / 1_000_000.0)
                bps >= 1_000L -> String.format(locale, "%.1f Kbps", bps / 1_000.0)
                else -> "$bps Bps"
            }

        open fun formatDurationMs(durationMs: Long): String {
            val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0L -> String.format(locale, "%dh %02dm", hours, minutes)
                minutes > 0L -> String.format(locale, "%dm %02ds", minutes, seconds)
                else -> "${seconds}s"
            }
        }
    }

internal class DiagnosticsUiCoreSupport
    @Inject
    constructor(
        internal val formatter: DiagnosticsUiFormatter,
    ) {
        constructor() : this(DiagnosticsUiFormatter())
    }

internal fun DiagnosticsUiCoreSupport.toSessionRowUiModel(
    session: DiagnosticScanSession,
): DiagnosticsSessionRowUiModel {
    val report = session.report
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
        metrics =
            buildList {
                add(DiagnosticsMetricUiModel(label = "Path", value = session.pathMode))
                add(DiagnosticsMetricUiModel(label = "Mode", value = session.serviceMode ?: "Unknown"))
                report?.results?.size?.let {
                    add(DiagnosticsMetricUiModel(label = "Probes", value = it.toString()))
                }
            },
        tone = toneForOutcome(session.status),
    )
}

internal fun DiagnosticsUiCoreSupport.toProbeResultUiModel(
    index: Int,
    result: ProbeResult,
): DiagnosticsProbeResultUiModel =
    DiagnosticsProbeResultUiModel(
        id = "report-$index-${result.probeType}-${result.target}",
        probeType = result.probeType,
        target = result.target,
        outcome = result.outcome,
        probeRetryCount = result.probeRetryCount ?: deriveProbeRetryCount(result.details),
        tone = toneForOutcome(result.outcome),
        details = result.details.map { DiagnosticsFieldUiModel(it.key, it.value) },
    )

internal fun DiagnosticsUiCoreSupport.toEventUiModel(event: DiagnosticEvent): DiagnosticsEventUiModel =
    DiagnosticsEventUiModel(
        id = event.id,
        source = event.source.replaceFirstChar { it.uppercase(formatter.locale) },
        severity = event.level.uppercase(formatter.locale),
        message = event.message,
        createdAtLabel = formatTimestamp(event.createdAt),
        tone = toneForOutcome(event.level),
    )

internal fun DiagnosticsUiCoreSupport.toneForOutcome(value: String): DiagnosticsTone {
    val normalized = value.lowercase(formatter.locale)
    return when {
        normalized.contains("ok") || normalized.contains("success") || normalized.contains("completed") -> {
            DiagnosticsTone.Positive
        }

        normalized.contains("warn") ||
            normalized.contains("timeout") ||
            normalized.contains("partial") ||
            normalized.contains("running") ||
            normalized.contains("slow") ||
            normalized.contains("stalled") -> {
            DiagnosticsTone.Warning
        }

        normalized.contains("error") ||
            normalized.contains("failed") ||
            normalized.contains("blocked") ||
            normalized.contains("reset") -> {
            DiagnosticsTone.Negative
        }

        normalized.contains("info") -> {
            DiagnosticsTone.Info
        }

        else -> {
            DiagnosticsTone.Neutral
        }
    }
}

internal fun DiagnosticsUiCoreSupport.parsePathMode(value: String): ScanPathMode =
    runCatching { ScanPathMode.valueOf(value) }.getOrDefault(ScanPathMode.RAW_PATH)

internal fun DiagnosticsUiCoreSupport.formatTimestamp(timestamp: Long): String = formatter.formatTimestamp(timestamp)

internal fun DiagnosticsUiCoreSupport.formatBytes(bytes: Long): String = formatter.formatBytes(bytes)

internal fun DiagnosticsUiCoreSupport.formatBps(bps: Long): String = formatter.formatBps(bps)

internal fun DiagnosticsUiCoreSupport.formatDurationMs(durationMs: Long): String =
    formatter.formatDurationMs(durationMs)

internal fun DiagnosticsUiCoreSupport.redactValue(value: String?): String = value?.let { "redacted" } ?: "Unknown"

internal fun DiagnosticsUiCoreSupport.redactCollection(values: List<String>): String =
    if (values.isEmpty()) {
        "Unknown"
    } else {
        "redacted(${values.size})"
    }
