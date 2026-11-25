package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.EndpointSettingsRow
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.screens.dns.localizedDnsSummary
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val VpnProfilePreviewLimit = 3

@Composable
internal fun ConnectionRelaySection(
    uiState: ConnectionUiState,
    onOpenRelaySettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseRelay: () -> Unit = {},
    onOpenSubscriptionStatus: (() -> Unit)? = null,
    onOpenSubscriptionFailover: (() -> Unit)? = null,
    onOpenProfileUtility: (() -> Unit)? = null,
) {
    TrackRecomposition("ConnectionRelaySection")

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        RelaySectionRows(
            uiState,
            onOpenRelaySettings,
            onOpenDnsSettings,
            onChooseRelay = onChooseRelay,
            onOpenSubscriptionStatus = onOpenSubscriptionStatus,
            onOpenSubscriptionFailover = onOpenSubscriptionFailover,
            onOpenProfileUtility = onOpenProfileUtility,
        )
    }
}

@Composable
internal fun ConnectionRelayProfileChoices(
    uiState: ConnectionUiState,
    actions: ConnectionProfileActions,
    onPasteServerLink: () -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = stringResource(R.string.config_vpn_profiles_title))
        ServerActionMessage(uiState.serverMessage, actions.dismissMessage)
        ServerAddActions(
            onPasteLink = onPasteServerLink,
            onScanQr = actions.scanServer,
            onOpenRoute = actions.create,
            onCreateRelay = actions.createRelay,
        )
        VpnProfileList(uiState, actions)
        uiState.awgServers.forEach { server -> AwgServerRow(server, actions) }
    }
}

@Composable
private fun VpnProfileList(
    uiState: ConnectionUiState,
    actions: ConnectionProfileActions,
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val search =
        com.poyka.ripdpi.ui.components.profiles
            .rememberProfileSearchState()
    if (uiState.vpnProfiles.isEmpty()) return
    if (showSearch) {
        VpnProfileSearchSheet(
            profiles = uiState.vpnProfiles,
            selectedProfileId = uiState.activeRelayProfileId.takeIf { uiState.activeRelayEnabled },
            search = search,
            onDismiss = { showSearch = false },
            actions = actions,
        )
    }
    uiState.vpnProfiles.take(VpnProfilePreviewLimit).forEach { profile ->
        RelayServerRow(
            profile = profile,
            selected = profile.id == uiState.activeRelayProfileId && uiState.activeRelayEnabled,
            actions = actions,
        )
    }
    RipDpiButton(
        text = stringResource(R.string.profile_search_open),
        onClick = { showSearch = true },
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.ConnectionRelayProfilesMore),
        variant = RipDpiButtonVariant.Outline,
        leadingIcon = RipDpiIcons.Search,
        wrapLabel = true,
    )
}

@Composable
private fun RelaySectionRows(
    uiState: ConnectionUiState,
    onOpenRelaySettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onChooseRelay: () -> Unit,
    onOpenSubscriptionStatus: (() -> Unit)? = null,
    onOpenSubscriptionFailover: (() -> Unit)? = null,
    onOpenProfileUtility: (() -> Unit)? = null,
) {
    RipDpiCard {
        if (uiState.activeExit != null) {
            SettingsRow(
                title = uiState.activeExit.name,
                subtitle = uiState.activeExit.kind,
                onClick = onChooseRelay,
                showChevron = true,
                showDivider = true,
                testTag = RipDpiTestTags.ConnectionRelayRelay,
            )
        } else if (uiState.activeExternalProvider != null) {
            SettingsRow(
                title = uiState.activeExternalProvider.displayName,
                onClick = onChooseRelay,
                showChevron = true,
                showDivider = true,
                testTag = RipDpiTestTags.ConnectionRelayRelay,
            )
        } else if (uiState.draft.relayEnabled && uiState.draft.relayKind != RelayKindOff) {
            VpnRelayRow(uiState = uiState, onOpenRelaySettings = onOpenRelaySettings)
            VpnProtocolRow(uiState = uiState, onOpenRelaySettings = onOpenRelaySettings)
            VpnCredentialsRow(uiState = uiState, onOpenRelaySettings = onOpenRelaySettings)
        } else {
            // One setup row instead of three rows that all say "Relay disabled".
            VpnRelayOffRow(onChooseRelay = onChooseRelay)
        }
        VpnDnsRow(uiState = uiState, onOpenDnsSettings = onOpenDnsSettings)
        onOpenSubscriptionStatus?.let {
            VpnSubscriptionRow(
                testTag = RipDpiTestTags.ConnectionSubscriptionStatus,
                title = stringResource(R.string.subscription_status_title),
                onClick = it,
            )
        }
        onOpenProfileUtility?.let {
            VpnSubscriptionRow(
                testTag = RipDpiTestTags.SettingsProfileUtility,
                title = stringResource(R.string.profile_utility_title),
                onClick = it,
            )
        }
        onOpenSubscriptionFailover?.let {
            VpnSubscriptionRow(
                testTag = RipDpiTestTags.ConnectionSubscriptionFailover,
                title = stringResource(R.string.title_subscription_failover),
                onClick = it,
            )
        }
    }
}

@Composable
private fun VpnSubscriptionRow(
    testTag: String,
    title: String,
    onClick: () -> Unit,
) {
    VpnActionRow(
        testTag = testTag,
        accessibilityLabel = title,
        accessibilityValue = "",
        onClick = onClick,
    ) {
        SettingsRow(title = title, showChevron = true)
    }
}

@Composable
private fun VpnRelayOffRow(onChooseRelay: () -> Unit) {
    val title = stringResource(R.string.config_relay_off_title)
    val body = stringResource(R.string.config_relay_off_body)
    VpnActionRow(
        testTag = RipDpiTestTags.ConnectionRelayRelay,
        accessibilityLabel = title,
        accessibilityValue = "",
        onClick = onChooseRelay,
        accessibilitySubtitle = body,
    ) {
        SettingsRow(
            title = title,
            subtitle = body,
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun VpnRelayRow(
    uiState: ConnectionUiState,
    onOpenRelaySettings: () -> Unit,
) {
    VpnActionRow(
        testTag = RipDpiTestTags.ConnectionRelayRelay,
        accessibilityLabel = stringResource(R.string.config_relay_server),
        accessibilityValue = relayEndpointLabel(uiState),
        onClick = onOpenRelaySettings,
        accessibilitySubtitle = stringResource(R.string.config_vpn_relay_server_summary),
    ) {
        EndpointSettingsRow(
            title = stringResource(R.string.config_relay_server),
            subtitle = stringResource(R.string.config_vpn_relay_server_summary),
            value = relayEndpointLabel(uiState),
            showChevron = true,
        )
    }
}

@Composable
private fun VpnProtocolRow(
    uiState: ConnectionUiState,
    onOpenRelaySettings: () -> Unit,
) {
    VpnActionRow(
        testTag = RipDpiTestTags.ConnectionRelayProtocol,
        accessibilityLabel = stringResource(R.string.config_vpn_protocol_title),
        accessibilityValue = uiState.draft.relaySummary,
        onClick = onOpenRelaySettings,
        accessibilitySubtitle = stringResource(R.string.config_vpn_protocol_summary),
    ) {
        SettingsRow(
            title = stringResource(R.string.config_vpn_protocol_title),
            subtitle = stringResource(R.string.config_vpn_protocol_summary),
            value = uiState.draft.relaySummary,
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun VpnCredentialsRow(
    uiState: ConnectionUiState,
    onOpenRelaySettings: () -> Unit,
) {
    VpnActionRow(
        testTag = RipDpiTestTags.ConnectionRelayCredentials,
        accessibilityLabel = stringResource(R.string.config_vpn_credentials_title),
        accessibilityValue = credentialsValue(uiState),
        onClick = onOpenRelaySettings,
        accessibilitySubtitle = credentialsSubtitle(uiState),
    ) {
        SettingsRow(
            title = stringResource(R.string.config_vpn_credentials_title),
            subtitle = credentialsSubtitle(uiState),
            value = credentialsValue(uiState),
            showChevron = true,
            showDivider = true,
        )
    }
}

@Composable
private fun VpnDnsRow(
    uiState: ConnectionUiState,
    onOpenDnsSettings: () -> Unit,
) {
    val dnsSummary = uiState.draft.localizedDnsSummary()
    VpnActionRow(
        testTag = RipDpiTestTags.ConnectionDnsSettings,
        accessibilityLabel = stringResource(R.string.title_dns_settings),
        accessibilityValue = dnsSummary,
        onClick = onOpenDnsSettings,
    ) {
        SettingsRow(
            title = stringResource(R.string.title_dns_settings),
            value = dnsSummary,
            showChevron = true,
        )
    }
}

@Composable
private fun VpnActionRow(
    testTag: String,
    accessibilityLabel: String,
    accessibilityValue: String,
    onClick: () -> Unit,
    accessibilitySubtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .ripDpiClickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    this.testTag = testTag
                    // The cleared subtree hides the row subtitle, so it joins the label here.
                    contentDescription = listOfNotNull(accessibilityLabel, accessibilitySubtitle).joinToString(", ")
                    stateDescription = accessibilityValue
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
private fun relayEndpointLabel(uiState: ConnectionUiState): String {
    val draft = uiState.draft
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
            stringResource(R.string.status_mode_card_remote_server_unknown)
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
private fun credentialsSubtitle(uiState: ConnectionUiState): String =
    stringResource(
        if (uiState.validationErrors[ConnectionFieldRelayCredentials] != null) {
            R.string.config_relay_credentials_body
        } else {
            R.string.config_vpn_credentials_summary
        },
    )

@Composable
private fun credentialsValue(uiState: ConnectionUiState): String =
    when {
        uiState.validationErrors[ConnectionFieldRelayCredentials] != null -> {
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
        ConnectionRelaySection(
            uiState =
                ConnectionUiState(
                    activeMode = draft.mode,
                    runningMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    draft = draft,
                ),
            onOpenRelaySettings = {},
            onOpenDnsSettings = {},
        )
    }
}
