package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.normalizeSelectiveMatrixHost
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection

internal const val SelectiveMatrixProfileId = "selective-availability-matrix"
internal const val SelectiveMatrixInputLimit = 1_024
private const val MaximumUserHosts = 4

/** Syntax validation only. The scan admission layer also rejects non-public DNS answers. */
internal fun parseSelectiveMatrixHosts(input: String): List<String>? {
    val hosts = input.split(Regex("[\\s,]+")).filter(String::isNotBlank)
    if (input.length > SelectiveMatrixInputLimit || hosts.size > MaximumUserHosts) return null
    return try {
        hosts.map(::normalizeSelectiveMatrixHost).distinct()
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Persisted rows can predate network-scope finalization. Only the finalized report can authorize a conclusion. */
internal fun finalizedMatrixResults(
    storedResults: List<ProbeResult>,
    report: DiagnosticsSessionProjection?,
): List<ProbeResult> {
    val matrixReportResults = report?.results.orEmpty().filter { it.probeType.startsWith("selective_availability") }
    val results =
        if (matrixReportResults.isEmpty()) {
            storedResults
        } else {
            storedResults.filterNot { it.probeType.startsWith("selective_availability") } + matrixReportResults
        }
    val scopeUnverified = report?.diagnoses.orEmpty().any { it.code == "network_scope_unverified" }
    val authoritative = report?.completionKind == ScanCompletionKind.NORMAL && matrixReportResults.isNotEmpty()
    return results.map { result ->
        if (result.probeType == "selective_availability_summary" && (!authoritative || scopeUnverified)) {
            result.copy(
                outcome = "matrix_inconclusive",
                details =
                    result.details.filterNot { it.key == "reason" } +
                        ProbeDetail(
                            "reason",
                            if (scopeUnverified) "network_scope_unverified" else "incomplete_report",
                        ),
            )
        } else {
            result
        }
    }
}
