package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R

internal fun DiagnosticsUiFactorySupport.buildWorkflowBadges(
    profile: DiagnosticsProfileOptionUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): List<DiagnosticsScanWorkflowBadgeUiModel> =
    buildList {
        if (isFullAudit) {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_http_https_quic),
                    DiagnosticsTone.Neutral,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_all_builtin),
                    DiagnosticsTone.Neutral,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_raw_only),
                    DiagnosticsTone.Neutral,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_manual_apply),
                    DiagnosticsTone.Neutral,
                ),
            )
        } else if (strategyProbeSelected) {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_http_https_quic),
                    DiagnosticsTone.Neutral,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_raw_only),
                    DiagnosticsTone.Neutral,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_manual_apply),
                    DiagnosticsTone.Neutral,
                ),
            )
        } else {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_dns_http_https_tcp),
                    DiagnosticsTone.Neutral,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_raw_and_in_path),
                    DiagnosticsTone.Neutral,
                ),
            )
        }
        if (profile.manualOnly) {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_manual_only),
                    DiagnosticsTone.Neutral,
                ),
            )
        }
        if (profile.regionTag?.equals("ru", ignoreCase = true) == true) {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    context.getString(R.string.diagnostics_profile_badge_region_net),
                    DiagnosticsTone.Neutral,
                ),
            )
        }
    }
