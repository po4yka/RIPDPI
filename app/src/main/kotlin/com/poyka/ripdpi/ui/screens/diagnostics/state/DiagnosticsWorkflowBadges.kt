package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.R

internal fun workflowBadgeLabels(
    profile: DiagnosticsProfileOptionUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): List<Int> =
    buildList {
        when {
            isFullAudit -> {
                add(R.string.diagnostics_profile_badge_http_https_quic)
                add(R.string.diagnostics_profile_badge_all_builtin)
                add(R.string.diagnostics_profile_badge_raw_only)
                add(R.string.diagnostics_profile_badge_manual_apply)
            }

            strategyProbeSelected -> {
                add(R.string.diagnostics_profile_badge_http_https_quic)
                add(R.string.diagnostics_profile_badge_raw_only)
                add(R.string.diagnostics_profile_badge_manual_apply)
            }

            profile.isRawPathOnly -> {
                add(R.string.diagnostics_profile_badge_raw_only)
            }

            else -> {
                add(R.string.diagnostics_profile_badge_dns_http_https_tcp)
                add(R.string.diagnostics_profile_badge_raw_and_in_path)
            }
        }
        if (profile.manualOnly) {
            add(R.string.diagnostics_profile_badge_manual_only)
        }
        if (profile.regionTag?.equals("ru", ignoreCase = true) == true) {
            add(R.string.diagnostics_profile_badge_region_net)
        }
    }

internal fun DiagnosticsUiFactorySupport.buildWorkflowBadges(
    profile: DiagnosticsProfileOptionUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): List<DiagnosticsScanWorkflowBadgeUiModel> =
    workflowBadgeLabels(profile, strategyProbeSelected, isFullAudit).map { label ->
        DiagnosticsScanWorkflowBadgeUiModel(context.getString(label), DiagnosticsTone.Neutral)
    }
