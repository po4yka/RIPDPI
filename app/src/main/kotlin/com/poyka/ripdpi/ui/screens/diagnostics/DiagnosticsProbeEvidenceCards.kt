package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel

/** Probes with a dedicated evidence card; generic probe lists skip them. */
internal fun DiagnosticsProbeResultUiModel.hasDedicatedEvidenceCard(): Boolean = isTlsHopProbe() || isQuicVersionProbe()

@Composable
internal fun DedicatedProbeEvidenceCards(probes: List<DiagnosticsProbeResultUiModel>) {
    probes.mapNotNull { it.tlsHop }.forEach { TlsHopResultsCard(it) }
    probes.mapNotNull { it.quicVersion }.takeIf { it.isNotEmpty() }?.let { QuicVersionResultsCard(it) }
}
