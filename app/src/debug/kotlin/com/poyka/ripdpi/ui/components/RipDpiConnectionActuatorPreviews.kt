package com.poyka.ripdpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorUiState

@Preview(name = "ConnectionActuator disconnected", widthDp = 360)
@Composable
private fun ConnectionActuatorDisconnectedPreview() {
    ConnectionActuatorScene(StatusConnectionActuatorStatus.Open)
}

@Preview(name = "ConnectionActuator dark", widthDp = 360)
@Composable
private fun ConnectionActuatorDarkPreview() {
    ConnectionActuatorScene(StatusConnectionActuatorStatus.Open, theme = "dark")
}

@Preview(name = "ConnectionActuator connecting", widthDp = 360)
@Composable
private fun ConnectionActuatorConnectingPreview() {
    ConnectionActuatorScene(StatusConnectionActuatorStatus.Engaging)
}

@Preview(name = "ConnectionActuator connected", widthDp = 360)
@Composable
private fun ConnectionActuatorConnectedPreview() {
    ConnectionActuatorScene(StatusConnectionActuatorStatus.Locked)
}

@Preview(name = "ConnectionActuator warning", widthDp = 360)
@Composable
private fun ConnectionActuatorWarningPreview() {
    ConnectionActuatorScene(StatusConnectionActuatorStatus.Degraded)
}

@Preview(name = "ConnectionActuator fault", widthDp = 360)
@Composable
private fun ConnectionActuatorFaultPreview() {
    ConnectionActuatorScene(StatusConnectionActuatorStatus.Fault)
}

@Preview(name = "ConnectionActuator RTL large text", widthDp = 360, fontScale = 2f, locale = "ar")
@Composable
private fun ConnectionActuatorRtlPreview() {
    ConnectionActuatorScene(StatusConnectionActuatorStatus.Locked)
}

@Preview(name = "ConnectionActuator Android lockdown", widthDp = 360, fontScale = 2f)
@Composable
private fun ConnectionActuatorLockdownPreview() {
    ConnectionActuatorScene(StatusConnectionActuatorStatus.Locked, lockdown = true)
}

@Composable
private fun ConnectionActuatorScene(
    status: StatusConnectionActuatorStatus,
    theme: String = "light",
    lockdown: Boolean = false,
) {
    val description =
        when (status) {
            StatusConnectionActuatorStatus.Open -> {
                stringResource(R.string.home_connection_actuator_state_open)
            }

            StatusConnectionActuatorStatus.Engaging -> {
                stringResource(R.string.home_connection_actuator_state_engaging)
            }

            StatusConnectionActuatorStatus.Locked -> {
                stringResource(R.string.home_connection_actuator_state_locked)
            }

            StatusConnectionActuatorStatus.Degraded -> {
                stringResource(
                    R.string.home_connection_actuator_state_degraded,
                    stringResource(R.string.home_connection_stage_dns),
                )
            }

            StatusConnectionActuatorStatus.Fault -> {
                stringResource(
                    R.string.home_connection_actuator_state_fault,
                    stringResource(R.string.home_connection_stage_tunnel),
                )
            }
        }
    val action = if (lockdown) stringResource(R.string.home_connection_actuator_action_android_managed) else ""
    val detail =
        if (status ==
            StatusConnectionActuatorStatus.Fault
        ) {
            stringResource(R.string.connection_timed_out)
        } else {
            ""
        }
    val route = stringResource(R.string.home_mode_vpn)
    val exit = stringResource(R.string.home_connection_actuator_direct)
    RipDpiComponentPreview(themePreference = theme) {
        RipDpiConnectionActuator(
            state =
                StatusConnectionActuatorUiState(
                    status = status,
                    statusDescription = description,
                    actionLabel = action,
                    routeLabel = route,
                    trailingLabel = exit,
                    issueDetail = detail,
                    deactivationEnabled = false.takeIf { lockdown },
                ),
            onActivate = {},
            onDeactivate = {},
        )
    }
}
