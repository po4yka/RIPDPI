package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private data class FakePayloadLibraryStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
internal fun FakePayloadLibraryCard(
    uiState: SettingsUiState,
    onResetFakePayloadLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakePayloadLibraryStatus(uiState)
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> {
                stringResource(R.string.fake_payload_library_scope_cli)
            }

            !uiState.fakePayloadLibraryControlsRelevant -> {
                stringResource(R.string.fake_payload_library_scope_protocols_disabled)
            }

            uiState.httpFakeProfileActiveInStrategy && uiState.udpFakeProfileActiveInStrategy -> {
                stringResource(R.string.fake_payload_library_scope_tcp_and_udp_live)
            }

            uiState.httpFakeProfileActiveInStrategy || uiState.tlsFakeProfileActiveInStrategy -> {
                stringResource(R.string.fake_payload_library_scope_tcp_live)
            }

            uiState.udpFakeProfileActiveInStrategy -> {
                stringResource(R.string.fake_payload_library_scope_udp_live)
            }

            uiState.hasHostFake -> {
                stringResource(R.string.fake_payload_library_scope_hostfake_only)
            }

            else -> {
                stringResource(R.string.fake_payload_library_scope_active)
            }
        }
    val badges =
        buildList {
            if (uiState.hasCustomFakePayloadProfiles) {
                add(stringResource(R.string.fake_payload_library_badge_custom) to SummaryCapsuleTone.Active)
            } else {
                add(stringResource(R.string.fake_payload_library_badge_default) to SummaryCapsuleTone.Neutral)
            }
            if (uiState.isFake) {
                add(stringResource(R.string.fake_payload_library_badge_tcp_fake_live) to SummaryCapsuleTone.Active)
            } else if (uiState.hasHostFake) {
                add(stringResource(R.string.fake_payload_library_badge_hostfake_only) to SummaryCapsuleTone.Info)
            }
            if (uiState.hasUdpFakeBurst) {
                add(
                    stringResource(R.string.fake_payload_library_badge_udp_burst, uiState.udpFakeCount) to
                        SummaryCapsuleTone.Active,
                )
            }
            if (uiState.quicFakeProfileActive) {
                add(stringResource(R.string.fake_payload_library_badge_quic_separate) to SummaryCapsuleTone.Info)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Elevated,
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
                label = stringResource(R.string.fake_payload_library_summary_label_http),
                value = formatHttpFakeProfileLabel(uiState.httpFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_tls),
                value = formatTlsFakeProfileLabel(uiState.tlsFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_udp),
                value = formatUdpFakeProfileLabel(uiState.udpFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_scope),
                value = scopeSummary,
            )
        }
        if (uiState.canResetFakePayloadLibrary) {
            RipDpiButton(
                text = stringResource(R.string.fake_payload_library_reset_action),
                onClick = onResetFakePayloadLibrary,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
internal fun FakePayloadProfileCard(
    title: String,
    description: String,
    profileLabel: String,
    statusLabel: String,
    statusTone: StatusIndicatorTone,
    badges: List<Pair<String, SummaryCapsuleTone>>,
    appliesSummary: String,
    interactionSummary: String,
    value: String,
    options: List<RipDpiDropdownOption<String>>,
    setting: AdvancedOptionSetting,
    onSelected: (AdvancedOptionSetting, String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = statusTone,
        )
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = description,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_current),
                value = profileLabel,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_when_used),
                value = appliesSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_interaction),
                value = interactionSummary,
            )
        }
        RipDpiDropdown(
            options = options,
            selectedValue = value,
            onValueSelected = { selectedValue -> onSelected(setting, selectedValue) },
            enabled = enabled,
        )
    }
}

@Composable
private fun rememberFakePayloadLibraryStatus(uiState: SettingsUiState): FakePayloadLibraryStatusContent =
    when {
        uiState.enableCmdSettings -> {
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_cli_title),
                body = stringResource(R.string.fake_payload_library_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.fakePayloadLibraryControlsRelevant -> {
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_protocols_disabled_title),
                body = stringResource(R.string.fake_payload_library_protocols_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.isServiceRunning && uiState.hasCustomFakePayloadProfiles -> {
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_restart_title),
                body = stringResource(R.string.fake_payload_library_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.hasCustomFakePayloadProfiles -> {
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_custom_title),
                body = stringResource(R.string.fake_payload_library_custom_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_default_title),
                body = stringResource(R.string.fake_payload_library_default_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }
