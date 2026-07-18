package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.AndroidHardKillSwitchSnapshot
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus

@Immutable
data class HardKillSwitchUiState(
    val status: AndroidHardKillSwitchStatus = AndroidHardKillSwitchStatus.UNKNOWN,
    val label: String = "",
    val summary: String = "",
    val actionLabel: String? = null,
    val visible: Boolean = false,
    val blocksDisconnect: Boolean = false,
) {
    val warning: Boolean
        get() = status != AndroidHardKillSwitchStatus.ENABLED
}

internal fun buildHardKillSwitchUiState(
    snapshot: AndroidHardKillSwitchSnapshot,
    configuredMode: Mode,
    activeMode: Mode,
    appStatus: AppStatus,
    stringResolver: StringResolver,
): HardKillSwitchUiState {
    val relevantToCurrentPath =
        configuredMode == Mode.VPN || (appStatus == AppStatus.Running && activeMode == Mode.VPN)
    val blocksDisconnect =
        snapshot.status == AndroidHardKillSwitchStatus.ENABLED &&
            appStatus == AppStatus.Running &&
            activeMode == Mode.VPN
    return HardKillSwitchUiState(
        status = snapshot.status,
        label =
            when (snapshot.status) {
                AndroidHardKillSwitchStatus.ENABLED -> {
                    stringResolver.getString(R.string.home_hard_kill_switch_enabled_title)
                }

                AndroidHardKillSwitchStatus.NOT_ENABLED -> {
                    stringResolver.getString(R.string.home_hard_kill_switch_not_enabled_title)
                }

                AndroidHardKillSwitchStatus.UNKNOWN -> {
                    stringResolver.getString(R.string.home_hard_kill_switch_unknown_title)
                }
            },
        summary =
            when (snapshot.status) {
                AndroidHardKillSwitchStatus.ENABLED -> {
                    stringResolver.getString(R.string.home_hard_kill_switch_enabled_summary)
                }

                AndroidHardKillSwitchStatus.NOT_ENABLED -> {
                    stringResolver.getString(R.string.home_hard_kill_switch_not_enabled_summary)
                }

                AndroidHardKillSwitchStatus.UNKNOWN -> {
                    stringResolver.getString(R.string.home_hard_kill_switch_unknown_summary)
                }
            },
        actionLabel =
            if (snapshot.status == AndroidHardKillSwitchStatus.ENABLED) {
                stringResolver.getString(R.string.home_connection_actuator_action_android_managed)
            } else {
                stringResolver.getString(R.string.settings_permission_action_open_vpn_settings)
            },
        visible = relevantToCurrentPath,
        blocksDisconnect = blocksDisconnect,
    )
}
