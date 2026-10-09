package com.poyka.ripdpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator

@Preview(name = "ConnectionActuator disconnected", widthDp = 360)
@Composable
private fun ConnectionActuatorDisconnectedPreview() {
    ConnectionActuatorScene(HomeConnectionActuatorStatus.Open)
}

@Preview(name = "ConnectionActuator dark", widthDp = 360)
@Composable
private fun ConnectionActuatorDarkPreview() {
    ConnectionActuatorScene(HomeConnectionActuatorStatus.Open, theme = "dark")
}

@Preview(name = "ConnectionActuator connecting", widthDp = 360)
@Composable
private fun ConnectionActuatorConnectingPreview() {
    ConnectionActuatorScene(HomeConnectionActuatorStatus.Engaging)
}

@Preview(name = "ConnectionActuator connected", widthDp = 360)
@Composable
private fun ConnectionActuatorConnectedPreview() {
    ConnectionActuatorScene(HomeConnectionActuatorStatus.Locked)
}

@Preview(name = "ConnectionActuator warning", widthDp = 360)
@Composable
private fun ConnectionActuatorWarningPreview() {
    ConnectionActuatorScene(HomeConnectionActuatorStatus.Degraded)
}

@Preview(name = "ConnectionActuator fault", widthDp = 360)
@Composable
private fun ConnectionActuatorFaultPreview() {
    ConnectionActuatorScene(HomeConnectionActuatorStatus.Fault)
}

@Preview(name = "ConnectionActuator RTL large text", widthDp = 360, fontScale = 2f, locale = "ar")
@Composable
private fun ConnectionActuatorRtlPreview() {
    ConnectionActuatorScene(HomeConnectionActuatorStatus.Locked)
}

@Preview(name = "ConnectionActuator Android lockdown", widthDp = 360, fontScale = 2f)
@Composable
private fun ConnectionActuatorLockdownPreview() {
    ConnectionActuatorScene(HomeConnectionActuatorStatus.Locked, lockdown = true)
}

@Composable
private fun ConnectionActuatorScene(
    status: HomeConnectionActuatorStatus,
    theme: String = "light",
    lockdown: Boolean = false,
) {
    val description =
        when (status) {
            HomeConnectionActuatorStatus.Open -> {
                stringResource(R.string.home_connection_actuator_state_open)
            }

            HomeConnectionActuatorStatus.Engaging -> {
                stringResource(R.string.home_connection_actuator_state_engaging)
            }

            HomeConnectionActuatorStatus.Locked -> {
                stringResource(R.string.home_connection_actuator_state_locked)
            }

            HomeConnectionActuatorStatus.Degraded -> {
                stringResource(
                    R.string.home_connection_actuator_state_degraded,
                    stringResource(R.string.home_connection_stage_dns),
                )
            }

            HomeConnectionActuatorStatus.Fault -> {
                stringResource(
                    R.string.home_connection_actuator_state_fault,
                    stringResource(R.string.home_connection_stage_tunnel),
                )
            }
        }
    val action = if (lockdown) stringResource(R.string.home_connection_actuator_action_android_managed) else ""
    val detail = if (status == HomeConnectionActuatorStatus.Fault) stringResource(R.string.connection_timed_out) else ""
    val route = stringResource(R.string.home_mode_vpn)
    val exit = stringResource(R.string.home_connection_actuator_direct)
    RipDpiComponentPreview(themePreference = theme) {
        RipDpiConnectionActuator(
            state =
                HomeConnectionActuatorUiState(
                    status = status,
                    statusDescription = description,
                    actionLabel = action,
                    routeLabel = route,
                    trailingLabel = exit,
                    faultDetail = detail,
                    deactivationEnabled = false.takeIf { lockdown },
                ),
            onActivate = {},
            onDeactivate = {},
        )
    }
}
