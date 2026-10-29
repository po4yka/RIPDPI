package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.navigation.Route

private val HostnamePattern = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

/**
 * Returns the replay destination for a failed [probe] run under strategy [candidateId], or null
 * when the probe did not fail or either the target host or the strategy id is missing.
 */
internal fun replayRouteFor(
    candidateId: String,
    probe: DiagnosticsProbeResultUiModel,
): Route.ReplayFailure? {
    val domain = probe.target.trim()
    return if (
        probe.tone == DiagnosticsTone.Negative &&
        candidateId.isNotBlank() &&
        HostnamePattern.matches(domain)
    ) {
        Route.ReplayFailure(domain = domain, strategyId = candidateId)
    } else {
        null
    }
}
