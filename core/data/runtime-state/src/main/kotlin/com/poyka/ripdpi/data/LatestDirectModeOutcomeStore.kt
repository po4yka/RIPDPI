package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.StateFlow

// Snapshot of the most recent direct-mode verdict observed by the
// diagnostics workflow. Held in memory only; survives configuration changes
// because the store is @Singleton, but is reset on process death — that
// matches the existing per-session UX (run a scan to populate it).
//
// Carries the fields recommendTransportRemediation consumes; the richer
// DiagnosticsCapabilityEvidence list is intentionally not stored here so
// the store can live in core/data/runtime-state without adding a
// core/diagnostics dependency. Surfaces that need the QUIC-vs-browser
// nuance (Home) read the evidence directly from the run outcome they
// already hold.
data class LatestDirectModeOutcomeSnapshot(
    val result: DirectModeVerdictResult?,
    val reasonCode: DirectModeReasonCode?,
    val transportClass: DirectTransportClass?,
    val recordedAt: Long,
)

// Singleton hand-off channel between the diagnostics workflow (producer)
// and any UI surface that wants the latest direct-mode verdict (consumer).
// Today the consumers are MainViewModel (via MainHomeDiagnosticsActions)
// and ConfigViewModel; the latter feeds the verdict into
// resolveRelayPresetSuggestion so the Config relay-preset hint stays in
// sync with the Diagnostics / Home remediation ladder (direct-mode architecture note P4.3.4).
interface LatestDirectModeOutcomeStore {
    val outcome: StateFlow<LatestDirectModeOutcomeSnapshot?>

    fun publish(snapshot: LatestDirectModeOutcomeSnapshot?)
}
