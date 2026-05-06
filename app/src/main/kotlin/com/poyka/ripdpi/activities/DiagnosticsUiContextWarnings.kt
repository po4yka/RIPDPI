package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel

internal fun DiagnosticsUiFactorySupport.buildContextWarnings(
    context: DiagnosticContextModel?,
): List<DiagnosticsEventUiModel> {
    if (context == null) {
        return emptyList()
    }
    return buildList {
        if (context.permissions.vpnPermissionState == "disabled") {
            add(contextWarning("context-vpn-permission", R.string.diagnostics_warn_vpn_permission))
        }
        if (context.permissions.notificationPermissionState == "disabled") {
            add(contextWarning("context-notification-permission", R.string.diagnostics_warn_notification_permission))
        }
        if (context.hasPowerRestriction()) {
            add(contextWarning("context-power-restriction", R.string.diagnostics_warn_power_restriction))
        }
    }
}

private fun DiagnosticContextModel.hasPowerRestriction(): Boolean =
    permissions.dataSaverState == "enabled" || environment.powerSaveModeState == "enabled"

private fun DiagnosticsUiFactorySupport.contextWarning(
    id: String,
    messageRes: Int,
): DiagnosticsEventUiModel =
    DiagnosticsEventUiModel(
        id = id,
        source = "Context",
        severity = "WARN",
        message = context.getString(messageRes),
        createdAtLabel = "now",
        tone = DiagnosticsTone.Warning,
    )
