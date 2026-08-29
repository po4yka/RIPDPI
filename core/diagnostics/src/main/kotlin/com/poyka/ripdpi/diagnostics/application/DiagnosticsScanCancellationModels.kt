@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

internal data class ActiveScanCancellation(
    val sessionId: String,
    val partialReportJson: String?,
    val failure: Throwable? = null,
    val prepared: PreparedDiagnosticsScan? = null,
)

internal enum class ScanTerminalClaim {
    CANCELLATION,
    COMPLETION,
}

internal class DiagnosticsBridgeStartException(
    cause: Throwable,
) : IllegalStateException("Diagnostics scan failed to start", cause)

internal sealed interface HiddenProbeCancellationResult {
    data object NoActiveProbe : HiddenProbeCancellationResult

    data class Cancelled(
        val sessionId: String,
    ) : HiddenProbeCancellationResult

    data class Failed(
        val sessionId: String,
    ) : HiddenProbeCancellationResult
}
