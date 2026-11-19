package com.poyka.ripdpi.ui.screens.diagnostics.state

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.TlsHopAttempt
import com.poyka.ripdpi.diagnostics.TlsHopComparisonEvidence
import com.poyka.ripdpi.diagnostics.TlsHopComparisonProbeType
import com.poyka.ripdpi.diagnostics.parseTlsHopComparisonEvidence
import com.poyka.ripdpi.diagnostics.parseTlsHopComparisonLocal
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class TlsHopResultUiModel(
    val evidence: TlsHopComparisonEvidence,
    val controlSni: String?,
    val testSni: String?,
    val attempts: ImmutableList<TlsHopAttempt>,
)

internal fun ProbeResult.toTlsHopResult(): TlsHopResultUiModel? {
    if (probeType != TlsHopComparisonProbeType) return null
    val local = parseTlsHopComparisonLocal(details)
    return parseTlsHopComparisonEvidence(details)?.let { evidence ->
        TlsHopResultUiModel(
            evidence = evidence,
            controlSni = local?.controlSni,
            testSni = local?.testSni,
            attempts = local?.attempts.orEmpty().toImmutableList(),
        )
    }
}
