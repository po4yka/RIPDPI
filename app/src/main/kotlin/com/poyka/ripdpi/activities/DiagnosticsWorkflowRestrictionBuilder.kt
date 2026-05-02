package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R

internal fun DiagnosticsUiFactorySupport.buildWorkflowLabel(selectedProfile: DiagnosticsProfileOptionUiModel?): String =
    if (selectedProfile?.isFullAudit == true) {
        context.getString(R.string.diagnostics_scan_automatic_audit)
    } else {
        context.getString(R.string.diagnostics_scan_automatic_probing)
    }

internal fun DiagnosticsUiFactorySupport.buildWorkflowRestriction(
    params: BuildScanUiModelParams,
    selectedProfile: DiagnosticsProfileOptionUiModel?,
    strategyProbeSelected: Boolean,
): DiagnosticsWorkflowRestrictionUiModel? {
    if (!strategyProbeSelected) return null
    val workflowLabel = buildWorkflowLabel(selectedProfile)
    return when {
        params.rawArgsEnabled -> {
            DiagnosticsWorkflowRestrictionUiModel(
                reason = DiagnosticsWorkflowRestrictionReasonUiModel.COMMAND_LINE_MODE_ACTIVE,
                title =
                    if (selectedProfile?.isFullAudit == true) {
                        context.getString(R.string.diagnostics_audit_unavailable_title)
                    } else {
                        context.getString(R.string.diagnostics_probe_unavailable_title)
                    },
                body =
                    context.getString(
                        R.string.diagnostics_scan_workflow_blocked_command_line_body_format,
                        workflowLabel,
                        context.getString(R.string.use_command_line_settings),
                    ),
                actionLabel = context.getString(R.string.diagnostics_scan_open_advanced_settings),
                actionKind = DiagnosticsWorkflowRestrictionActionKindUiModel.OPEN_ADVANCED_SETTINGS,
            )
        }

        params.vpnPermissionDisabled -> {
            DiagnosticsWorkflowRestrictionUiModel(
                reason = DiagnosticsWorkflowRestrictionReasonUiModel.VPN_PERMISSION_DISABLED,
                title = context.getString(R.string.diagnostics_scan_vpn_permission_warning_title),
                body =
                    context.getString(
                        R.string.diagnostics_scan_vpn_permission_warning_body_format,
                        workflowLabel,
                    ),
                actionLabel = context.getString(R.string.diagnostics_scan_grant_vpn_permission),
                actionKind = DiagnosticsWorkflowRestrictionActionKindUiModel.OPEN_VPN_PERMISSION,
            )
        }

        else -> {
            null
        }
    }
}
