package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.poyka.ripdpi.activities.HomeConnectionActuatorStage
import com.poyka.ripdpi.activities.HomeConnectionActuatorStageState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStageUiState
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf

// Fixtures shared by the actuator's interaction and layout suites, which are
// split along the same seam as the component itself.

internal fun ComposeContentTestRule.setActuator(
    state: HomeConnectionActuatorUiState,
    onActivate: () -> Unit = {},
    onDeactivate: () -> Unit = {},
) {
    setContent {
        RipDpiTheme {
            RipDpiConnectionActuator(
                state = state,
                onActivate = onActivate,
                onDeactivate = onDeactivate,
                testTag = RipDpiTestTags.ConnectionActuatorButton,
            )
        }
    }
}

internal fun actuatorState(status: HomeConnectionActuatorStatus): HomeConnectionActuatorUiState =
    HomeConnectionActuatorUiState(
        status = status,
        trailingLabel = "Secure",
        routeLabel = "Local VPN",
        statusDescription = "State $status",
        actionLabel = "Action $status",
        carriageFraction =
            when (status) {
                HomeConnectionActuatorStatus.Open -> {
                    0f
                }

                HomeConnectionActuatorStatus.Engaging -> {
                    0.48f
                }

                HomeConnectionActuatorStatus.Locked,
                HomeConnectionActuatorStatus.Degraded,
                -> {
                    1f
                }

                HomeConnectionActuatorStatus.Fault -> {
                    0.68f
                }
            },
        stages =
            persistentListOf(
                stage(HomeConnectionActuatorStage.Network, HomeConnectionActuatorStageState.Complete),
                stage(HomeConnectionActuatorStage.Dns, stageStateForDns(status)),
                stage(HomeConnectionActuatorStage.Handshake, HomeConnectionActuatorStageState.Complete),
                stage(HomeConnectionActuatorStage.Tunnel, stageStateForTunnel(status)),
                stage(HomeConnectionActuatorStage.Route, HomeConnectionActuatorStageState.Complete),
            ),
    )

private fun stageStateForDns(status: HomeConnectionActuatorStatus): HomeConnectionActuatorStageState =
    if (status == HomeConnectionActuatorStatus.Degraded) {
        HomeConnectionActuatorStageState.Warning
    } else {
        HomeConnectionActuatorStageState.Complete
    }

private fun stageStateForTunnel(status: HomeConnectionActuatorStatus): HomeConnectionActuatorStageState =
    if (status == HomeConnectionActuatorStatus.Fault) {
        HomeConnectionActuatorStageState.Failed
    } else {
        HomeConnectionActuatorStageState.Complete
    }

private fun stage(
    stage: HomeConnectionActuatorStage,
    state: HomeConnectionActuatorStageState,
): HomeConnectionActuatorStageUiState =
    HomeConnectionActuatorStageUiState(
        stage = stage,
        label =
            when (stage) {
                HomeConnectionActuatorStage.Network -> "Network"
                HomeConnectionActuatorStage.Dns -> "DNS"
                HomeConnectionActuatorStage.Handshake -> "Handshake"
                HomeConnectionActuatorStage.Tunnel -> "Tunnel"
                HomeConnectionActuatorStage.Route -> "Route"
            },
        state = state,
    )

internal fun SemanticsNodeInteraction.assertHasRole(expected: Role): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, expected))
