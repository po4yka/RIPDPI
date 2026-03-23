package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private data class HostFakeStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
internal fun HostFakeProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberHostFakeStatus(uiState)
    val primaryStep = uiState.desync.primaryHostFakeStep
    val profileSummary =
        when (uiState.desync.hostFakeStepCount) {
            0 -> stringResource(R.string.ripdpi_hostfake_summary_profile_none)
            1 -> stringResource(R.string.ripdpi_hostfake_summary_profile_single)
            else -> stringResource(R.string.ripdpi_hostfake_summary_profile_multiple, uiState.desync.hostFakeStepCount)
        }
    val scopeSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled -> {
                stringResource(R.string.ripdpi_hostfake_summary_scope_http_https)
            }

            uiState.desyncHttpEnabled -> {
                stringResource(R.string.ripdpi_hostfake_summary_scope_http)
            }

            uiState.desyncHttpsEnabled -> {
                stringResource(R.string.ripdpi_hostfake_summary_scope_https)
            }

            else -> {
                stringResource(R.string.ripdpi_hostfake_summary_scope_none)
            }
        }
    val templateSummary =
        primaryStep
            ?.fakeHostTemplate
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_hostfake_summary_template_random)
    val midhostSummary =
        primaryStep
            ?.midhostMarker
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.ripdpi_hostfake_summary_midhost_marker, it) }
            ?: stringResource(R.string.ripdpi_hostfake_summary_midhost_whole)
    val endMarkerSummary =
        primaryStep
            ?.marker
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_hostfake_summary_end_marker_none)

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
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_template),
                value = templateSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_midhost),
                value = midhostSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_end_marker),
                value = endMarkerSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_transport),
                value = stringResource(R.string.ripdpi_hostfake_summary_transport, uiState.fake.fakeTtl),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_http_example),
                value = stringResource(R.string.ripdpi_hostfake_example_http),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_tls_example),
                value = stringResource(R.string.ripdpi_hostfake_example_tls),
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_hostfake_scope_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun rememberHostFakeStatus(uiState: SettingsUiState): HostFakeStatusContent =
    when {
        uiState.enableCmdSettings -> {
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_cli_title),
                body = stringResource(R.string.ripdpi_hostfake_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.hostFakeControlsRelevant -> {
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_protocols_off_title),
                body = stringResource(R.string.ripdpi_hostfake_protocols_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.hasHostFake && uiState.isServiceRunning -> {
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_restart_title),
                body = stringResource(R.string.ripdpi_hostfake_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.hasHostFake -> {
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_ready_title),
                body = stringResource(R.string.ripdpi_hostfake_ready_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_available_title),
                body = stringResource(R.string.ripdpi_hostfake_available_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }
