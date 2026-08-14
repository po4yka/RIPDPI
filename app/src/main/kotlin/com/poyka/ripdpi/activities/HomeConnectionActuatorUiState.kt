package com.poyka.ripdpi.activities

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Seed stages carry no label: the composable resolves a blank one from
 * [labelRes]. See the note on [HomeConnectionActuatorUiState] for why literals
 * cannot live here.
 */
private val DefaultHomeConnectionActuatorStages =
    HomeConnectionActuatorStage.entries
        .map { stage ->
            HomeConnectionActuatorStageUiState(
                stage = stage,
                label = "",
                state = HomeConnectionActuatorStageState.Pending,
            )
        }.toImmutableList()

enum class HomeConnectionActuatorStatus {
    Open,
    Engaging,
    Locked,
    Degraded,
    Fault,
}

enum class HomeConnectionActuatorStage(
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
fun HomeConnectionActuatorStage.labelRes(): Int =
    when (this) {
        HomeConnectionActuatorStage.Network -> R.string.home_connection_stage_network
        HomeConnectionActuatorStage.Dns -> R.string.home_connection_stage_dns
        HomeConnectionActuatorStage.Handshake -> R.string.home_connection_stage_handshake
        HomeConnectionActuatorStage.Tunnel -> R.string.home_connection_stage_tunnel
        HomeConnectionActuatorStage.Route -> R.string.home_connection_stage_route
    }

enum class HomeConnectionActuatorStageState {
    Pending,
    Active,
    Complete,
    Warning,
    Failed,
}

@Immutable
data class HomeConnectionActuatorStageUiState(
    val stage: HomeConnectionActuatorStage,
    val label: String,
    val state: HomeConnectionActuatorStageState,
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
data class HomeConnectionActuatorUiState(
    val status: HomeConnectionActuatorStatus = HomeConnectionActuatorStatus.Open,
    val trailingLabel: String = "",
    val routeLabel: String = "",
    val statusDescription: String = "",
    val actionLabel: String = "",
    val carriageFraction: Float = 0f,
    val stages: ImmutableList<HomeConnectionActuatorStageUiState> = DefaultHomeConnectionActuatorStages,
    val deactivationEnabled: Boolean? = null,
) {
    val isActivationAvailable: Boolean
        get() = status == HomeConnectionActuatorStatus.Open || status == HomeConnectionActuatorStatus.Fault

    val isDeactivationAvailable: Boolean
        get() =
            deactivationEnabled ?: (
                status == HomeConnectionActuatorStatus.Engaging ||
                    status == HomeConnectionActuatorStatus.Locked ||
                    status == HomeConnectionActuatorStatus.Degraded
            )
}
