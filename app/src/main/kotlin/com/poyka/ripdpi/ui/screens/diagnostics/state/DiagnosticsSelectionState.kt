package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixConfig
import com.poyka.ripdpi.diagnostics.TlsHopComparisonConfig
import com.poyka.ripdpi.diagnostics.VerifiedDownloadInput

internal data class SelectionState(
    val selectedSectionRequest: DiagnosticsSection = DiagnosticsSection.Dashboard,
    val selectedProfileId: String? = null,
    val selectiveMatrixHostsInput: String = "",
    val tlsHopComparisonInput: TlsHopComparisonConfig = TlsHopComparisonConfig(),
    val quicVersionMatrixInput: QuicVersionMatrixConfig = QuicVersionMatrixConfig(),
    val verifiedDownloadInput: VerifiedDownloadInput = VerifiedDownloadInput(),
    val selectedApproachMode: DiagnosticsApproachMode = DiagnosticsApproachMode.Profiles,
    val selectedApproachDetail: BypassApproachDetail? = null,
    val selectedProbe: DiagnosticsProbeResultUiModel? = null,
    val selectedEventId: String? = null,
    val selectedStrategyProbeCandidate: DiagnosticsStrategyProbeCandidateDetailUiModel? = null,
)

internal data class FilterState(
    val sessionSearch: String = "",
    val eventSourceFilter: String? = null,
    val eventSeverityFilter: String? = null,
    val eventSearch: String = "",
    val eventAutoScroll: Boolean = true,
)

internal data class SessionDetailState(
    val selectedSessionDetail: DiagnosticSessionDetail? = null,
    val sensitiveSessionDetailsVisible: Boolean = false,
)
