package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.poyka.ripdpi.ui.components.EnableKeyboardInput
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStage
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStageState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStageUiState
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf

// Fixtures shared by the actuator's interaction and layout suites, which are
// split along the same seam as the component itself.

internal fun ComposeContentTestRule.setActuator(
    state: StatusConnectionActuatorUiState,
    onActivate: () -> Unit = {},
    onDeactivate: () -> Unit = {},
    keyboardInput: Boolean = false,
) {
    setContent {
        if (keyboardInput) EnableKeyboardInput()
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

internal fun actuatorState(status: StatusConnectionActuatorStatus): StatusConnectionActuatorUiState =
    StatusConnectionActuatorUiState(
        status = status,
        trailingLabel = "Direct",
        routeLabel = "Local VPN",
        statusDescription = "State $status",
        actionLabel = "Action $status",
        stages =
            if (status == StatusConnectionActuatorStatus.Engaging) {
                persistentListOf(
                    *StatusConnectionActuatorStage.entries
                        .map {
                            stage(it, StatusConnectionActuatorStageState.Pending)
                        }.toTypedArray(),
                )
            } else {
                persistentListOf(
                    stage(StatusConnectionActuatorStage.Network, StatusConnectionActuatorStageState.Complete),
                    stage(StatusConnectionActuatorStage.Dns, stageStateForDns(status)),
                    stage(StatusConnectionActuatorStage.Handshake, StatusConnectionActuatorStageState.Complete),
                    stage(StatusConnectionActuatorStage.Tunnel, stageStateForTunnel(status)),
                    stage(StatusConnectionActuatorStage.Route, StatusConnectionActuatorStageState.Complete),
                )
            },
    )

private fun stageStateForDns(status: StatusConnectionActuatorStatus): StatusConnectionActuatorStageState =
    if (status == StatusConnectionActuatorStatus.Degraded) {
        StatusConnectionActuatorStageState.Warning
    } else {
        StatusConnectionActuatorStageState.Complete
    }

private fun stageStateForTunnel(status: StatusConnectionActuatorStatus): StatusConnectionActuatorStageState =
    if (status == StatusConnectionActuatorStatus.Fault) {
        StatusConnectionActuatorStageState.Failed
    } else {
        StatusConnectionActuatorStageState.Complete
    }

private fun stage(
    stage: StatusConnectionActuatorStage,
    state: StatusConnectionActuatorStageState,
): StatusConnectionActuatorStageUiState =
    StatusConnectionActuatorStageUiState(
        stage = stage,
        label =
            when (stage) {
                StatusConnectionActuatorStage.Network -> "Network"
                StatusConnectionActuatorStage.Dns -> "DNS"
                StatusConnectionActuatorStage.Handshake -> "Handshake"
                StatusConnectionActuatorStage.Tunnel -> "Tunnel"
                StatusConnectionActuatorStage.Route -> "Route"
            },
        state = state,
    )

internal fun SemanticsNodeInteraction.assertHasRole(expected: Role): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, expected))
