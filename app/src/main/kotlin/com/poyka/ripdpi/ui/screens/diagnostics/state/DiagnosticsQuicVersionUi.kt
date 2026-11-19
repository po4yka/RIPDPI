package com.poyka.ripdpi.ui.screens.diagnostics.state

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixEvidence
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixProbeTypes
import com.poyka.ripdpi.diagnostics.parseQuicVersionMatrixEvidence

/** One row of a QUIC version matrix run: a version, the comparison, the residual cell or the matrix itself. */
@Immutable
data class QuicVersionRowUiModel(
    val probeType: String,
    val evidence: QuicVersionMatrixEvidence,
)

internal fun ProbeResult.toQuicVersionRow(): QuicVersionRowUiModel? {
    if (probeType !in QuicVersionMatrixProbeTypes) return null
    return parseQuicVersionMatrixEvidence(details)?.let { QuicVersionRowUiModel(probeType, it) }
}
