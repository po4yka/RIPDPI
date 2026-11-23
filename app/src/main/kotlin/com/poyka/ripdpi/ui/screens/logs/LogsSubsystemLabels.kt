package com.poyka.ripdpi.ui.screens.logs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R

@Composable
internal fun subsystemLabel(subsystem: LogSubsystem): String =
    when (subsystem) {
        LogSubsystem.Service -> stringResource(R.string.logs_subsystem_service)
        LogSubsystem.Proxy -> stringResource(R.string.logs_subsystem_proxy)
        LogSubsystem.Tunnel -> stringResource(R.string.logs_subsystem_tunnel)
        LogSubsystem.Diagnostics -> stringResource(R.string.logs_subsystem_diagnostics)
        LogSubsystem.Relay -> stringResource(R.string.config_path_relay)
        LogSubsystem.Warp -> stringResource(R.string.warp_profile_default_name)
        LogSubsystem.AmneziaWg -> "AmneziaWG"
    }
