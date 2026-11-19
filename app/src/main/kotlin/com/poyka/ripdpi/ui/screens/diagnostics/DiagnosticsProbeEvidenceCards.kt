package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel

/** TLS hop and QUIC version probes have a dedicated evidence card; generic probe lists skip them. */
internal fun DiagnosticsProbeResultUiModel.hasDedicatedEvidenceCard(): Boolean = isTlsHopProbe() || isQuicVersionProbe()

@Composable
internal fun DedicatedProbeEvidenceCards(probes: List<DiagnosticsProbeResultUiModel>) {
    probes.filter { it.isSelectiveMatrixProbe() }.takeIf { it.isNotEmpty() }?.let { SelectiveMatrixResultsCard(it) }
    probes.mapNotNull { it.tlsHop }.forEach { TlsHopResultsCard(it) }
    probes.mapNotNull { it.quicVersion }.takeIf { it.isNotEmpty() }?.let { QuicVersionResultsCard(it) }
}
