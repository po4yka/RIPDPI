package com.poyka.ripdpi.diagnostics

const val BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary =
    "Background automatic probing canceled to start manual diagnostics"

sealed interface DiagnosticsManualScanStartResult {
    data class Started(
        val sessionId: String,
    ) : DiagnosticsManualScanStartResult

    data class RequiresHiddenProbeResolution(
        val requestId: String,
        val profileName: String,
        val pathMode: ScanPathMode,
        val scanKind: ScanKind,
        val isFullAudit: Boolean,
    ) : DiagnosticsManualScanStartResult
}

enum class HiddenProbeConflictAction {
    WAIT,
    CANCEL_AND_RUN,
}

enum class DiagnosticsManualScanResolutionFailureReason {
    REQUEST_NOT_FOUND,
    HIDDEN_PROBE_STILL_ACTIVE,
    CANCELLATION_FAILED,
    START_FAILED,
}

sealed interface DiagnosticsManualScanResolution {
    data class Started(
        val sessionId: String,
    ) : DiagnosticsManualScanResolution

    data class Failed(
        val reason: DiagnosticsManualScanResolutionFailureReason,
    ) : DiagnosticsManualScanResolution
}
