package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayCredentials
import com.poyka.ripdpi.activities.ConfigFieldRelayLocalSocksPort
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.RelayPresetUiState
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val relayChipColumns = 2

@Composable
internal fun ModeEditorRelaySection(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.config_relay_section))
        RipDpiCard {
            ModeEditorRelayHeader(draft = draft, actions = actions)
            if (draft.relayEnabled) {
                ModeEditorRelayEnabledFields(draft = draft, uiState = uiState, actions = actions)
            }
        }
    }
}

@Composable
private fun ModeEditorRelayHeader(
    draft: ConfigDraft,
    actions: ModeEditorActions,
) {
    val colors = RipDpiThemeTokens.colors

    Text(
        text = stringResource(R.string.config_relay_title),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = colors.foreground,
    )
    Text(
        text = stringResource(R.string.config_relay_body),
        style = RipDpiThemeTokens.type.body,
        color = colors.mutedForeground,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.config_relay_enable),
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        RipDpiSwitch(
            checked = draft.relayEnabled,
            onCheckedChange = actions.onRelayEnabledChanged,
            accessibilityLabel = stringResource(R.string.config_relay_enable),
        )
    }
}

@Composable
private fun ModeEditorRelayEnabledFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayProfileId,
                onValueChange = actions.onRelayProfileIdChanged,
            ),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_profile_id),
                helperText = stringResource(R.string.config_relay_profile_id_helper),
            ),
    )
    uiState.relayPresetSuggestion?.let { suggestion ->
        WarningBanner(
            title = suggestion.title,
            message = suggestion.reason,
            tone = WarningBannerTone.Info,
        )
    }
    ModeEditorRelayPresetChips(uiState = uiState, draft = draft, actions = actions)
    ModeEditorRelayKindChips(draft = draft, actions = actions)
    if (uiState.validationErrors[ConfigFieldRelayCredentials] != null) {
        WarningBanner(
            title = stringResource(R.string.config_relay_credentials_title),
            message = stringResource(R.string.config_relay_credentials_body),
            tone = WarningBannerTone.Warning,
        )
    }
    RelayKindFields(
        draft = draft,
        uiState = uiState,
        actions = relayKindFieldActions(actions),
    )
    ModeEditorRelayUdpToggle(draft = draft, actions = actions)
    ModeEditorRelayLocalPortField(draft = draft, uiState = uiState, actions = actions)
}

@Composable
private fun ModeEditorRelayPresetChips(
    uiState: ConfigUiState,
    draft: ConfigDraft,
    actions: ModeEditorActions,
) {
    if (uiState.relayPresets.isEmpty()) {
        return
    }

    RelayChipSection(
        sectionKey = "recommended",
        title = stringResource(R.string.config_relay_protocol_section_recommended),
        chips = uiState.relayPresets.toRelayPresetChips(),
        selectedKind = draft.relayPresetId,
        onRelayKindChanged = actions.onRelayPresetSelected,
    )
}

@Composable
private fun ModeEditorRelayKindChips(
    draft: ConfigDraft,
    actions: ModeEditorActions,
) {
    relayProtocolSections().forEach { section ->
        RelayChipSection(
            sectionKey = section.key,
            title = stringResource(section.titleRes),
            chips = section.chips,
            selectedKind = draft.relayKind,
            onRelayKindChanged = actions.onRelayKindChanged,
        )
    }
}

@Composable
private fun RelayChipSection(
    sectionKey: String,
    title: String,
    chips: List<RelayChipOption>,
    selectedKind: String,
    onRelayKindChanged: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val chipHeight = RipDpiThemeTokens.components.inputs.chipHeight

    Column(
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.modeEditorRelaySection(sectionKey)),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
        )
        chips.chunked(relayChipColumns).forEach { rowChips ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                rowChips.forEach { chip ->
                    RelayChipOption(
                        chip = chip,
                        selectedKind = selectedKind,
                        onRelayKindChanged = onRelayKindChanged,
                    )
                }
                repeat(relayChipColumns - rowChips.size) {
                    Spacer(modifier = Modifier.weight(1f).height(chipHeight))
                }
            }
        }
    }
}

@Composable
private fun RowScope.RelayChipOption(
    chip: RelayChipOption,
    selectedKind: String,
    onRelayKindChanged: (String) -> Unit,
) {
    val chipModifier =
        Modifier
            .height(RipDpiThemeTokens.components.inputs.chipHeight)
            .ripDpiTestTag(RipDpiTestTags.modeEditorRelayChip(chip.kind))
    if (chip.labelRes != null) {
        RelayKindChip(
            selectedKind = selectedKind,
            kind = chip.kind,
            labelRes = chip.labelRes,
            onRelayKindChanged = onRelayKindChanged,
            modifier = chipModifier,
        )
    } else {
        RelayKindChip(
            selectedKind = selectedKind,
            kind = chip.kind,
            label = chip.label.orEmpty(),
            onRelayKindChanged = onRelayKindChanged,
            modifier = chipModifier,
        )
    }
}

private data class RelayChipSectionModel(
    val key: String,
    val titleRes: Int,
    val chips: List<RelayChipOption>,
)

private data class RelayChipOption(
    val kind: String,
    val labelRes: Int? = null,
    val label: String? = null,
)

private fun List<RelayPresetUiState>.toRelayPresetChips(): List<RelayChipOption> =
    map { preset -> RelayChipOption(kind = preset.id, label = preset.title) }

private fun relayProtocolSections(): List<RelayChipSectionModel> =
    listOf(
        RelayChipSectionModel(
            key = "tls-transports",
            titleRes = R.string.config_relay_protocol_section_tls,
            chips =
                listOf(
                    RelayChipOption(RelayKindVlessReality, labelRes = R.string.config_relay_kind_vless),
                    RelayChipOption(RelayKindCloudflareTunnel, labelRes = R.string.config_relay_kind_cloudflare_tunnel),
                    RelayChipOption(RelayKindNaiveProxy, labelRes = R.string.relay_kind_naiveproxy_label),
                    RelayChipOption(RelayKindShadowTlsV3, labelRes = R.string.relay_kind_shadowtls_v3_label),
                ),
        ),
        RelayChipSectionModel(
            key = "quic-transports",
            titleRes = R.string.config_relay_protocol_section_quic,
            chips =
                listOf(
                    RelayChipOption(RelayKindHysteria2, labelRes = R.string.config_relay_kind_hysteria2),
                    RelayChipOption(RelayKindMasque, labelRes = R.string.config_relay_kind_masque),
                    RelayChipOption(RelayKindTuicV5, labelRes = R.string.relay_kind_tuic_v5_label),
                    RelayChipOption(RelayKindChainRelay, labelRes = R.string.config_relay_kind_chain),
                ),
        ),
        RelayChipSectionModel(
            key = "pt-relays",
            titleRes = R.string.config_relay_protocol_section_pt,
            chips =
                listOf(
                    RelayChipOption(RelayKindSnowflake, labelRes = R.string.relay_kind_snowflake_label),
                    RelayChipOption(RelayKindWebTunnel, labelRes = R.string.relay_kind_webtunnel_label),
                    RelayChipOption(RelayKindObfs4, labelRes = R.string.relay_kind_obfs4_label),
                ),
        ),
        RelayChipSectionModel(
            key = "tor",
            titleRes = R.string.config_relay_protocol_section_tor,
            chips = listOf(RelayChipOption(RelayKindTor, labelRes = R.string.config_relay_protocol_section_tor)),
        ),
    )

@Composable
private fun ModeEditorRelayUdpToggle(
    draft: ConfigDraft,
    actions: ModeEditorActions,
) {
    if (
        draft.relayKind == RelayKindHysteria2 ||
        draft.relayKind == RelayKindMasque ||
        draft.relayKind == RelayKindTuicV5
    ) {
        RipDpiSwitch(
            checked = draft.relayUdpEnabled,
            onCheckedChange = actions.onRelayUdpEnabledChanged,
            label = stringResource(R.string.config_relay_udp),
        )
    }
}

@Composable
private fun ModeEditorRelayLocalPortField(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    RipDpiTextField(
        state =
            rememberRipDpiTextFieldState(
                value = draft.relayLocalSocksPort,
                onValueChange = actions.onRelayLocalSocksPortChanged,
            ),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.config_relay_local_port),
                helperText = stringResource(R.string.config_relay_local_port_helper),
                errorText =
                    validationMessage(
                        uiState.validationErrors[ConfigFieldRelayLocalSocksPort],
                    ),
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            ),
    )
}

private fun relayKindFieldActions(actions: ModeEditorActions): RelayKindFieldActions =
    RelayKindFieldActions(
        endpoint =
            RelayEndpointActions(
                onRelayServerChanged = actions.onRelayServerChanged,
                onRelayServerPortChanged = actions.onRelayServerPortChanged,
                onRelayServerNameChanged = actions.onRelayServerNameChanged,
            ),
        vless =
            RelayVlessActions(
                onRelayRealityPublicKeyChanged = actions.onRelayRealityPublicKeyChanged,
                onRelayRealityShortIdChanged = actions.onRelayRealityShortIdChanged,
                onRelayVlessTransportChanged = actions.onRelayVlessTransportChanged,
                onRelayXhttpPathChanged = actions.onRelayXhttpPathChanged,
                onRelayXhttpHostChanged = actions.onRelayXhttpHostChanged,
                onRelayXhttpModeChanged = actions.onRelayXhttpModeChanged,
                onRelayCloudflareTunnelModeChanged = actions.onRelayCloudflareTunnelModeChanged,
                onRelayCloudflarePublishLocalOriginUrlChanged = actions.onRelayCloudflarePublishLocalOriginUrlChanged,
                onRelayCloudflareCredentialsRefChanged = actions.onRelayCloudflareCredentialsRefChanged,
                onRelayCloudflareTunnelTokenChanged = actions.onRelayCloudflareTunnelTokenChanged,
                onRelayCloudflareTunnelCredentialsJsonChanged = actions.onRelayCloudflareTunnelCredentialsJsonChanged,
                onRelayVlessUuidChanged = actions.onRelayVlessUuidChanged,
            ),
        hysteria =
            RelayHysteriaActions(
                onRelayHysteriaPasswordChanged = actions.onRelayHysteriaPasswordChanged,
                onRelayHysteriaSalamanderKeyChanged = actions.onRelayHysteriaSalamanderKeyChanged,
            ),
        chain =
            RelayChainActions(
                onRelayChainHopProfileIdChanged = actions.onRelayChainHopProfileIdChanged,
                onRelayChainHopAdded = actions.onRelayChainHopAdded,
                onRelayChainHopRemoved = actions.onRelayChainHopRemoved,
                onRelayChainHopMoved = actions.onRelayChainHopMoved,
            ),
        masque =
            RelayMasqueActions(
                onRelayMasqueUrlChanged = actions.onRelayMasqueUrlChanged,
                onRelayMasqueAuthModeChanged = actions.onRelayMasqueAuthModeChanged,
                onRelayMasqueAuthTokenChanged = actions.onRelayMasqueAuthTokenChanged,
                onRelayMasqueClientCertificateChainPemChanged = actions.onRelayMasqueClientCertificateChainPemChanged,
                onRelayMasqueClientPrivateKeyPemChanged = actions.onRelayMasqueClientPrivateKeyPemChanged,
                onRelayMasqueUseHttp2FallbackChanged = actions.onRelayMasqueUseHttp2FallbackChanged,
                onRelayMasqueCloudflareGeohashEnabledChanged = actions.onRelayMasqueCloudflareGeohashEnabledChanged,
                onRelayMasqueImportCertificateChainClicked = actions.onRelayMasqueImportCertificateChainClicked,
                onRelayMasqueImportPrivateKeyClicked = actions.onRelayMasqueImportPrivateKeyClicked,
                onRelayMasqueImportPkcs12Clicked = actions.onRelayMasqueImportPkcs12Clicked,
            ),
        tuic =
            RelayTuicActions(
                onRelayTuicUuidChanged = actions.onRelayTuicUuidChanged,
                onRelayTuicPasswordChanged = actions.onRelayTuicPasswordChanged,
                onRelayTuicZeroRttChanged = actions.onRelayTuicZeroRttChanged,
                onRelayTuicCongestionControlChanged = actions.onRelayTuicCongestionControlChanged,
            ),
        misc =
            RelayMiscKindActions(
                onRelayShadowTlsPasswordChanged = actions.onRelayShadowTlsPasswordChanged,
                onRelayShadowTlsInnerProfileIdChanged = actions.onRelayShadowTlsInnerProfileIdChanged,
                onRelayNaiveUsernameChanged = actions.onRelayNaiveUsernameChanged,
                onRelayNaivePasswordChanged = actions.onRelayNaivePasswordChanged,
                onRelayNaivePathChanged = actions.onRelayNaivePathChanged,
                onRelayPtBridgeLineChanged = actions.onRelayPtBridgeLineChanged,
                onRelayWebTunnelUrlChanged = actions.onRelayWebTunnelUrlChanged,
                onRelaySnowflakeBrokerUrlChanged = actions.onRelaySnowflakeBrokerUrlChanged,
                onRelaySnowflakeFrontDomainChanged = actions.onRelaySnowflakeFrontDomainChanged,
            ),
        finalmask =
            RelayFinalmaskActions(
                onRelayFinalmaskTypeChanged = actions.onRelayFinalmaskTypeChanged,
                onRelayFinalmaskHeaderHexChanged = actions.onRelayFinalmaskHeaderHexChanged,
                onRelayFinalmaskTrailerHexChanged = actions.onRelayFinalmaskTrailerHexChanged,
                onRelayFinalmaskRandRangeChanged = actions.onRelayFinalmaskRandRangeChanged,
                onRelayFinalmaskSudokuSeedChanged = actions.onRelayFinalmaskSudokuSeedChanged,
                onRelayFinalmaskFragmentPacketsChanged = actions.onRelayFinalmaskFragmentPacketsChanged,
                onRelayFinalmaskFragmentMinBytesChanged = actions.onRelayFinalmaskFragmentMinBytesChanged,
                onRelayFinalmaskFragmentMaxBytesChanged = actions.onRelayFinalmaskFragmentMaxBytesChanged,
            ),
    )
