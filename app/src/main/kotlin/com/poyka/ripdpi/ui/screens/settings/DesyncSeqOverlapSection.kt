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

private data class SeqOverlapStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Suppress("LongMethod")
@Composable
internal fun SeqOverlapProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberSeqOverlapStatus(uiState)
    val primaryStep = uiState.desync.primarySeqOverlapStep
    val scopeSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled -> {
                stringResource(R.string.ripdpi_seqovl_summary_scope_http_https)
            }

            uiState.desyncHttpEnabled -> {
                stringResource(R.string.ripdpi_seqovl_summary_scope_http)
            }

            uiState.desyncHttpsEnabled -> {
                stringResource(R.string.ripdpi_seqovl_summary_scope_https)
            }

            else -> {
                stringResource(R.string.ripdpi_seqovl_summary_scope_none)
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
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_seqovl_summary_label_profile),
                value =
                    if (uiState.desync.hasSeqOverlap) {
                        stringResource(R.string.ripdpi_seqovl_summary_profile_configured)
                    } else {
                        stringResource(R.string.ripdpi_seqovl_summary_profile_inactive)
                    },
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_seqovl_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_seqovl_summary_label_marker),
                value =
                    primaryStep?.marker?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.ripdpi_seqovl_summary_marker_none),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_seqovl_summary_label_overlap),
                value = uiState.desync.seqOverlapEffectiveSize.toString(),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_seqovl_summary_label_fake_mode),
                value =
                    primaryStep?.fakeMode?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.ripdpi_seqovl_summary_fake_mode_profile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_seqovl_summary_label_runtime),
                value =
                    if (uiState.seqovlSupported) {
                        stringResource(R.string.ripdpi_seqovl_summary_runtime_supported)
                    } else {
                        stringResource(R.string.ripdpi_seqovl_summary_runtime_fallback)
                    },
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_seqovl_scope_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (!uiState.seqovlSupported) {
            Text(
                text = stringResource(R.string.settings_seqovl_unsupported_reason),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun rememberSeqOverlapStatus(uiState: SettingsUiState): SeqOverlapStatusContent =
    when {
        uiState.enableCmdSettings -> {
            SeqOverlapStatusContent(
                label = stringResource(R.string.ripdpi_seqovl_cli_title),
                body = stringResource(R.string.ripdpi_seqovl_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.seqOverlapUnavailableOnDevice -> {
            SeqOverlapStatusContent(
                label = stringResource(R.string.ripdpi_seqovl_unsupported_title),
                body = stringResource(R.string.ripdpi_seqovl_unsupported_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.desync.hasSeqOverlap && uiState.isServiceRunning -> {
            SeqOverlapStatusContent(
                label = stringResource(R.string.ripdpi_seqovl_restart_title),
                body = stringResource(R.string.ripdpi_seqovl_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.desync.hasSeqOverlap -> {
            SeqOverlapStatusContent(
                label = stringResource(R.string.ripdpi_seqovl_ready_title),
                body = stringResource(R.string.ripdpi_seqovl_ready_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            SeqOverlapStatusContent(
                label = stringResource(R.string.ripdpi_seqovl_available_title),
                body = stringResource(R.string.ripdpi_seqovl_available_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }
