package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun LocalBypassConfigScreen(
    uiState: ConfigUiState,
    desyncSummary: String,
    onOpenDesyncSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("LocalBypassConfigScreen")

    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SettingsCategoryHeader(title = stringResource(R.string.home_mode_local_dpi_bypass))
        RipDpiCard {
            Text(
                text = stringResource(R.string.config_local_bypass_section_body),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            LocalBypassModeRow(uiState = uiState)
            LocalBypassListenAddressRow(uiState = uiState)
            LocalBypassDnsRow(uiState = uiState, onOpenDnsSettings = onOpenDnsSettings)
            LocalBypassDesyncRow(
                uiState = uiState,
                desyncSummary = desyncSummary,
                onOpenDesyncSettings = onOpenDesyncSettings,
            )
        }
    }
}

@Composable
private fun LocalBypassModeRow(uiState: ConfigUiState) {
    SettingsRow(
        title = stringResource(R.string.mode_setting),
        subtitle = stringResource(R.string.config_mode_summary),
        value = stringResource(modeLabelRes(uiState.draft.mode)),
        leadingIcon = RipDpiIcons.NetworkCheck,
        showDivider = true,
        testTag = RipDpiTestTags.ConfigLocalBypassMode,
    )
}

@Composable
private fun LocalBypassListenAddressRow(uiState: ConfigUiState) {
    SettingsRow(
        title = stringResource(R.string.bye_dpi_proxy_ip_setting),
        subtitle = stringResource(R.string.config_listen_address_summary),
        value =
            stringResource(
                R.string.proxy_address,
                uiState.draft.proxyIp,
                uiState.draft.proxyPort,
            ),
        leadingIcon = RipDpiIcons.Public,
        monospaceValue = true,
        showDivider = true,
        testTag = RipDpiTestTags.ConfigLocalBypassListenAddress,
    )
}

@Composable
private fun LocalBypassDnsRow(
    uiState: ConfigUiState,
    onOpenDnsSettings: () -> Unit,
) {
    LocalBypassActionRow(
        testTag = RipDpiTestTags.ConfigDnsSettings,
        accessibilityLabel = stringResource(R.string.title_dns_settings),
        onClick = onOpenDnsSettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.title_dns_settings),
            subtitle =
                stringResource(
                    if (uiState.draft.mode == Mode.VPN) {
                        R.string.config_dns_summary_enabled
                    } else {
                        R.string.config_dns_summary_disabled
                    },
                ),
            value = uiState.draft.dnsSummary,
            leadingIcon = RipDpiIcons.Dns,
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun LocalBypassDesyncRow(
    uiState: ConfigUiState,
    desyncSummary: String,
    onOpenDesyncSettings: () -> Unit,
) {
    LocalBypassActionRow(
        testTag = RipDpiTestTags.ConfigLocalBypassDesync,
        accessibilityLabel = stringResource(R.string.ripdpi_desync_method_setting),
        onClick = onOpenDesyncSettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.ripdpi_desync_method_setting),
            subtitle = stringResource(R.string.config_desync_method_summary),
            value = desyncSummary,
            leadingIcon = RipDpiIcons.Shield,
            showChevron = true,
            showDivider = uiState.draft.defaultTtl.isNotBlank(),
        )
    }
    if (uiState.draft.defaultTtl.isNotBlank()) {
        SettingsRow(
            title = stringResource(R.string.ripdpi_default_ttl_setting),
            subtitle = stringResource(R.string.config_default_ttl_summary),
            value = uiState.draft.defaultTtl,
            leadingIcon = RipDpiIcons.Timer,
            monospaceValue = true,
        )
    }
}

@Composable
private fun LocalBypassActionRow(
    testTag: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .ripDpiClickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    this.testTag = testTag
                    contentDescription = accessibilityLabel
                    role = Role.Button
                    onClick(action = {
                        onClick()
                        true
                    })
                },
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
private fun LocalBypassConfigScreenPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.Proxy,
            proxyIp = "127.0.0.1",
            proxyPort = "1080",
            defaultTtl = "8",
        )
    RipDpiTheme {
        LocalBypassConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    draft = draft,
                ),
            desyncSummary = draft.chainSummary,
            onOpenDesyncSettings = {},
            onOpenDnsSettings = {},
        )
    }
}
