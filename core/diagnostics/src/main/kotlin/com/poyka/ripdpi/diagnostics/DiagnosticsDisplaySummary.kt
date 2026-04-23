package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection

internal const val ScanCancelledSummary = "Scan cancelled"
internal const val ScanCompletedWithPartialResultsSummary = "Scan completed with partial results"

private const val ScanCompletedWithDnsFallbackSummary = "Scan completed with DNS fallback"

internal fun ScanReport.displaySummary(defaultSummary: String = summary): String =
    deriveDisplaySummary(
        rawSummary = summary.ifBlank { defaultSummary },
        directModeVerdict = directModeVerdict,
        strategyCompletionKind = strategyProbeReport?.completionKind,
        hasPartialResults = results.isNotEmpty() || observations.isNotEmpty(),
    )

internal fun ScanSessionEntity.displaySummary(report: ScanReport?): String = report?.displaySummary(summary) ?: summary

internal fun ScanSessionEntity.displaySummary(report: DiagnosticsSessionProjection?): String =
    report?.displaySummary(summary) ?: summary

internal fun DiagnosticsSessionProjection.displaySummary(rawSummary: String): String =
    deriveDisplaySummary(
        rawSummary = rawSummary,
        directModeVerdict = directModeVerdict,
        strategyCompletionKind = strategyProbeReport?.completionKind,
        hasPartialResults = results.isNotEmpty() || observations.isNotEmpty(),
    )

private fun deriveDisplaySummary(
    rawSummary: String,
    directModeVerdict: DirectModeVerdict?,
    strategyCompletionKind: StrategyProbeCompletionKind?,
    hasPartialResults: Boolean,
): String =
    when {
        directModeVerdict?.result == com.poyka.ripdpi.data.DirectModeVerdictResult.OWNED_STACK_ONLY -> {
            "Direct mode works only in RIPDPI owned stack"
        }

        directModeVerdict?.result == com.poyka.ripdpi.data.DirectModeVerdictResult.NO_DIRECT_SOLUTION -> {
            "No direct solution for this authority"
        }

        strategyCompletionKind == StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK -> {
            ScanCompletedWithDnsFallbackSummary
        }

        hasPartialResults && (rawSummary.isBlank() || rawSummary == ScanCancelledSummary) -> {
            ScanCompletedWithPartialResultsSummary
        }

        else -> {
            rawSummary
        }
    }
