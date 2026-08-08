package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private val DefaultHomeConnectionActuatorStages =
    persistentListOf(
        HomeConnectionActuatorStageUiState(
            stage = HomeConnectionActuatorStage.Network,
            label = "Network",
            state = HomeConnectionActuatorStageState.Pending,
        ),
        HomeConnectionActuatorStageUiState(
            stage = HomeConnectionActuatorStage.Dns,
            label = "DNS",
            state = HomeConnectionActuatorStageState.Pending,
        ),
        HomeConnectionActuatorStageUiState(
            stage = HomeConnectionActuatorStage.Handshake,
            label = "Handshake",
            state = HomeConnectionActuatorStageState.Pending,
        ),
        HomeConnectionActuatorStageUiState(
            stage = HomeConnectionActuatorStage.Tunnel,
            label = "Tunnel",
            state = HomeConnectionActuatorStageState.Pending,
        ),
        HomeConnectionActuatorStageUiState(
            stage = HomeConnectionActuatorStage.Route,
            label = "Route",
            state = HomeConnectionActuatorStageState.Pending,
        ),
    )

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

@Immutable
data class HomeConnectionActuatorUiState(
    val status: HomeConnectionActuatorStatus = HomeConnectionActuatorStatus.Open,
    val leadingLabel: String = "Open",
    val trailingLabel: String = "Secure",
    val routeLabel: String = "Local VPN",
    val statusDescription: String = "Secure line disengaged",
    val actionLabel: String = "Engage secure line",
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
