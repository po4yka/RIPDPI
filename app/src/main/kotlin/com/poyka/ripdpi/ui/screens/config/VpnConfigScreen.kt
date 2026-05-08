package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigFieldRelayCredentials
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun VpnConfigScreen(
    uiState: ConfigUiState,
    onOpenRelaySettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("VpnConfigScreen")

    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SettingsCategoryHeader(title = stringResource(R.string.home_mode_remote_vpn))
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
}

@Composable
private fun VpnRelayRow(
    uiState: ConfigUiState,
    onOpenRelaySettings: () -> Unit,
) {
    VpnActionRow(
        testTag = RipDpiTestTags.ConfigVpnRelay,
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
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .ripDpiClickable(role = Role.Button, onClick = onClick)
                .ripDpiTestTag(testTag),
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

@Suppress("UnusedPrivateMember")
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
        )
    }
}
