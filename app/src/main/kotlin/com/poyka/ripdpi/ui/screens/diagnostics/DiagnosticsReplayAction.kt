package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.diagnostics.replayableStrategyCandidateIds
import com.poyka.ripdpi.ui.navigation.Route

private val HostnamePattern = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

/**
 * Returns the replay destination for a failed [probe] run under strategy [candidateId], or null
 * when the probe did not fail, the target is not a hostname, or the candidate has neither a proxy
 * config nor an id the activator can apply.
 */
internal fun replayRouteFor(
    candidateId: String,
    proxyConfigJson: String?,
    probe: DiagnosticsProbeResultUiModel,
): Route.ReplayFailure? {
    val domain = probe.target.trim()
    val config = proxyConfigJson?.takeIf(String::isNotBlank)
    val applicable = config != null || candidateId in replayableStrategyCandidateIds()
    return if (probe.tone == DiagnosticsTone.Negative && applicable && HostnamePattern.matches(domain)) {
        Route.ReplayFailure(domain = domain, strategyId = candidateId, proxyConfigJson = config)
    } else {
        null
    }
}
