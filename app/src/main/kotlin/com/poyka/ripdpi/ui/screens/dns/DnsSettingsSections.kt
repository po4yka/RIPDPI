package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.protocolDisplayName
import com.poyka.ripdpi.diagnostics.SystemPrivateDnsStatus
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DnsModeSection(
    uiState: SettingsUiState,
    onModeSelected: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.dns_mode_section))
        Text(
            text = stringResource(R.string.dns_mode_helper),
            style = type.body,
            color = colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            DnsOptionCard(
                icon = RipDpiIcons.Lock,
                title = stringResource(R.string.dns_mode_doh),
                body = stringResource(R.string.dns_mode_encrypted_body),
                selected = uiState.dns.dnsMode == DnsModeEncrypted,
                badges = listOf(protocolDisplayName(uiState.dns.encryptedDnsProtocol)),
                onClick = { onModeSelected(DnsModeEncrypted) },
                testTag = RipDpiTestTags.dnsMode(DnsModeEncrypted),
            )
            DnsOptionCard(
                icon = RipDpiIcons.Dns,
                title = stringResource(R.string.dns_mode_plain),
                body = stringResource(R.string.dns_mode_plain_body),
                selected = uiState.dns.dnsMode == DnsModePlainUdp,
                badges = listOf(uiState.dns.dnsIp),
                onClick = { onModeSelected(DnsModePlainUdp) },
                testTag = RipDpiTestTags.dnsMode(DnsModePlainUdp),
            )
        }
        SystemPrivateDnsInfoSection()
        SystemPrivateDnsWarningSection(status = uiState.dns.systemPrivateDnsStatus)
    }
}

/**
 * Always-visible educational note explaining that RIPDPI resolves DNS through its
 * own in-VPN interceptor, independent of Android's system "Private DNS" setting.
 */
@Composable
private fun SystemPrivateDnsInfoSection() {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = stringResource(R.string.dns_private_dns_info_title),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.dns_private_dns_info_body),
            style = type.body,
            color = colors.mutedForeground,
        )
    }
}

/**
 * Conditional, non-blocking warning shown only when Android system Private DNS is
 * active, to explain a possible resolver mismatch. Purely educational: it never
 * gates VPN startup or DNS routing.
 */
@Composable
private fun SystemPrivateDnsWarningSection(status: String) {
    if (status != SystemPrivateDnsStatus.PRESENT.wireValue) {
        return
    }
    WarningBanner(
        title = stringResource(R.string.dns_private_dns_warning_title),
        message = stringResource(R.string.dns_private_dns_warning_body),
        tone = WarningBannerTone.Info,
    )
}

@Composable
internal fun DnsProtocolSection(
    uiState: SettingsUiState,
    onProtocolSelected: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    AnimatedVisibility(
        visible = uiState.dns.dnsMode == DnsModeEncrypted,
        enter = motion.sectionEnterTransition(),
        exit = motion.sectionExitTransition(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.dns_protocol_section))
            Text(
                text = stringResource(R.string.dns_protocol_helper),
                style = type.body,
                color = colors.mutedForeground,
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                DnsProtocolOptionCards(uiState, onProtocolSelected)
            }
        }
    }
}

@Composable
internal fun DnsResolverCatalogSection(
    uiState: SettingsUiState,
    onResolverSelected: (DnsResolverOption) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    AnimatedVisibility(
        visible =
            uiState.dns.dnsMode == DnsModeEncrypted &&
                uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh,
        enter = motion.sectionEnterTransition(),
        exit = motion.sectionExitTransition(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.dns_resolvers_section))
            Text(
                text = stringResource(R.string.dns_protocol_doh_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                resolverOptions.forEach { resolver ->
                    DnsResolverCard(
                        resolver = resolver,
                        selected = resolver.providerId == uiState.dns.dnsProviderId,
                        onClick = { onResolverSelected(resolver) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DnsCustomResolverSettingsSection(
    uiState: SettingsUiState,
    input: DnsSettingsInputState,
    validation: DnsSettingsValidation,
    onSaveCustomDoh: (String, List<String>) -> Unit,
    onSaveCustomDot: (String, Int, String, List<String>) -> Unit,
    onSaveCustomDnsCrypt: (String, Int, String, String, List<String>) -> Unit,
    onSavePlainDns: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.dns_custom_section))
        Text(
            text = stringResource(R.string.dns_custom_section_body),
            style = type.body,
            color = colors.mutedForeground,
        )
        RipDpiCard(modifier = Modifier.animateContentSize(animationSpec = motion.stateTween())) {
            when (uiState.dns.dnsMode) {
                DnsModePlainUdp -> {
                    PlainDnsEditor(
                        uiState = uiState,
                        input = input,
                        validation = validation,
                        onSavePlainDns = onSavePlainDns,
                    )
                }

                DnsModeEncrypted -> {
                    EncryptedDnsEditor(
                        uiState = uiState,
                        input = input,
                        validation = validation,
                        onSaveCustomDoh = onSaveCustomDoh,
                        onSaveCustomDot = onSaveCustomDot,
                        onSaveCustomDnsCrypt = onSaveCustomDnsCrypt,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DnsIpv6Section(
    ipv6Enable: Boolean,
    onIpv6Changed: (Boolean) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.protocol_ipv6_title))
        RipDpiCard {
            SettingsRow(
                title = stringResource(R.string.protocol_ipv6_title),
                subtitle = stringResource(R.string.dns_ipv6_helper),
                checked = ipv6Enable,
                onCheckedChange = onIpv6Changed,
            )
        }
    }
}

@Composable
private fun DnsProtocolOptionCards(
    uiState: SettingsUiState,
    onProtocolSelected: (String) -> Unit,
) {
    DnsOptionCard(
        icon = RipDpiIcons.Lock,
        title = stringResource(R.string.dns_protocol_doh),
        body = stringResource(R.string.dns_protocol_doh_body),
        selected = uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh,
        badges = listOf(stringResource(R.string.dns_protocol_builtin_and_custom)),
        onClick = { onProtocolSelected(EncryptedDnsProtocolDoh) },
        testTag = RipDpiTestTags.dnsProtocol(EncryptedDnsProtocolDoh),
    )
    DnsOptionCard(
        icon = RipDpiIcons.Lock,
        title = stringResource(R.string.dns_protocol_dot),
        body = stringResource(R.string.dns_protocol_dot_body),
        selected = uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDot,
        badges = listOf(stringResource(R.string.dns_protocol_custom_only)),
        onClick = { onProtocolSelected(EncryptedDnsProtocolDot) },
        testTag = RipDpiTestTags.dnsProtocol(EncryptedDnsProtocolDot),
    )
    DnsOptionCard(
        icon = RipDpiIcons.Vpn,
        title = stringResource(R.string.dns_protocol_dnscrypt),
        body = stringResource(R.string.dns_protocol_dnscrypt_body),
        selected = uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt,
        badges = listOf(stringResource(R.string.dns_protocol_custom_only)),
        onClick = { onProtocolSelected(EncryptedDnsProtocolDnsCrypt) },
        testTag = RipDpiTestTags.dnsProtocol(EncryptedDnsProtocolDnsCrypt),
    )
}

@Composable
private fun PlainDnsEditor(
    uiState: SettingsUiState,
    input: DnsSettingsInputState,
    validation: DnsSettingsValidation,
    onSavePlainDns: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Text(
        text = stringResource(R.string.dns_custom_title),
        style = type.bodyEmphasis,
        color = colors.foreground,
    )
    Text(
        text = stringResource(R.string.dns_custom_body),
        style = type.body,
        color = colors.mutedForeground,
    )
    RipDpiTextField(
        value = input.plainDnsInput,
        onValueChange = input.onPlainDnsInputChange,
        decoration =
            RipDpiTextFieldDecoration(
                testTag = RipDpiTestTags.DnsPlainAddress,
                label = stringResource(R.string.dbs_ip_setting),
                placeholder = stringResource(R.string.config_placeholder_dns),
                helperText =
                    if (uiState.isVpn) {
                        stringResource(R.string.config_dns_helper)
                    } else {
                        stringResource(R.string.config_dns_disabled_helper)
                    },
                errorText =
                    if (input.plainDnsInput.isNotBlank() && !validation.plainDnsValid) {
                        stringResource(R.string.config_error_invalid_dns)
                    } else {
                        null
                    },
            ),
        behavior =
            RipDpiTextFieldBehavior(
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            if (validation.plainDnsValid && validation.plainDnsDirty) {
                                onSavePlainDns(validation.trimmedPlainDns)
                            }
                        },
                    ),
            ),
    )
    RipDpiButton(
        text = stringResource(R.string.config_save),
        onClick = { onSavePlainDns(validation.trimmedPlainDns) },
        enabled = validation.plainDnsValid && validation.plainDnsDirty,
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.DnsPlainSave),
    )
}

@Composable
private fun EncryptedDnsEditor(
    uiState: SettingsUiState,
    input: DnsSettingsInputState,
    validation: DnsSettingsValidation,
    onSaveCustomDoh: (String, List<String>) -> Unit,
    onSaveCustomDot: (String, Int, String, List<String>) -> Unit,
    onSaveCustomDnsCrypt: (String, Int, String, String, List<String>) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    if (uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh &&
        uiState.dns.dnsProviderId != DnsProviderCustom
    ) {
        Text(
            text = stringResource(R.string.dns_custom_switch_hint),
            style = type.caption,
            color = colors.info,
        )
    }
    CustomEncryptedDnsSection(
        uiState = uiState,
        dohUrl = input.customDohUrl,
        onDohUrlChange = input.onCustomDohUrlChange,
        dotHost = input.customDotHost,
        onDotHostChange = input.onCustomDotHostChange,
        dnscryptHost = input.customDnsCryptHost,
        onDnscryptHostChange = input.onCustomDnsCryptHostChange,
        portInput = input.portInput,
        onPortInputChange = input.onPortInputChange,
        tlsServerNameInput = input.tlsServerNameInput,
        onTlsServerNameChange = input.onTlsServerNameInputChange,
        bootstrapInput = input.bootstrapInput,
        onBootstrapInputChange = input.onBootstrapInputChange,
        dnscryptProviderInput = input.dnscryptProviderInput,
        onDnscryptProviderChange = input.onDnscryptProviderInputChange,
        dnscryptPublicKeyInput = input.dnscryptPublicKeyInput,
        onDnscryptPublicKeyChange = input.onDnscryptPublicKeyInputChange,
        customDohValid = validation.customDohValid,
        customDohDirty = validation.customDohDirty,
        customDotValid = validation.customDotValid,
        customDotDirty = validation.customDotDirty,
        customDnsCryptValid = validation.customDnsCryptValid,
        customDnsCryptDirty = validation.customDnsCryptDirty,
        dnscryptPublicKeyValid = validation.dnscryptPublicKeyValid,
        bootstrapIpsValid = validation.bootstrapIpsValid,
        onSaveCustomDoh = {
            onSaveCustomDoh(validation.trimmedDohUrl, validation.normalizedBootstrapIps)
        },
        onSaveCustomDot = {
            onSaveCustomDot(
                validation.trimmedDotHost,
                validation.dotPort,
                validation.trimmedDotTlsServerName,
                validation.normalizedBootstrapIps,
            )
        },
        onSaveCustomDnsCrypt = {
            onSaveCustomDnsCrypt(
                validation.trimmedDnsCryptHost,
                validation.dnscryptPort,
                validation.trimmedDnsCryptProvider,
                validation.trimmedDnsCryptPublicKey,
                validation.normalizedBootstrapIps,
            )
        },
    )
}
