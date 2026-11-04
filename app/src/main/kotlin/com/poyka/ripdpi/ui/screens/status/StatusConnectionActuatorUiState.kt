package com.poyka.ripdpi.ui.screens.status

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.diagnostics.state.label
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration

/**
 * Seed stages carry no label: the composable resolves a blank one from
 * [labelRes]. See the note on [StatusConnectionActuatorUiState] for why literals
 * cannot live here.
 */
private val DefaultStatusConnectionActuatorStages =
    StatusConnectionActuatorStage.entries
        .map { stage ->
            StatusConnectionActuatorStageUiState(
                stage = stage,
                label = "",
                state = StatusConnectionActuatorStageState.Pending,
            )
        }.toImmutableList()

enum class StatusConnectionActuatorStatus {
    Open,
    Engaging,
    Locked,
    Degraded,
    Fault,
}

enum class StatusConnectionActuatorStage(
    val stableKey: String,
) {
    Network("network"),
    Dns("dns"),
    Handshake("handshake"),
    Tunnel("tunnel"),
    Route("route"),
}

/** Single source for a stage's label, shared by the resolver and the composable. */
@StringRes
fun StatusConnectionActuatorStage.labelRes(): Int =
    when (this) {
        StatusConnectionActuatorStage.Network -> R.string.status_connection_stage_network
        StatusConnectionActuatorStage.Dns -> R.string.status_connection_stage_dns
        StatusConnectionActuatorStage.Handshake -> R.string.status_connection_stage_handshake
        StatusConnectionActuatorStage.Tunnel -> R.string.status_connection_stage_tunnel
        StatusConnectionActuatorStage.Route -> R.string.status_connection_stage_route
    }

enum class StatusConnectionActuatorStageState {
    Pending,
    Active,
    Complete,
    Warning,
    Failed,
}

@Immutable
data class StatusConnectionActuatorStageUiState(
    val stage: StatusConnectionActuatorStage,
    val label: String,
    val state: StatusConnectionActuatorStageState,
)

/**
 * Every user-visible string defaults to blank, meaning "not resolved yet", and
 * the composable substitutes the localized default.
 *
 * These defaults are not decorative: `MainUiStateOwner` seeds its `stateIn` with
 * `MainUiState()`, so this object is what every launch renders until the
 * resolver-backed state arrives. English literals here shipped untranslated on
 * the first frame in all eight non-English locales, and were baked into the
 * Arabic, Persian and Simplified Chinese home goldens, which made those
 * fixtures record the bug rather than catch it.
 */
@Immutable
data class StatusConnectionActuatorUiState(
    val status: StatusConnectionActuatorStatus = StatusConnectionActuatorStatus.Open,
    val trailingLabel: String = "",
    val routeLabel: String = "",
    val statusDescription: String = "",
    val actionLabel: String = "",
    /**
     * What actually went wrong, carried by the control that reports the problem
     * rather than by a banner beside it. Empty while there is no fault or warning.
     */
    val issueDetail: String = "",
    /** The step chain of the active bypass strategy; blank hides the strategy panel. */
    val strategyLabel: String = "",
    val strategyFacets: ImmutableList<StatusModeSummaryFacet> = persistentListOf(),
    /** What the chain does to a packet; null keeps the panel to the chain text. */
    val strategyPassage: StrategyPassageScene? = null,
    /** Elapsed time of the running session, shown when the user confirms a disconnect. */
    val sessionDuration: Duration = Duration.ZERO,
    val stages: ImmutableList<StatusConnectionActuatorStageUiState> = DefaultStatusConnectionActuatorStages,
    val activationEnabled: Boolean? = null,
    val deactivationEnabled: Boolean? = null,
) {
    val isActivationAvailable: Boolean
        get() =
            activationEnabled
                ?: (status == StatusConnectionActuatorStatus.Open || status == StatusConnectionActuatorStatus.Fault)

    val isDeactivationAvailable: Boolean
        get() =
            deactivationEnabled ?: (
                status == StatusConnectionActuatorStatus.Engaging ||
                    status == StatusConnectionActuatorStatus.Locked ||
                    status == StatusConnectionActuatorStatus.Degraded
            )
}
