package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.StrategyProbeSuiteFullMatrixV1

@Composable
@ReadOnlyComposable
internal fun diagnosticProfileTextResources(profile: DiagnosticsProfileOptionUiModel): Pair<String, String> =
    when {
        profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.WEB_CONNECTIVITY -> {
            stringResource(R.string.diagnostics_profile_badge_ru_web) to
                stringResource(R.string.diagnostics_profile_desc_web_connectivity)
        }

        profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.MESSAGING -> {
            stringResource(R.string.diagnostics_profile_badge_ru_msg) to
                stringResource(R.string.diagnostics_profile_desc_messaging)
        }

        profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.CIRCUMVENTION -> {
            stringResource(R.string.diagnostics_profile_badge_ru_adapt) to
                stringResource(R.string.diagnostics_profile_desc_adaptation)
        }

        profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.THROTTLING -> {
            stringResource(R.string.diagnostics_profile_badge_ru_rate) to
                stringResource(R.string.diagnostics_profile_desc_throttling)
        }

        profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.DPI_FULL -> {
            stringResource(R.string.diagnostics_profile_badge_ru_full) to
                stringResource(R.string.diagnostics_profile_desc_network_full)
        }

        profile.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1 -> {
            stringResource(R.string.diagnostics_profile_audit_badge) to
                stringResource(R.string.diagnostics_profile_audit_body)
        }

        profile.kind == com.poyka.ripdpi.diagnostics.ScanKind.STRATEGY_PROBE -> {
            stringResource(R.string.diagnostics_profile_probe_badge) to
                stringResource(R.string.diagnostics_profile_probe_body)
        }

        else -> {
            stringResource(R.string.diagnostics_profile_connectivity_badge) to
                stringResource(R.string.diagnostics_profile_connectivity_body)
        }
    }

@Composable
@ReadOnlyComposable
internal fun displayedDiagnosticProfileDescription(profile: DiagnosticsProfileOptionUiModel): String {
    val (_, description) = diagnosticProfileTextResources(profile)
    return buildString {
        append(description)
        if (profile.manualOnly) {
            append(" ")
            append(stringResource(R.string.diagnostics_profile_manual_run_only))
        }
        if (profile.requiresExplicitConsent) {
            append(" ${stringResource(R.string.diagnostics_profile_explicit_consent_required)}")
        }
        if (profile.packRefs.isNotEmpty()) {
            append(" ")
            append(stringResource(R.string.diagnostics_profile_curated_packs_included, profile.packRefs.size))
        }
    }
}

@Composable
@ReadOnlyComposable
internal fun displayFamilyLabel(family: com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily): String =
    when (family) {
        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.GENERAL -> {
            stringResource(
                R.string.diagnostics_family_general,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.WEB_CONNECTIVITY -> {
            stringResource(
                R.string.diagnostics_family_web_connectivity,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.MESSAGING -> {
            stringResource(
                R.string.diagnostics_family_messaging,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.CIRCUMVENTION -> {
            stringResource(
                R.string.diagnostics_family_adaptation,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.THROTTLING -> {
            stringResource(
                R.string.diagnostics_family_throttling,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.DPI_FULL -> {
            stringResource(
                R.string.diagnostics_family_network_full,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.AUTOMATIC_PROBING -> {
            stringResource(
                R.string.diagnostics_family_automatic_probing,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.AUTOMATIC_AUDIT -> {
            stringResource(
                R.string.diagnostics_family_automatic_audit,
            )
        }
    }
