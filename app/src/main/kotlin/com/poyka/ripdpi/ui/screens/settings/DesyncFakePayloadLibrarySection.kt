package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun FakePayloadLibraryControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val uiState = model.uiState
    Text(
        text = stringResource(R.string.fake_payload_library_section_title),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    Text(
        text =
            if (uiState.fakePayloadLibraryControlsRelevant) {
                stringResource(R.string.fake_payload_library_section_body)
            } else {
                stringResource(R.string.fake_payload_library_inactive)
            },
        style = RipDpiThemeTokens.type.secondaryBody,
        color = colors.mutedForeground,
    )
    FakePayloadLibraryCard(
        uiState = uiState,
        onResetFakePayloadLibrary = model.actions.onResetFakePayloadLibrary,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    HttpFakePayloadProfile(model)
    TlsFakePayloadProfile(model)
    UdpFakePayloadProfile(model)
}

@Composable
private fun HttpFakePayloadProfile(model: DesyncSectionModel) {
    val uiState = model.uiState
    FakePayloadProfileCard(
        title = stringResource(R.string.http_fake_profile_title),
        description = stringResource(R.string.http_fake_profile_body),
        profileLabel = formatHttpFakeProfileLabel(uiState.fake.httpFakeProfile),
        statusLabel = httpFakeProfileStatusLabel(uiState),
        statusTone = httpFakeProfileStatusTone(uiState),
        badges = httpFakeProfileBadges(uiState),
        appliesSummary = httpFakeProfileScope(uiState),
        interactionSummary = stringResource(R.string.http_fake_profile_interaction),
        value = uiState.fake.httpFakeProfile,
        options = model.options.httpFakeProfileOptions,
        setting = AdvancedOptionSetting.HttpFakeProfile,
        onSelected = model.actions.onOptionSelected,
        enabled = model.visualEditorEnabled,
        modifier = Modifier.padding(bottom = RipDpiThemeTokens.spacing.sm),
    )
}

@Composable
private fun TlsFakePayloadProfile(model: DesyncSectionModel) {
    val uiState = model.uiState
    FakePayloadProfileCard(
        title = stringResource(R.string.tls_fake_profile_title),
        description = stringResource(R.string.tls_fake_profile_body),
        profileLabel = formatTlsFakeProfileLabel(uiState.fake.tlsFakeProfile),
        statusLabel = tlsFakeProfileStatusLabel(uiState),
        statusTone = tlsFakeProfileStatusTone(uiState),
        badges = tlsFakeProfileBadges(uiState),
        appliesSummary = tlsFakeProfileScope(uiState),
        interactionSummary = stringResource(R.string.tls_fake_profile_interaction),
        value = uiState.fake.tlsFakeProfile,
        options = model.options.tlsFakeProfileOptions,
        setting = AdvancedOptionSetting.TlsFakeProfile,
        onSelected = model.actions.onOptionSelected,
        enabled = model.visualEditorEnabled,
        modifier = Modifier.padding(bottom = RipDpiThemeTokens.spacing.sm),
    )
}

@Composable
private fun UdpFakePayloadProfile(model: DesyncSectionModel) {
    val uiState = model.uiState
    FakePayloadProfileCard(
        title = stringResource(R.string.udp_fake_profile_title),
        description = stringResource(R.string.udp_fake_profile_body),
        profileLabel = formatUdpFakeProfileLabel(uiState.fake.udpFakeProfile),
        statusLabel = udpFakeProfileStatusLabel(uiState),
        statusTone = udpFakeProfileStatusTone(uiState),
        badges = udpFakeProfileBadges(uiState),
        appliesSummary = udpFakeProfileScope(uiState),
        interactionSummary = udpFakeProfileInteraction(uiState),
        value = uiState.fake.udpFakeProfile,
        options = model.options.udpFakeProfileOptions,
        setting = AdvancedOptionSetting.UdpFakeProfile,
        onSelected = model.actions.onOptionSelected,
        enabled = model.visualEditorEnabled,
        modifier = Modifier.padding(bottom = RipDpiThemeTokens.spacing.sm),
    )
}

@Composable
private fun httpFakeProfileStatusLabel(uiState: SettingsUiState): String =
    when {
        uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
        !uiState.desyncHttpEnabled -> stringResource(R.string.fake_payload_profile_status_off)
        uiState.httpFakeProfileActiveInStrategy -> stringResource(R.string.fake_payload_profile_status_live)
        uiState.hasHostFake -> stringResource(R.string.fake_payload_profile_status_separate)
        else -> stringResource(R.string.fake_payload_profile_status_ready)
    }

private fun httpFakeProfileStatusTone(uiState: SettingsUiState): StatusIndicatorTone =
    when {
        uiState.enableCmdSettings -> StatusIndicatorTone.Warning
        !uiState.desyncHttpEnabled -> StatusIndicatorTone.Idle
        uiState.httpFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
        uiState.hasHostFake -> StatusIndicatorTone.Idle
        else -> StatusIndicatorTone.Idle
    }

@Composable
private fun httpFakeProfileBadges(uiState: SettingsUiState): List<Pair<String, SummaryCapsuleTone>> =
    listOf(
        httpFakeEnabledBadge(uiState.desyncHttpEnabled),
        fakeStepBadge(
            active = uiState.httpFakeProfileActiveInStrategy,
            hasHostFake = uiState.hasHostFake,
        ),
    )

@Composable
private fun httpFakeProfileScope(uiState: SettingsUiState): String =
    when {
        !uiState.desyncHttpEnabled -> stringResource(R.string.http_fake_profile_scope_off)
        uiState.httpFakeProfileActiveInStrategy -> stringResource(R.string.http_fake_profile_scope_live)
        uiState.hasHostFake -> stringResource(R.string.http_fake_profile_scope_hostfake)
        else -> stringResource(R.string.http_fake_profile_scope_ready)
    }

@Composable
private fun tlsFakeProfileStatusLabel(uiState: SettingsUiState): String =
    when {
        uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
        !uiState.desyncHttpsEnabled -> stringResource(R.string.fake_payload_profile_status_off)
        uiState.tlsFakeProfileActiveInStrategy -> stringResource(R.string.fake_payload_profile_status_live)
        uiState.hasHostFake -> stringResource(R.string.fake_payload_profile_status_separate)
        else -> stringResource(R.string.fake_payload_profile_status_ready)
    }

private fun tlsFakeProfileStatusTone(uiState: SettingsUiState): StatusIndicatorTone =
    when {
        uiState.enableCmdSettings -> StatusIndicatorTone.Warning
        !uiState.desyncHttpsEnabled -> StatusIndicatorTone.Idle
        uiState.tlsFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
        uiState.hasHostFake -> StatusIndicatorTone.Idle
        else -> StatusIndicatorTone.Idle
    }

@Composable
private fun tlsFakeProfileBadges(uiState: SettingsUiState): List<Pair<String, SummaryCapsuleTone>> =
    listOf(
        httpsFakeEnabledBadge(uiState.desyncHttpsEnabled),
        fakeStepBadge(
            active = uiState.tlsFakeProfileActiveInStrategy,
            hasHostFake = uiState.hasHostFake,
        ),
        stringResource(R.string.fake_payload_badge_fake_tls_layers) to SummaryCapsuleTone.Info,
    )

@Composable
private fun tlsFakeProfileScope(uiState: SettingsUiState): String =
    when {
        !uiState.desyncHttpsEnabled -> stringResource(R.string.tls_fake_profile_scope_off)
        uiState.tlsFakeProfileActiveInStrategy -> stringResource(R.string.tls_fake_profile_scope_live)
        uiState.hasHostFake -> stringResource(R.string.tls_fake_profile_scope_hostfake)
        else -> stringResource(R.string.tls_fake_profile_scope_ready)
    }

@Composable
private fun udpFakeProfileStatusLabel(uiState: SettingsUiState): String =
    when {
        uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
        !uiState.desyncUdpEnabled -> stringResource(R.string.fake_payload_profile_status_off)
        uiState.udpFakeProfileActiveInStrategy -> stringResource(R.string.fake_payload_profile_status_live)
        else -> stringResource(R.string.fake_payload_profile_status_ready)
    }

private fun udpFakeProfileStatusTone(uiState: SettingsUiState): StatusIndicatorTone =
    when {
        uiState.enableCmdSettings -> StatusIndicatorTone.Warning
        !uiState.desyncUdpEnabled -> StatusIndicatorTone.Idle
        uiState.udpFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
        else -> StatusIndicatorTone.Idle
    }

@Composable
private fun udpFakeProfileBadges(uiState: SettingsUiState): List<Pair<String, SummaryCapsuleTone>> =
    buildList {
        add(udpFakeEnabledBadge(uiState.desyncUdpEnabled))
        add(
            (
                if (uiState.udpFakeProfileActiveInStrategy) {
                    stringResource(R.string.fake_payload_badge_burst_ready, uiState.desync.udpFakeCount)
                } else {
                    stringResource(R.string.fake_payload_badge_burst_needed)
                }
            ) to
                if (uiState.udpFakeProfileActiveInStrategy) {
                    SummaryCapsuleTone.Active
                } else {
                    SummaryCapsuleTone.Warning
                },
        )
        if (uiState.quic.quicFakeProfileActive) {
            add(stringResource(R.string.fake_payload_badge_quic_separate) to SummaryCapsuleTone.Info)
        }
    }

@Composable
private fun udpFakeProfileScope(uiState: SettingsUiState): String =
    when {
        !uiState.desyncUdpEnabled -> {
            stringResource(R.string.udp_fake_profile_scope_off)
        }

        uiState.udpFakeProfileActiveInStrategy -> {
            stringResource(R.string.udp_fake_profile_scope_live, uiState.desync.udpFakeCount)
        }

        else -> {
            stringResource(R.string.udp_fake_profile_scope_ready)
        }
    }

@Composable
private fun udpFakeProfileInteraction(uiState: SettingsUiState): String =
    if (uiState.quic.quicFakeProfileActive) {
        stringResource(R.string.udp_fake_profile_interaction_quic_override)
    } else {
        stringResource(R.string.udp_fake_profile_interaction)
    }

@Composable
private fun httpFakeEnabledBadge(enabled: Boolean): Pair<String, SummaryCapsuleTone> =
    (
        if (enabled) {
            stringResource(R.string.fake_payload_badge_http_on)
        } else {
            stringResource(R.string.fake_payload_badge_http_off)
        }
    ) to SummaryCapsuleTone.Neutral

@Composable
private fun httpsFakeEnabledBadge(enabled: Boolean): Pair<String, SummaryCapsuleTone> =
    (
        if (enabled) {
            stringResource(R.string.fake_payload_badge_https_on)
        } else {
            stringResource(R.string.fake_payload_badge_https_off)
        }
    ) to SummaryCapsuleTone.Neutral

@Composable
private fun udpFakeEnabledBadge(enabled: Boolean): Pair<String, SummaryCapsuleTone> =
    (
        if (enabled) {
            stringResource(R.string.fake_payload_badge_udp_on)
        } else {
            stringResource(R.string.fake_payload_badge_udp_off)
        }
    ) to SummaryCapsuleTone.Neutral

@Composable
private fun fakeStepBadge(
    active: Boolean,
    hasHostFake: Boolean,
): Pair<String, SummaryCapsuleTone> =
    (
        if (active) {
            stringResource(R.string.fake_payload_badge_fake_step_live)
        } else if (hasHostFake) {
            stringResource(R.string.fake_payload_badge_hostfake_separate)
        } else {
            stringResource(R.string.fake_payload_badge_fake_step_needed)
        }
    ) to
        when {
            active -> SummaryCapsuleTone.Active
            hasHostFake -> SummaryCapsuleTone.Info
            else -> SummaryCapsuleTone.Warning
        }
