package com.poyka.ripdpi.ui.screens.history

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.SelectiveMatrixComparison
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsSessionRowUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlin.math.abs

/** Pair selection for an open selective matrix session and, once a second session is chosen, the comparison. */
@Immutable
data class SelectiveMatrixPairingUiModel(
    val candidates: ImmutableList<SelectiveMatrixPairCandidateUiModel>,
    /** Target id to the label the open session measured, for display only. */
    val targetLabels: ImmutableMap<String, String>,
    val pairedSessionId: String? = null,
    val comparison: SelectiveMatrixComparison? = null,
)

@Immutable
data class SelectiveMatrixPairCandidateUiModel(
    val sessionId: String,
    val transport: String?,
    val startedAtLabel: String,
)

internal fun selectiveMatrixPairing(
    detail: DiagnosticSessionDetail,
    sessions: List<DiagnosticScanSession>,
    rows: List<DiagnosticsSessionRowUiModel>,
    pair: HistoryMatrixPairState?,
): SelectiveMatrixPairingUiModel? {
    val attempts = detail.results.filter { it.probeType == MatrixAttemptProbeType }
    if (attempts.isEmpty()) return null
    val startedAtLabels = rows.associate { it.id to it.startedAtLabel }
    return SelectiveMatrixPairingUiModel(
        // Sessions saved before pairing revisions existed cannot be compared, so they are not offered.
        candidates =
            sessions
                .filter { it.id != detail.session.id && it.matrixRevisions != null }
                // Nearest runs first: only a run within the pairing window can be compared.
                .sortedBy { abs(it.startedAt - detail.session.startedAt) }
                .take(MaxPairCandidates)
                .map { session ->
                    SelectiveMatrixPairCandidateUiModel(
                        session.id,
                        session.networkTransport,
                        startedAtLabels[session.id].orEmpty(),
                    )
                }.toImmutableList(),
        targetLabels =
            attempts
                .mapNotNull { result ->
                    result.details.firstOrNull { it.key == "targetId" }?.let { it.value to result.target }
                }.toMap()
                .toImmutableMap(),
        pairedSessionId = pair?.pairedSessionId,
        comparison = pair?.comparison,
    )
}

private const val MatrixAttemptProbeType = "selective_availability"

// shortcut: a fixed cap keeps the sheet short, add paging if users need older runs.
private const val MaxPairCandidates = 6
