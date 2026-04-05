package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private data class FakeTlsStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Suppress("LongMethod")
@Composable
internal fun FakeTlsProfileCard(
    uiState: SettingsUiState,
    onResetFakeTlsProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakeTlsStatus(uiState)
    val baseSummary =
        stringResource(
            if (uiState.fake.fakeTlsUseOriginal) {
                R.string.ripdpi_fake_tls_summary_base_original
            } else {
                R.string.ripdpi_fake_tls_summary_base_default
            },
        )
    val sniSummary =
        if (uiState.fake.fakeTlsSniMode == FakeTlsSniModeFixed) {
            stringResource(
                R.string.ripdpi_fake_tls_summary_sni_fixed,
                uiState.fake.fakeSni.ifBlank { DefaultFakeSni },
            )
        } else {
            stringResource(R.string.ripdpi_fake_tls_summary_sni_randomized)
        }
    val mutationSummary =
        buildList {
            if (uiState.fake.fakeTlsRandomize) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_randomize))
            if (uiState.fake.fakeTlsDupSessionId) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_dup_sid))
            if (uiState.fake.fakeTlsPadEncap) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_pad_encap))
        }.ifEmpty {
            listOf(stringResource(R.string.ripdpi_fake_tls_summary_mutation_none))
        }.joinToString(", ")
    val sizeSummary =
        when {
            uiState.fake.fakeTlsSize > 0 -> {
                stringResource(R.string.ripdpi_fake_tls_summary_size_exact, uiState.fake.fakeTlsSize)
            }

            uiState.fake.fakeTlsSize < 0 -> {
                stringResource(R.string.ripdpi_fake_tls_summary_size_minus, -uiState.fake.fakeTlsSize)
            }

            else -> {
                stringResource(R.string.ripdpi_fake_tls_summary_size_input)
            }
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.ripdpi_fake_tls_scope_cli)
            !uiState.desyncHttpsEnabled -> stringResource(R.string.ripdpi_fake_tls_scope_https_disabled)
            !uiState.isFake -> stringResource(R.string.ripdpi_fake_tls_scope_needs_fake)
            uiState.isServiceRunning -> stringResource(R.string.ripdpi_fake_tls_scope_restart)
            else -> stringResource(R.string.ripdpi_fake_tls_scope_applies)
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
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_base),
                value = baseSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_sni),
                value = sniSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_mutations),
                value = mutationSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_size),
                value = sizeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_scope),
                value = scopeSummary,
            )
        }
        if (uiState.canResetFakeTlsProfile) {
            var showResetDialog by remember { mutableStateOf(false) }

            if (showResetDialog) {
                RipDpiDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = stringResource(R.string.confirm_reset_fake_tls_profile_title),
                    dismissAction =
                        RipDpiDialogAction(
                            label = stringResource(R.string.confirm_reset_fake_tls_profile_dismiss),
                            onClick = { showResetDialog = false },
                        ),
                    confirmAction =
                        RipDpiDialogAction(
                            label = stringResource(R.string.confirm_reset_fake_tls_profile_confirm),
                            onClick = {
                                showResetDialog = false
                                onResetFakeTlsProfile()
                            },
                        ),
                    visuals =
                        RipDpiDialogVisuals(
                            message = stringResource(R.string.confirm_reset_fake_tls_profile_body),
                            tone = RipDpiDialogTone.Destructive,
                        ),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                RipDpiButton(
                    text = stringResource(R.string.ripdpi_fake_tls_reset_action),
                    onClick = { showResetDialog = true },
                    variant = RipDpiButtonVariant.Outline,
                    trailingIcon = RipDpiIcons.Close,
                )
            }
            Text(
                text = stringResource(R.string.ripdpi_fake_tls_reset_hint),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun rememberFakeTlsStatus(uiState: SettingsUiState): FakeTlsStatusContent =
    when {
        uiState.enableCmdSettings -> {
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_cli_status_title),
                body = stringResource(R.string.ripdpi_fake_tls_cli_status_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.desyncHttpsEnabled -> {
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_https_disabled_title),
                body = stringResource(R.string.ripdpi_fake_tls_https_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        !uiState.isFake && uiState.fake.hasCustomFakeTlsProfile -> {
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_saved_title),
                body = stringResource(R.string.ripdpi_fake_tls_saved_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.isFake -> {
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_waiting_title),
                body = stringResource(R.string.ripdpi_fake_tls_waiting_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.fake.hasCustomFakeTlsProfile -> {
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_custom_title),
                body = stringResource(R.string.ripdpi_fake_tls_custom_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_default_title),
                body = stringResource(R.string.ripdpi_fake_tls_default_body),
                tone = StatusIndicatorTone.Active,
            )
        }
    }
