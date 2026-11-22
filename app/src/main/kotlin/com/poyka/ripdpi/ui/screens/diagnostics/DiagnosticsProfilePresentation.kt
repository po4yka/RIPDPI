package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.EchTransferComparisonProfileId
import com.poyka.ripdpi.diagnostics.Http3TransferComparisonProfileId
import com.poyka.ripdpi.diagnostics.TransferVolumeComparisonProfileId
import com.poyka.ripdpi.diagnostics.VerifiedDownloadProfileId
import com.poyka.ripdpi.diagnostics.VerifiedUploadProfileId
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.StrategyProbeSuiteFullMatrixV1

@Composable
@ReadOnlyComposable
internal fun diagnosticProfileTextResources(profile: DiagnosticsProfileOptionUiModel): Pair<String, String> =
    when {
        profile.id == "ip-family-connectivity" -> {
            stringResource(R.string.diagnostics_ip_title) to stringResource(R.string.diagnostics_ip_caution)
        }

        profile.id == com.poyka.ripdpi.diagnostics.TlsHopComparisonProfileId -> {
            stringResource(R.string.diagnostics_profile_connectivity_badge) to
                stringResource(R.string.diagnostics_tls_hop_observation)
        }

        profile.id == com.poyka.ripdpi.diagnostics.QuicVersionMatrixProfileId -> {
            stringResource(R.string.diagnostics_profile_connectivity_badge) to
                stringResource(R.string.diagnostics_quic_version_limit_initial_stage)
        }

        profile.id == VerifiedDownloadProfileId -> {
            stringResource(R.string.diagnostics_profile_connectivity_badge) to
                stringResource(R.string.diagnostics_verified_download_description)
        }

        profile.id == VerifiedUploadProfileId -> {
            stringResource(R.string.diagnostics_profile_connectivity_badge) to
                stringResource(R.string.diagnostics_verified_upload_description)
        }

        profile.id == EchTransferComparisonProfileId -> {
            stringResource(R.string.diagnostics_profile_connectivity_badge) to
                stringResource(R.string.diagnostics_ech_transfer_description)
        }

        profile.id == Http3TransferComparisonProfileId -> {
            stringResource(R.string.diagnostics_profile_connectivity_badge) to
                stringResource(R.string.diagnostics_http3_transfer_description)
        }

        profile.id == TransferVolumeComparisonProfileId -> {
            stringResource(R.string.diagnostics_profile_connectivity_badge) to
                stringResource(R.string.diagnostics_volume_transfer_description)
        }

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
    val manualRunOnly = stringResource(R.string.diagnostics_profile_manual_run_only)
    return buildString {
        append(description)
        // Some descriptions reuse short labels; close them with this locale's sentence end before more sentences.
        if (description.lastOrNull()?.isLetterOrDigit() == true) {
            append(manualRunOnly.last())
        }
        if (profile.manualOnly) {
            append(" ")
            append(manualRunOnly)
        }
        if (profile.requiresExplicitConsent) {
            append(" ${stringResource(R.string.diagnostics_profile_explicit_consent_required)}")
        }
        if (profile.packRefs.isNotEmpty()) {
            append(" ")
            append(
                pluralStringResource(
                    R.plurals.diagnostics_profile_curated_packs_included,
                    profile.packRefs.size,
                    profile.packRefs.size,
                ),
            )
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
