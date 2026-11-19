package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.SelectiveMatrixComparison
import com.poyka.ripdpi.diagnostics.SelectiveMatrixLegSummary
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairMismatch
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairRow
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.serialization.Serializable

/**
 * Redacted export of a paired comparison. It carries transport labels, timestamps, opaque target ids, per-leg
 * stages and repetition counts only: no session ids, hostnames, revisions, or network identifiers.
 */
@Serializable
data class SelectiveMatrixPairExport(
    val format: String = SelectiveMatrixPairExportFormat,
    val comparable: Boolean,
    val mismatches: List<SelectiveMatrixPairMismatch> = emptyList(),
    val legs: List<SelectiveMatrixLegSummary> = emptyList(),
    val rows: List<SelectiveMatrixPairRow> = emptyList(),
    val pathSpecificCount: Int = 0,
    val comparableCount: Int = 0,
)

const val SelectiveMatrixPairExportFormat = "ripdpi.selective_matrix_pair.v1"

fun SelectiveMatrixComparison.toPairExport(): SelectiveMatrixPairExport =
    when (this) {
        is SelectiveMatrixComparison.Unavailable -> {
            SelectiveMatrixPairExport(comparable = false, mismatches = mismatches)
        }

        is SelectiveMatrixComparison.Compared -> {
            SelectiveMatrixPairExport(
                comparable = true,
                legs = listOf(first, second),
                rows = rows,
                pathSpecificCount = pathSpecificCount,
                comparableCount = comparableCount,
            )
        }
    }

fun SelectiveMatrixComparison.encodePairExport(): String = RipDpiEncodeDefaultsJson.encodeToString(toPairExport())
