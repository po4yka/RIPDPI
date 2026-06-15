package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.poyka.ripdpi.activities.ConfigFieldRelayCredentials
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.AdvancedSection
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val VpnProfilePreviewLimit = 3

@Composable
internal fun VpnConfigScreen(
    uiState: ConfigUiState,
    onModeSelected: (Mode) -> Unit,
    onOpenRelaySettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onPasteServerLink: () -> Unit,
    onScanServer: () -> Unit,
    onProfileShare: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("VpnConfigScreen")

    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SettingsCategoryHeader(title = stringResource(R.string.home_mode_remote_vpn))
        VpnSimpleCard(
            uiState = uiState,
            onModeSelected = onModeSelected,
            onPasteServerLink = onPasteServerLink,
            onScanServer = onScanServer,
            onProfileShare = onProfileShare,
        )
        AdvancedSection(initiallyExpanded = uiState.uiPersona == "advanced") {
            VpnAdvancedRows(
                uiState = uiState,
                onOpenRelaySettings = onOpenRelaySettings,
                onOpenDnsSettings = onOpenDnsSettings,
            )
        }
    }
}

@Composable
private fun VpnSimpleCard(
    uiState: ConfigUiState,
    onModeSelected: (Mode) -> Unit,
    onPasteServerLink: () -> Unit,
    onScanServer: () -> Unit,
    onProfileShare: (String) -> Unit,
) {
    val vpnEnabled = uiState.activeMode == Mode.VPN

    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigVpnSimple)) {
        Text(
            text = stringResource(R.string.config_vpn_simple_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.config_vpn_simple_body),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        SettingsRow(
            title = stringResource(R.string.config_vpn_toggle_title),
            subtitle = stringResource(R.string.config_vpn_toggle_body),
            value =
                stringResource(
                    if (vpnEnabled) {
                        R.string.config_local_bypass_enabled
                    } else {
                        R.string.config_local_bypass_disabled
                    },
                ),
            leadingIcon = RipDpiIcons.Vpn,
            showDivider = true,
            testTag = RipDpiTestTags.ConfigVpnToggleState,
        )
        VpnSimpleActions(
            vpnEnabled = vpnEnabled,
            onModeSelected = onModeSelected,
            onPasteServerLink = onPasteServerLink,
            onScanServer = onScanServer,
        )
        VpnProfileList(uiState = uiState, onProfileShare = onProfileShare)
    }
}

@Composable
private fun VpnProfileList(
    uiState: ConfigUiState,
    onProfileShare: (String) -> Unit,
) {
    if (uiState.vpnProfiles.isEmpty()) {
        SettingsRow(
            title = stringResource(R.string.config_vpn_profiles_title),
            subtitle = stringResource(R.string.config_vpn_profiles_empty_body),
            value = stringResource(R.string.config_vpn_profiles_empty_value),
            leadingIcon = RipDpiIcons.Public,
            showDivider = true,
            testTag = RipDpiTestTags.ConfigVpnProfileList,
        )
        return
    }

    uiState.vpnProfiles.take(VpnProfilePreviewLimit).forEachIndexed { index, profile ->
        SettingsRow(
            title = profile.selectorLabel,
            subtitle = profile.trustLabel,
            value = profile.kindLabel,
            leadingIcon = RipDpiIcons.Public,
            onClick = { onProfileShare(profile.id) },
            showChevron = true,
            showDivider = true,
            testTag = RipDpiTestTags.configVpnProfileShare(profile.id),
        )
        if (index == VpnProfilePreviewLimit - 1 && uiState.vpnProfiles.size > VpnProfilePreviewLimit) {
            Text(
                text =
                    stringResource(
                        R.string.config_vpn_profiles_more,
                        uiState.vpnProfiles.size - VpnProfilePreviewLimit,
                    ),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun VpnSimpleActions(
    vpnEnabled: Boolean,
    onModeSelected: (Mode) -> Unit,
    onPasteServerLink: () -> Unit,
    onScanServer: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        RipDpiButton(
            text =
                stringResource(
                    if (vpnEnabled) {
                        R.string.config_vpn_turn_off
                    } else {
                        R.string.config_vpn_turn_on
                    },
                ),
            onClick = { onModeSelected(if (vpnEnabled) Mode.Proxy else Mode.VPN) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.ConfigVpnToggle),
            variant = RipDpiButtonVariant.Primary,
            leadingIcon = RipDpiIcons.Vpn,
        )
        Text(
            text = stringResource(R.string.config_vpn_add_profile_title),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.config_vpn_add_server_paste),
                onClick = onPasteServerLink,
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.ConfigVpnAddServerPaste),
                variant = RipDpiButtonVariant.Outline,
                leadingIcon = RipDpiIcons.Copy,
            )
            RipDpiButton(
                text = stringResource(R.string.config_vpn_add_server_scan),
                onClick = onScanServer,
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.ConfigVpnAddServerScan),
                variant = RipDpiButtonVariant.Outline,
                leadingIcon = RipDpiIcons.QrCodeScanner,
            )
        }
    }
}

@Composable
private fun VpnAdvancedRows(
    uiState: ConfigUiState,
    onOpenRelaySettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
) {
    RipDpiCard {
        Text(
            text = stringResource(R.string.config_vpn_section_body),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        VpnRelayRow(uiState = uiState, onOpenRelaySettings = onOpenRelaySettings)
        VpnProtocolRow(uiState = uiState, onOpenRelaySettings = onOpenRelaySettings)
        VpnCredentialsRow(uiState = uiState, onOpenRelaySettings = onOpenRelaySettings)
        VpnDnsRow(uiState = uiState, onOpenDnsSettings = onOpenDnsSettings)
    }
}

@Composable
private fun VpnRelayRow(
    uiState: ConfigUiState,
    onOpenRelaySettings: () -> Unit,
) {
    VpnActionRow(
        testTag = RipDpiTestTags.ConfigVpnRelay,
        accessibilityLabel = stringResource(R.string.config_relay_server),
        onClick = onOpenRelaySettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.config_relay_server),
            subtitle = stringResource(R.string.config_vpn_relay_server_summary),
            value = relayEndpointLabel(uiState),
            leadingIcon = RipDpiIcons.Public,
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun VpnProtocolRow(
    uiState: ConfigUiState,
    onOpenRelaySettings: () -> Unit,
) {
    VpnActionRow(
        testTag = RipDpiTestTags.ConfigVpnProtocol,
        accessibilityLabel = stringResource(R.string.config_vpn_protocol_title),
        onClick = onOpenRelaySettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.config_vpn_protocol_title),
            subtitle = stringResource(R.string.config_vpn_protocol_summary),
            value = uiState.draft.relaySummary,
            leadingIcon = RipDpiIcons.Vpn,
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun VpnCredentialsRow(
    uiState: ConfigUiState,
    onOpenRelaySettings: () -> Unit,
) {
    VpnActionRow(
        testTag = RipDpiTestTags.ConfigVpnCredentials,
        accessibilityLabel = stringResource(R.string.config_vpn_credentials_title),
        onClick = onOpenRelaySettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.config_vpn_credentials_title),
            subtitle = credentialsSubtitle(uiState),
            value = credentialsValue(uiState),
            leadingIcon = RipDpiIcons.Lock,
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun VpnDnsRow(
    uiState: ConfigUiState,
    onOpenDnsSettings: () -> Unit,
) {
    VpnActionRow(
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
        )
    }
}

@Composable
private fun VpnActionRow(
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

@Composable
private fun relayEndpointLabel(uiState: ConfigUiState): String {
    val draft = uiState.draft
    if (!draft.relayEnabled || draft.relayKind == RelayKindOff) {
        return stringResource(R.string.home_mode_card_remote_relay_disabled)
    }
    val chainEndpoint =
        listOf(draft.relayChainEntryServer, draft.relayChainExitServer)
            .filter { it.isNotBlank() }
            .joinToString(" -> ")
    val endpoint =
        draft.relayServer
            .ifBlank { draft.relayServerName }
            .ifBlank { draft.relayMasqueUrl }
            .ifBlank { draft.relayWebTunnelUrl }
            .ifBlank { chainEndpoint }
    return when {
        endpoint.isBlank() -> {
            stringResource(R.string.home_mode_card_remote_server_unknown)
        }

        draft.relayServer.isNotBlank() && draft.relayServerPort.isNotBlank() -> {
            stringResource(R.string.proxy_address, draft.relayServer, draft.relayServerPort)
        }

        else -> {
            endpoint
        }
    }
}

@Composable
private fun credentialsSubtitle(uiState: ConfigUiState): String =
    stringResource(
        if (uiState.validationErrors[ConfigFieldRelayCredentials] != null) {
            R.string.config_relay_credentials_body
        } else {
            R.string.config_vpn_credentials_summary
        },
    )

@Composable
private fun credentialsValue(uiState: ConfigUiState): String =
    when {
        !uiState.draft.relayEnabled || uiState.draft.relayKind == RelayKindOff -> {
            stringResource(R.string.home_mode_card_remote_relay_disabled)
        }

        uiState.validationErrors[ConfigFieldRelayCredentials] != null -> {
            stringResource(R.string.config_vpn_credentials_required)
        }

        else -> {
            uiState.draft.relayProfileId
        }
    }

@Preview(showBackground = true)
@Composable
private fun VpnConfigScreenPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.VPN,
            relayEnabled = true,
            relayServer = "vpn.example",
            relayServerPort = "443",
        )
    RipDpiTheme {
        VpnConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    draft = draft,
                ),
            onOpenRelaySettings = {},
            onOpenDnsSettings = {},
            onModeSelected = {},
            onPasteServerLink = {},
            onScanServer = {},
        )
    }
}
