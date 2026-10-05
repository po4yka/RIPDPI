package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.RunningReconnectFailure
import com.poyka.ripdpi.services.RunningReconnectState

internal fun RunningReconnectFailure.message(strings: StringResolver): String =
    strings.getString(
        when (this) {
            RunningReconnectFailure.PermissionRequired -> R.string.runtime_config_permission
            RunningReconnectFailure.Lockdown -> R.string.runtime_config_lockdown
            RunningReconnectFailure.LockdownUnknown -> R.string.runtime_config_lockdown_unknown
            RunningReconnectFailure.StopTimedOut -> R.string.runtime_config_stop_timeout
            RunningReconnectFailure.Superseded -> R.string.runtime_config_superseded
            else -> R.string.runtime_config_start_failed
        },
    )

internal fun buildAppliedConfigurationUiState(
    inputs: MainUiInputs,
    strings: StringResolver,
): HomeAppliedConfigurationUiState {
    val (status, mode) = inputs.statusAndMode
    val application = inputs.applications[mode] ?: RuntimeConfigurationApplication.Unknown
    val reconnecting =
        inputs.reconnectState is RunningReconnectState.Stopping ||
            inputs.reconnectState is RunningReconnectState.Starting
    val confirmed =
        when (application) {
            is RuntimeConfigurationApplication.Applied -> application.configuration
            is RuntimeConfigurationApplication.Applying -> application.previous
            is RuntimeConfigurationApplication.Failed -> application.previous
            RuntimeConfigurationApplication.Unknown -> null
        }
    val pending = inputs.pendingConfigurations[mode] == RuntimeConfigurationPendingStatus.SavedChangesPending
    val statusKey = application.statusKey(pending, reconnecting)
    val message =
        when (val reconnect = inputs.reconnectState) {
            is RunningReconnectState.Failed -> reconnect.reason.message(strings)
            is RunningReconnectState.Cancelled -> strings.getString(R.string.runtime_config_cancelled)
            else -> null
        }
    return HomeAppliedConfigurationUiState(
        visible =
            status != AppStatus.Halted || application is RuntimeConfigurationApplication.Failed || message != null,
        status = strings.getString(statusKey),
        confirmedSummary =
            confirmed?.let {
                // Whitelisted applied facets only; profile names, endpoint hosts and credentials never enter the view.
                confirmedRuntimeSummary(it, strings)
            },
        previous = application !is RuntimeConfigurationApplication.Applied && confirmed != null,
        pending = pending,
        reconnecting = reconnecting,
        message = message,
        confirmation = application.reconnectConfirmation(mode, status, pending, reconnecting),
    )
}

private fun RuntimeConfigurationApplication.statusKey(
    pending: Boolean,
    reconnecting: Boolean,
): Int =
    when {
        reconnecting || this is RuntimeConfigurationApplication.Applying -> R.string.runtime_config_applying
        this is RuntimeConfigurationApplication.Failed -> R.string.runtime_config_failed
        pending -> R.string.runtime_config_pending
        this is RuntimeConfigurationApplication.Applied -> R.string.runtime_config_applied
        else -> R.string.runtime_config_unknown
    }

private fun RuntimeConfigurationApplication.reconnectConfirmation(
    mode: com.poyka.ripdpi.data.Mode,
    status: AppStatus,
    pending: Boolean,
    reconnecting: Boolean,
): com.poyka.ripdpi.services.RunningReconnectRequest.ConfirmedRuntime? =
    when {
        status != AppStatus.Running -> {
            null
        }

        !pending || reconnecting -> {
            null
        }

        this !is RuntimeConfigurationApplication.Applied -> {
            null
        }

        else -> {
            com.poyka.ripdpi.services.RunningReconnectRequest.ConfirmedRuntime(
                mode,
                configuration.runtimeId,
                configuration.revision,
            )
        }
    }
