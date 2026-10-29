package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.diagnostics.replayableStrategyCandidateIds
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag

private val HostnamePattern = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

/**
 * Returns the replay destination for a failed [probe] run under strategy [candidateId], or null
 * when the probe did not fail, the target is not a hostname, or the candidate has neither a saved
 * scan ([replaySessionId], for a native candidate with a stored config) nor an id the strategy
 * activator can apply. [replayableStrategyCandidateIds] comes from the same built-in list the
 * activator's candidate provider serves, so it is authoritative for the Kotlin ids.
 */
internal fun replayRouteFor(
    candidateId: String,
    replaySessionId: String?,
    probe: DiagnosticsProbeResultUiModel,
): Route.ReplayFailure? {
    val domain = probe.target.trim()
    val sessionId = replaySessionId?.takeIf(String::isNotBlank)
    val applicable = sessionId != null || candidateId in replayableStrategyCandidateIds()
    return if (probe.tone == DiagnosticsTone.Negative && applicable && HostnamePattern.matches(domain)) {
        Route.ReplayFailure(domain = domain, strategyId = candidateId, sessionId = sessionId)
    } else {
        null
    }
}

@Composable
internal fun ProbeReplayAction(
    candidateId: String,
    replaySessionId: String?,
    probe: DiagnosticsProbeResultUiModel,
    onReplayProbe: ((Route.ReplayFailure) -> Unit)?,
) {
    val route = replayRouteFor(candidateId, replaySessionId, probe)
    if (onReplayProbe != null && route != null) {
        RipDpiButton(
            text = stringResource(R.string.vpn_replay_failure_replay),
            onClick = { onReplayProbe(route) },
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DiagnosticsProbeReplayAction),
        )
    }
}
