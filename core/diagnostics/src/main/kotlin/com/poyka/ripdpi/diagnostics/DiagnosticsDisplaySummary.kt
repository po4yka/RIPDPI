package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection

internal const val ScanCancelledSummary = "Scan cancelled"
internal const val ScanCompletedWithPartialResultsSummary = "Scan completed with partial results"
internal const val ScanPartialResultsReasonSeparator = " · "
internal const val ScanUnavailableOfflineSummary = "Scan unavailable while offline"
internal const val ScanCancelledByUserSummary = "Scan cancelled by user"
internal const val ScanDeadlineExceededSummary = "Scan time limit reached"
internal const val ScanEngineErrorSummary = "Scan stopped by an engine error"
internal const val ScanWorkerPanickedSummary = "Scan stopped after a worker failure"

private const val ScanCompletedWithDnsFallbackSummary = "Scan completed with DNS fallback"
private const val TransparentDirectModeSummary = "Direct mode works transparently"
private const val DirectPathHealthyWithSyntheticAttentionSummary =
    "Direct connectivity is healthy; only synthetic probe artifacts need attention"

internal fun ScanReport.displaySummary(defaultSummary: String = summary): String =
    deriveDisplaySummary(
        rawSummary = summary.ifBlank { defaultSummary },
        directModeVerdict = directModeVerdict,
        directPathHealthState = directPathHealthState(),
        strategyCompletionKind = strategyProbeReport?.completionKind,
        completionKind = completionKind,
        terminationReason = terminationReason,
        hasPartialResults = results.isNotEmpty() || observations.isNotEmpty(),
    )

internal fun ScanSessionEntity.displaySummary(report: ScanReport?): String =
    if (hasAuthoritativeManualConflictCancellation()) summary else report?.displaySummary(summary) ?: summary

internal fun ScanSessionEntity.displaySummary(report: DiagnosticsSessionProjection?): String =
    if (hasAuthoritativeManualConflictCancellation()) summary else report?.displaySummary(summary) ?: summary

internal fun DiagnosticsSessionProjection.displaySummary(rawSummary: String): String =
    deriveDisplaySummary(
        rawSummary = rawSummary,
        directModeVerdict = directModeVerdict,
        directPathHealthState = null,
        strategyCompletionKind = strategyProbeReport?.completionKind,
        completionKind = completionKind,
        terminationReason = terminationReason,
        hasPartialResults = results.isNotEmpty() || observations.isNotEmpty(),
    )

private fun deriveDisplaySummary(
    rawSummary: String,
    directModeVerdict: DirectModeVerdict?,
    directPathHealthState: DirectPathHealthState?,
    strategyCompletionKind: StrategyProbeCompletionKind?,
    completionKind: ScanCompletionKind,
    terminationReason: ScanTerminationReason?,
    hasPartialResults: Boolean,
): String =
    when {
        completionKind == ScanCompletionKind.PARTIAL_RESULTS -> {
            terminationReason.displaySummary()?.let { reasonSummary ->
                ScanCompletedWithPartialResultsSummary + ScanPartialResultsReasonSeparator + reasonSummary
            } ?: ScanCompletedWithPartialResultsSummary
        }

        completionKind == ScanCompletionKind.TERMINATED -> {
            terminationReason.displaySummary() ?: rawSummary
        }

        directModeVerdictSummary(directModeVerdict) != null -> {
            directModeVerdictSummary(directModeVerdict).orEmpty()
        }

        directPathHealthState == DirectPathHealthState.DIRECT_PATH_HEALTHY_WITH_SYNTHETIC_ATTENTION -> {
            DirectPathHealthyWithSyntheticAttentionSummary
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

private fun ScanTerminationReason?.displaySummary(): String? =
    when (this) {
        ScanTerminationReason.NETWORK_UNAVAILABLE -> ScanUnavailableOfflineSummary
        ScanTerminationReason.USER_CANCELLED -> ScanCancelledByUserSummary
        ScanTerminationReason.DEADLINE_EXCEEDED -> ScanDeadlineExceededSummary
        ScanTerminationReason.ENGINE_ERROR -> ScanEngineErrorSummary
        ScanTerminationReason.WORKER_PANICKED -> ScanWorkerPanickedSummary
        null -> null
    }

private fun directModeVerdictSummary(verdict: DirectModeVerdict?): String? =
    when (verdict?.result) {
        DirectModeVerdictResult.OWNED_STACK_ONLY -> {
            "Direct mode works only in RIPDPI owned stack"
        }

        DirectModeVerdictResult.TRANSPARENT_WORKS -> {
            TransparentDirectModeSummary
        }

        DirectModeVerdictResult.NO_DIRECT_SOLUTION -> {
            when (verdict.reasonCode) {
                DirectModeReasonCode.IP_BLOCKED -> {
                    "No direct solution: observed authority reachability failures; IP-level filtering is a " +
                        "candidate explanation, but it is not established"
                }

                DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE -> {
                    "No direct solution: TLS handshake failed after ClientHello"
                }

                DirectModeReasonCode.QUIC_BLOCKED -> {
                    "No direct solution: observed QUIC failure without TCP recovery; " +
                        "a blocking cause is not established"
                }

                DirectModeReasonCode.NO_TCP_FALLBACK -> {
                    "No direct solution: app did not fall back from QUIC"
                }

                else -> {
                    "No direct solution for this authority"
                }
            }
        }

        null,
        -> {
            null
        }
    }
