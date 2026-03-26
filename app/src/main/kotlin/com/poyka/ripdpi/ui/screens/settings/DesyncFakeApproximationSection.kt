package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private data class FakeApproximationStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
internal fun FakeApproximationProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakeApproximationStatus(uiState)
    val primaryStep = uiState.desync.primaryFakeApproximationStep
    val profileSummary =
        when (uiState.desync.fakeApproximationStepCount) {
            0 -> {
                stringResource(R.string.ripdpi_fake_approx_summary_profile_none)
            }

            1 -> {
                stringResource(R.string.ripdpi_fake_approx_summary_profile_single)
            }

            else -> {
                stringResource(
                    R.string.ripdpi_fake_approx_summary_profile_multiple,
                    uiState.desync.fakeApproximationStepCount,
                )
            }
        }
    val scopeSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled -> {
                stringResource(R.string.ripdpi_fake_approx_summary_scope_http_https)
            }

            uiState.desyncHttpEnabled -> {
                stringResource(R.string.ripdpi_fake_approx_summary_scope_http)
            }

            uiState.desyncHttpsEnabled -> {
                stringResource(R.string.ripdpi_fake_approx_summary_scope_https)
            }

            else -> {
                stringResource(R.string.ripdpi_fake_approx_summary_scope_none)
            }
        }
    val modeSummary =
        when (primaryStep?.kind) {
            TcpChainStepKind.FakeSplit -> stringResource(R.string.ripdpi_fake_approx_summary_mode_fakedsplit)
            TcpChainStepKind.FakeDisorder -> stringResource(R.string.ripdpi_fake_approx_summary_mode_fakeddisorder)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_mode_none)
        }
    val markerSummary =
        primaryStep
            ?.marker
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_fake_approx_summary_marker_none)
    val transportSummary =
        when (primaryStep?.kind) {
            TcpChainStepKind.FakeSplit -> stringResource(R.string.ripdpi_fake_approx_summary_transport_fakedsplit)
            TcpChainStepKind.FakeDisorder -> stringResource(R.string.ripdpi_fake_approx_summary_transport_fakeddisorder)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_transport_none)
        }
    val badges =
        buildList {
            add(
                (
                    if (uiState.desync.hasFakeApproximation) {
                        stringResource(R.string.ripdpi_fake_approx_badge_configured)
                    } else {
                        stringResource(R.string.ripdpi_fake_approx_badge_available)
                    }
                    ) to
                    if (uiState.desync.hasFakeApproximation) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            add(stringResource(R.string.ripdpi_fake_approx_badge_linux_android) to SummaryCapsuleTone.Info)
            if (uiState.desyncHttpEnabled) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_http) to SummaryCapsuleTone.Info)
            }
            if (uiState.desyncHttpsEnabled) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_https) to SummaryCapsuleTone.Info)
            }
            if (uiState.desync.hasFakeSplitApproximation) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_fakedsplit) to SummaryCapsuleTone.Active)
            }
            if (uiState.desync.hasFakeDisorderApproximation) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_fakeddisorder) to SummaryCapsuleTone.Warning)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_mode),
                value = modeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_marker),
                value = markerSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_shared),
                value = stringResource(R.string.ripdpi_fake_approx_summary_shared),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_transport),
                value = transportSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_http_example),
                value = stringResource(R.string.ripdpi_fake_approx_example_http),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_tls_example),
                value = stringResource(R.string.ripdpi_fake_approx_example_tls),
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_fake_approx_scope_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun rememberFakeApproximationStatus(uiState: SettingsUiState): FakeApproximationStatusContent =
    when {
        uiState.enableCmdSettings -> {
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_cli_title),
                body = stringResource(R.string.ripdpi_fake_approx_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.fakeApproximationControlsRelevant -> {
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_protocols_off_title),
                body = stringResource(R.string.ripdpi_fake_approx_protocols_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.desync.hasFakeApproximation && uiState.isServiceRunning -> {
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_restart_title),
                body = stringResource(R.string.ripdpi_fake_approx_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.desync.hasFakeApproximation -> {
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_ready_title),
                body = stringResource(R.string.ripdpi_fake_approx_ready_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_available_title),
                body = stringResource(R.string.ripdpi_fake_approx_available_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }
