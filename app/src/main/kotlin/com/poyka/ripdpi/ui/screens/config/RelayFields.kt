package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayServer
import com.poyka.ripdpi.activities.ConfigFieldRelayServerPort
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayCongestionControlCubic
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun RelayKindFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    onRelayServerChanged: (String) -> Unit,
    onRelayServerPortChanged: (String) -> Unit,
    onRelayServerNameChanged: (String) -> Unit,
    onRelayRealityPublicKeyChanged: (String) -> Unit,
    onRelayRealityShortIdChanged: (String) -> Unit,
    onRelayVlessTransportChanged: (String) -> Unit,
    onRelayXhttpPathChanged: (String) -> Unit,
    onRelayXhttpHostChanged: (String) -> Unit,
    onRelayVlessUuidChanged: (String) -> Unit,
    onRelayHysteriaPasswordChanged: (String) -> Unit,
    onRelayHysteriaSalamanderKeyChanged: (String) -> Unit,
    onRelayChainEntryProfileIdChanged: (String) -> Unit,
    onRelayChainExitProfileIdChanged: (String) -> Unit,
    onRelayMasqueUrlChanged: (String) -> Unit,
    onRelayMasqueAuthModeChanged: (String) -> Unit,
    onRelayMasqueAuthTokenChanged: (String) -> Unit,
    onRelayMasqueClientCertificateChainPemChanged: (String) -> Unit,
    onRelayMasqueClientPrivateKeyPemChanged: (String) -> Unit,
    onRelayMasqueUseHttp2FallbackChanged: (Boolean) -> Unit,
    onRelayMasqueCloudflareGeohashEnabledChanged: (Boolean) -> Unit,
    onRelayMasqueImportCertificateChainClicked: () -> Unit,
    onRelayMasqueImportPrivateKeyClicked: () -> Unit,
    onRelayMasqueImportPkcs12Clicked: () -> Unit,
    onRelayTuicUuidChanged: (String) -> Unit,
    onRelayTuicPasswordChanged: (String) -> Unit,
    onRelayTuicZeroRttChanged: (Boolean) -> Unit,
    onRelayTuicCongestionControlChanged: (String) -> Unit,
    onRelayShadowTlsPasswordChanged: (String) -> Unit,
    onRelayShadowTlsInnerProfileIdChanged: (String) -> Unit,
    onRelayNaiveUsernameChanged: (String) -> Unit,
    onRelayNaivePasswordChanged: (String) -> Unit,
    onRelayNaivePathChanged: (String) -> Unit,
    onRelayPtBridgeLineChanged: (String) -> Unit,
    onRelayWebTunnelUrlChanged: (String) -> Unit,
    onRelaySnowflakeBrokerUrlChanged: (String) -> Unit,
    onRelaySnowflakeFrontDomainChanged: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    if (
        draft.relayKind == RelayKindVlessReality ||
        draft.relayKind == RelayKindCloudflareTunnel ||
        draft.relayKind == RelayKindHysteria2 ||
        draft.relayKind == RelayKindTuicV5 ||
        draft.relayKind == RelayKindNaiveProxy
    ) {
        RipDpiTextField(
            value = draft.relayServer,
            onValueChange = onRelayServerChanged,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.config_relay_server),
                    errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayServer]),
                ),
        )
        RipDpiTextField(
            value = draft.relayServerPort,
            onValueChange = onRelayServerPortChanged,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.config_relay_server_port),
                    errorText = validationMessage(uiState.validationErrors[ConfigFieldRelayServerPort]),
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                ),
        )
        RipDpiTextField(
            value = draft.relayServerName,
            onValueChange = onRelayServerNameChanged,
            decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_server_name)),
        )
    }
    when (draft.relayKind) {
        RelayKindVlessReality,
        RelayKindCloudflareTunnel,
        -> {
            if (draft.relayKind == RelayKindCloudflareTunnel) {
                Text(
                    text = stringResource(R.string.config_relay_cloudflare_tunnel_note),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
            }
            if (draft.relayKind == RelayKindVlessReality) {
                RipDpiTextField(
                    value = draft.relayRealityPublicKey,
                    onValueChange = onRelayRealityPublicKeyChanged,
                    decoration =
                        RipDpiTextFieldDecoration(
                            label = stringResource(R.string.config_relay_reality_public_key),
                        ),
                )
                RipDpiTextField(
                    value = draft.relayRealityShortId,
                    onValueChange = onRelayRealityShortIdChanged,
                    decoration =
                        RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_reality_short_id)),
                )
            }
            Text(
                text = stringResource(R.string.config_relay_vless_transport),
                style = RipDpiThemeTokens.type.caption,
                color = colors.mutedForeground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                VlessTransportChip(
                    selectedTransport = draft.relayVlessTransport,
                    transport = RelayVlessTransportRealityTcp,
                    labelRes = R.string.config_relay_vless_transport_reality_tcp,
                    onRelayVlessTransportChanged = onRelayVlessTransportChanged,
                )
                VlessTransportChip(
                    selectedTransport = draft.relayVlessTransport,
                    transport = RelayVlessTransportXhttp,
                    labelRes = R.string.config_relay_vless_transport_xhttp,
                    onRelayVlessTransportChanged = onRelayVlessTransportChanged,
                )
            }
            if (draft.relayKind == RelayKindCloudflareTunnel || draft.relayVlessTransport == RelayVlessTransportXhttp) {
                RipDpiTextField(
                    value = draft.relayXhttpPath,
                    onValueChange = onRelayXhttpPathChanged,
                    decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_xhttp_path)),
                )
                RipDpiTextField(
                    value = draft.relayXhttpHost,
                    onValueChange = onRelayXhttpHostChanged,
                    decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_xhttp_host)),
                )
            }
            RipDpiTextField(
                value = draft.relayVlessUuid,
                onValueChange = onRelayVlessUuidChanged,
                decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_vless_uuid)),
            )
        }

        RelayKindHysteria2 -> {
            RipDpiTextField(
                value = draft.relayHysteriaPassword,
                onValueChange = onRelayHysteriaPasswordChanged,
                decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_hysteria_password)),
            )
            RipDpiTextField(
                value = draft.relayHysteriaSalamanderKey,
                onValueChange = onRelayHysteriaSalamanderKeyChanged,
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.config_relay_hysteria_salamander),
                    ),
            )
        }

        RelayKindTuicV5 -> {
            RipDpiTextField(
                value = draft.relayTuicUuid,
                onValueChange = onRelayTuicUuidChanged,
                decoration = RipDpiTextFieldDecoration(label = "TUIC UUID"),
            )
            RipDpiTextField(
                value = draft.relayTuicPassword,
                onValueChange = onRelayTuicPasswordChanged,
                decoration = RipDpiTextFieldDecoration(label = "TUIC password"),
            )
            RipDpiSwitch(
                checked = draft.relayTuicZeroRtt,
                onCheckedChange = onRelayTuicZeroRttChanged,
                label = "Enable 0-RTT",
            )
            Text(
                text = "Congestion control",
                style = RipDpiThemeTokens.type.caption,
                color = colors.mutedForeground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                RelayKindChip(
                    selectedKind = draft.relayTuicCongestionControl,
                    kind = RelayCongestionControlBbr,
                    label = "BBR",
                    onRelayKindChanged = onRelayTuicCongestionControlChanged,
                )
                RelayKindChip(
                    selectedKind = draft.relayTuicCongestionControl,
                    kind = RelayCongestionControlCubic,
                    label = "CUBIC",
                    onRelayKindChanged = onRelayTuicCongestionControlChanged,
                )
            }
        }

        RelayKindChainRelay -> {
            RelayChainFields(
                draft = draft,
                onRelayChainEntryProfileIdChanged = onRelayChainEntryProfileIdChanged,
                onRelayChainExitProfileIdChanged = onRelayChainExitProfileIdChanged,
            )
        }

        RelayKindMasque -> {
            RipDpiTextField(
                value = draft.relayMasqueUrl,
                onValueChange = onRelayMasqueUrlChanged,
                decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_masque_url)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MasqueAuthModeChip(
                    draft.relayMasqueAuthMode,
                    RelayMasqueAuthModeBearer,
                    R.string.config_relay_masque_auth_bearer,
                    onRelayMasqueAuthModeChanged,
                )
                MasqueAuthModeChip(
                    draft.relayMasqueAuthMode,
                    RelayMasqueAuthModePreshared,
                    R.string.config_relay_masque_auth_preshared,
                    onRelayMasqueAuthModeChanged,
                )
                MasqueAuthModeChip(
                    draft.relayMasqueAuthMode,
                    RelayMasqueAuthModeCloudflareMtls,
                    R.string.config_relay_masque_auth_cloudflare_direct,
                    onRelayMasqueAuthModeChanged,
                )
                if (uiState.supportsMasquePrivacyPass) {
                    MasqueAuthModeChip(
                        draft.relayMasqueAuthMode,
                        RelayMasqueAuthModePrivacyPass,
                        R.string.config_relay_masque_auth_privacy_pass,
                        onRelayMasqueAuthModeChanged,
                    )
                }
            }
            if (!uiState.supportsMasquePrivacyPass) {
                Text(
                    text =
                        when (uiState.masquePrivacyPassBuildStatus) {
                            MasquePrivacyPassBuildStatus.Available -> {
                                stringResource(R.string.config_relay_masque_privacy_pass_available)
                            }

                            MasquePrivacyPassBuildStatus.MissingProviderUrl -> {
                                stringResource(R.string.config_relay_masque_privacy_pass_missing_provider)
                            }

                            MasquePrivacyPassBuildStatus.InvalidProviderUrl -> {
                                stringResource(R.string.config_relay_masque_privacy_pass_invalid_provider)
                            }
                        },
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
            } else {
                Text(
                    text = stringResource(R.string.config_relay_masque_privacy_pass_available),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
            }
            if (
                draft.relayMasqueAuthMode != RelayMasqueAuthModePrivacyPass &&
                draft.relayMasqueAuthMode != RelayMasqueAuthModeCloudflareMtls
            ) {
                RipDpiTextField(
                    value = draft.relayMasqueAuthToken,
                    onValueChange = onRelayMasqueAuthTokenChanged,
                    decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.config_relay_masque_token)),
                )
            }
            if (draft.relayMasqueAuthMode == RelayMasqueAuthModeCloudflareMtls) {
                RipDpiConfigTextField(
                    value = draft.relayMasqueClientCertificateChainPem,
                    onValueChange = onRelayMasqueClientCertificateChainPemChanged,
                    decoration =
                        RipDpiTextFieldDecoration(
                            label = stringResource(R.string.config_relay_masque_client_certificate_chain),
                            helperText = stringResource(R.string.config_relay_masque_client_certificate_chain_helper),
                        ),
                    multiline = true,
                )
                RipDpiConfigTextField(
                    value = draft.relayMasqueClientPrivateKeyPem,
                    onValueChange = onRelayMasqueClientPrivateKeyPemChanged,
                    decoration =
                        RipDpiTextFieldDecoration(
                            label = stringResource(R.string.config_relay_masque_client_private_key),
                            helperText = stringResource(R.string.config_relay_masque_client_private_key_helper),
                        ),
                    multiline = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    RipDpiButton(
                        text = stringResource(R.string.config_relay_masque_import_certificate_chain),
                        onClick = onRelayMasqueImportCertificateChainClicked,
                        modifier = Modifier.weight(1f),
                        variant = RipDpiButtonVariant.Outline,
                    )
                    RipDpiButton(
                        text = stringResource(R.string.config_relay_masque_import_private_key),
                        onClick = onRelayMasqueImportPrivateKeyClicked,
                        modifier = Modifier.weight(1f),
                        variant = RipDpiButtonVariant.Outline,
                    )
                }
                RipDpiButton(
                    text = stringResource(R.string.config_relay_masque_import_pkcs12),
                    onClick = onRelayMasqueImportPkcs12Clicked,
                    modifier = Modifier.fillMaxWidth(),
                    variant = RipDpiButtonVariant.Outline,
                )
                RipDpiSwitch(
                    checked = draft.relayMasqueCloudflareGeohashEnabled,
                    onCheckedChange = onRelayMasqueCloudflareGeohashEnabledChanged,
                    label = stringResource(R.string.config_relay_masque_cloudflare_geohash_enabled),
                )
                Text(
                    text = stringResource(R.string.config_relay_masque_cloudflare_geohash_helper),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
            }
            RipDpiSwitch(
                checked = draft.relayMasqueUseHttp2Fallback,
                onCheckedChange = onRelayMasqueUseHttp2FallbackChanged,
                label = stringResource(R.string.config_relay_masque_http2),
            )
        }

        RelayKindShadowTlsV3 -> {
            RipDpiTextField(
                value = draft.relayShadowTlsInnerProfileId,
                onValueChange = onRelayShadowTlsInnerProfileIdChanged,
                decoration = RipDpiTextFieldDecoration(label = "Inner profile ID"),
            )
            RipDpiTextField(
                value = draft.relayShadowTlsPassword,
                onValueChange = onRelayShadowTlsPasswordChanged,
                decoration = RipDpiTextFieldDecoration(label = "ShadowTLS password"),
            )
        }

        RelayKindNaiveProxy -> {
            RipDpiTextField(
                value = draft.relayNaiveUsername,
                onValueChange = onRelayNaiveUsernameChanged,
                decoration = RipDpiTextFieldDecoration(label = "NaiveProxy username"),
            )
            RipDpiTextField(
                value = draft.relayNaivePassword,
                onValueChange = onRelayNaivePasswordChanged,
                decoration = RipDpiTextFieldDecoration(label = "NaiveProxy password"),
            )
            RipDpiTextField(
                value = draft.relayNaivePath,
                onValueChange = onRelayNaivePathChanged,
                decoration = RipDpiTextFieldDecoration(label = "HTTP path (optional)"),
            )
        }

        RelayKindSnowflake -> {
            RipDpiTextField(
                value = draft.relaySnowflakeBrokerUrl,
                onValueChange = onRelaySnowflakeBrokerUrlChanged,
                decoration = RipDpiTextFieldDecoration(label = "Broker URL"),
            )
            RipDpiTextField(
                value = draft.relaySnowflakeFrontDomain,
                onValueChange = onRelaySnowflakeFrontDomainChanged,
                decoration = RipDpiTextFieldDecoration(label = "Front domain"),
            )
        }

        RelayKindWebTunnel -> {
            RipDpiTextField(
                value = draft.relayWebTunnelUrl,
                onValueChange = onRelayWebTunnelUrlChanged,
                decoration = RipDpiTextFieldDecoration(label = "WebTunnel URL"),
            )
        }

        RelayKindObfs4 -> {
            RipDpiConfigTextField(
                value = draft.relayPtBridgeLine,
                onValueChange = onRelayPtBridgeLineChanged,
                decoration =
                    RipDpiTextFieldDecoration(
                        label = "Bridge line",
                        helperText = "Paste a full obfs4 bridge line from your bridge source.",
                    ),
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
internal fun RelayChainFields(
    draft: ConfigDraft,
    onRelayChainEntryProfileIdChanged: (String) -> Unit,
    onRelayChainExitProfileIdChanged: (String) -> Unit,
) {
    Text(
        text = "Chain relay uses saved relay profiles for both hops. Legacy inline chain settings are read-only.",
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    RipDpiTextField(
        value = draft.relayChainEntryProfileId,
        onValueChange = onRelayChainEntryProfileIdChanged,
        decoration = RipDpiTextFieldDecoration(label = "Entry profile ID"),
    )
    RipDpiTextField(
        value = draft.relayChainExitProfileId,
        onValueChange = onRelayChainExitProfileIdChanged,
        decoration = RipDpiTextFieldDecoration(label = "Exit profile ID"),
    )
}

@Composable
internal fun RowScope.RelayKindChip(
    selectedKind: String,
    kind: String,
    labelRes: Int,
    onRelayKindChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedKind == kind,
        onClick = { onRelayKindChanged(kind) },
        modifier = Modifier.weight(1f),
    )
}

@Composable
internal fun RowScope.RelayKindChip(
    selectedKind: String,
    kind: String,
    label: String,
    onRelayKindChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = label,
        selected = selectedKind == kind,
        onClick = { onRelayKindChanged(kind) },
        modifier = Modifier.weight(1f),
    )
}

@Composable
internal fun RowScope.MasqueAuthModeChip(
    selectedMode: String,
    mode: String,
    labelRes: Int,
    onRelayMasqueAuthModeChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedMode == mode,
        onClick = { onRelayMasqueAuthModeChanged(mode) },
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun RowScope.VlessTransportChip(
    selectedTransport: String,
    transport: String,
    labelRes: Int,
    onRelayVlessTransportChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedTransport == transport,
        onClick = { onRelayVlessTransportChanged(transport) },
        modifier = Modifier.weight(1f),
    )
}
