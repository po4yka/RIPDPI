package com.poyka.ripdpi.diagnostics

data class DiagnosticSessionDetail(
    val session: DiagnosticScanSession,
    val results: List<ProbeResult>,
    val snapshots: List<DiagnosticNetworkSnapshot>,
    val events: List<DiagnosticEvent>,
    val context: DiagnosticContextSnapshot?,
    val capabilityEvidence: List<DiagnosticsCapabilityEvidence> = emptyList(),
)
