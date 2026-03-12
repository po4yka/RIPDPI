package com.poyka.ripdpi.ui.screens.dns

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScaffoldWidth
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.checkNotLocalIp
import java.net.URI

internal data class DnsResolverOption(
    val providerId: String,
    val protocol: String,
    val address: String,
    val host: String,
    val port: Int,
    val tlsServerName: String,
    val dohUrl: String,
    val bootstrapIps: List<String>,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
)

private val resolverOptions =
    listOf(
        DnsResolverOption(
            providerId = DnsProviderCloudflare,
            protocol = EncryptedDnsProtocolDoh,
            address = "1.1.1.1",
            host = "cloudflare-dns.com",
            port = 443,
            tlsServerName = "cloudflare-dns.com",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            titleRes = R.string.dns_resolver_cloudflare_title,
            descriptionRes = R.string.dns_resolver_cloudflare_body,
        ),
        DnsResolverOption(
            providerId = "google",
            protocol = EncryptedDnsProtocolDoh,
            address = "8.8.8.8",
            host = "dns.google",
            port = 443,
            tlsServerName = "dns.google",
            dohUrl = "https://dns.google/dns-query",
            bootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
            titleRes = R.string.dns_resolver_google_title,
            descriptionRes = R.string.dns_resolver_google_body,
        ),
        DnsResolverOption(
            providerId = "quad9",
            protocol = EncryptedDnsProtocolDoh,
            address = "9.9.9.9",
            host = "dns.quad9.net",
            port = 443,
            tlsServerName = "dns.quad9.net",
            dohUrl = "https://dns.quad9.net/dns-query",
            bootstrapIps = listOf("9.9.9.9", "149.112.112.112"),
            titleRes = R.string.dns_resolver_quad9_title,
            descriptionRes = R.string.dns_resolver_quad9_body,
        ),
        DnsResolverOption(
            providerId = "adguard",
            protocol = EncryptedDnsProtocolDoh,
            address = "94.140.14.14",
            host = "dns.adguard-dns.com",
            port = 443,
            tlsServerName = "dns.adguard-dns.com",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            bootstrapIps = listOf("94.140.14.14", "94.140.15.15"),
            titleRes = R.string.dns_resolver_adguard_title,
            descriptionRes = R.string.dns_resolver_adguard_body,
        ),
    )

@Composable
fun DnsSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DnsSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onModeSelected = { mode ->
            if (mode == DnsModeEncrypted) {
                if (uiState.encryptedDnsProtocol == EncryptedDnsProtocolDoh && uiState.dnsProviderId != DnsProviderCustom) {
                    viewModel.selectBuiltInDnsProvider(uiState.dnsProviderId)
                } else {
                    viewModel.setEncryptedDnsProtocol(
                        uiState.encryptedDnsProtocol.ifBlank { EncryptedDnsProtocolDoh },
                    )
                }
            } else {
                viewModel.setPlainDnsServer(uiState.dnsIp)
            }
        },
        onProtocolSelected = { protocol ->
            when (protocol) {
                EncryptedDnsProtocolDoh -> {
                    val providerId =
                        resolverOptions.firstOrNull { it.providerId == uiState.dnsProviderId }?.providerId
                            ?: DnsProviderCloudflare
                    viewModel.selectBuiltInDnsProvider(providerId)
                }
                else -> {
                    viewModel.setEncryptedDnsProtocol(protocol)
                }
            }
        },
        onResolverSelected = { resolver ->
            viewModel.selectBuiltInDnsProvider(resolver.providerId)
        },
        onSaveCustomDoh = { dohUrl, bootstrapIps ->
            viewModel.setCustomDohResolver(dohUrl, bootstrapIps)
        },
        onSaveCustomDot = { host, port, tlsServerName, bootstrapIps ->
            viewModel.setCustomDotResolver(host, port, tlsServerName, bootstrapIps)
        },
        onSaveCustomDnsCrypt = { host, port, providerName, publicKey, bootstrapIps ->
            viewModel.setCustomDnsCryptResolver(host, port, providerName, publicKey, bootstrapIps)
        },
        onSavePlainDns = { dnsAddress ->
            viewModel.setPlainDnsServer(dnsAddress)
        },
        onIpv6Changed = { enabled ->
            viewModel.updateSetting(
                key = "ipv6Enable",
                value = enabled.toString(),
            ) {
                setIpv6Enable(enabled)
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun DnsSettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onModeSelected: (String) -> Unit,
    onProtocolSelected: (String) -> Unit,
    onResolverSelected: (DnsResolverOption) -> Unit,
    onSaveCustomDoh: (String, List<String>) -> Unit,
    onSaveCustomDot: (String, Int, String, List<String>) -> Unit,
    onSaveCustomDnsCrypt: (String, Int, String, String, List<String>) -> Unit,
    onSavePlainDns: (String) -> Unit,
    onIpv6Changed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val selectedResolver =
        remember(uiState.dnsProviderId, uiState.encryptedDnsProtocol) {
            resolverOptions.firstOrNull {
                uiState.dnsMode == DnsModeEncrypted &&
                    uiState.encryptedDnsProtocol == EncryptedDnsProtocolDoh &&
                    it.providerId == uiState.dnsProviderId
            }
        }

    var plainDnsInput by rememberSaveable(uiState.dnsIp, uiState.dnsMode) {
        mutableStateOf(if (uiState.dnsMode == DnsModePlainUdp) uiState.dnsIp else "")
    }
    var customDohUrl by rememberSaveable(uiState.encryptedDnsDohUrl, uiState.encryptedDnsProtocol) {
        mutableStateOf(if (uiState.encryptedDnsProtocol == EncryptedDnsProtocolDoh) uiState.encryptedDnsDohUrl else "")
    }
    var customDotHost by rememberSaveable(uiState.encryptedDnsHost, uiState.encryptedDnsProtocol) {
        mutableStateOf(if (uiState.encryptedDnsProtocol == EncryptedDnsProtocolDot) uiState.encryptedDnsHost else "")
    }
    var customDnsCryptHost by rememberSaveable(uiState.encryptedDnsHost, uiState.encryptedDnsProtocol) {
        mutableStateOf(if (uiState.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) uiState.encryptedDnsHost else "")
    }
    var portInput by rememberSaveable(uiState.encryptedDnsPort, uiState.encryptedDnsProtocol) {
        val value =
            if (uiState.encryptedDnsProtocol == EncryptedDnsProtocolDot || uiState.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) {
                uiState.encryptedDnsPort.takeIf { it > 0 }?.toString().orEmpty()
            } else {
                ""
            }
        mutableStateOf(value)
    }
    var tlsServerNameInput by rememberSaveable(uiState.encryptedDnsTlsServerName, uiState.encryptedDnsProtocol) {
        mutableStateOf(if (uiState.encryptedDnsProtocol == EncryptedDnsProtocolDot) uiState.encryptedDnsTlsServerName else "")
    }
    var bootstrapInput by rememberSaveable(uiState.encryptedDnsBootstrapIps, uiState.encryptedDnsProtocol) {
        mutableStateOf(
            if (uiState.dnsMode == DnsModeEncrypted) {
                uiState.encryptedDnsBootstrapIps.joinToString(separator = ", ")
            } else {
                ""
            },
        )
    }
    var dnscryptProviderInput by rememberSaveable(uiState.encryptedDnsDnscryptProviderName, uiState.encryptedDnsProtocol) {
        mutableStateOf(
            if (uiState.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) {
                uiState.encryptedDnsDnscryptProviderName
            } else {
                ""
            },
        )
    }
    var dnscryptPublicKeyInput by rememberSaveable(uiState.encryptedDnsDnscryptPublicKey, uiState.encryptedDnsProtocol) {
        mutableStateOf(
            if (uiState.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) {
                uiState.encryptedDnsDnscryptPublicKey
            } else {
                ""
            },
        )
    }

    val trimmedPlainDns = plainDnsInput.trim()
    val normalizedBootstrapIps = parseBootstrapIps(bootstrapInput)
    val bootstrapIpsValid = normalizedBootstrapIps.isNotEmpty() && normalizedBootstrapIps.all(::checkNotLocalIp)

    val plainDnsValid = trimmedPlainDns.isNotEmpty() && checkNotLocalIp(trimmedPlainDns)
    val plainDnsDirty = uiState.dnsMode != DnsModePlainUdp || trimmedPlainDns != uiState.dnsIp

    val trimmedDohUrl = customDohUrl.trim()
    val customDohValid = isValidHttpsUrl(trimmedDohUrl) && bootstrapIpsValid
    val customDohDirty =
        uiState.dnsMode != DnsModeEncrypted ||
            uiState.encryptedDnsProtocol != EncryptedDnsProtocolDoh ||
            uiState.dnsProviderId != DnsProviderCustom ||
            trimmedDohUrl != uiState.encryptedDnsDohUrl ||
            normalizedBootstrapIps != uiState.encryptedDnsBootstrapIps

    val trimmedDotHost = customDotHost.trim()
    val trimmedDotTlsServerName = tlsServerNameInput.trim()
    val dotPort = portInput.toIntOrNull() ?: 0
    val customDotValid =
        trimmedDotHost.isNotEmpty() &&
            dotPort in 1..65535 &&
            trimmedDotTlsServerName.isNotEmpty() &&
            bootstrapIpsValid
    val customDotDirty =
        uiState.dnsMode != DnsModeEncrypted ||
            uiState.encryptedDnsProtocol != EncryptedDnsProtocolDot ||
            uiState.dnsProviderId != DnsProviderCustom ||
            trimmedDotHost != uiState.encryptedDnsHost ||
            dotPort != uiState.encryptedDnsPort ||
            trimmedDotTlsServerName != uiState.encryptedDnsTlsServerName ||
            normalizedBootstrapIps != uiState.encryptedDnsBootstrapIps

    val trimmedDnsCryptHost = customDnsCryptHost.trim()
    val dnscryptPort = portInput.toIntOrNull() ?: 0
    val trimmedDnsCryptProvider = dnscryptProviderInput.trim()
    val trimmedDnsCryptPublicKey = dnscryptPublicKeyInput.trim()
    val dnscryptPublicKeyValid = trimmedDnsCryptPublicKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    val customDnsCryptValid =
        trimmedDnsCryptHost.isNotEmpty() &&
            dnscryptPort in 1..65535 &&
            trimmedDnsCryptProvider.isNotEmpty() &&
            dnscryptPublicKeyValid &&
            bootstrapIpsValid
    val customDnsCryptDirty =
        uiState.dnsMode != DnsModeEncrypted ||
            uiState.encryptedDnsProtocol != EncryptedDnsProtocolDnsCrypt ||
            uiState.dnsProviderId != DnsProviderCustom ||
            trimmedDnsCryptHost != uiState.encryptedDnsHost ||
            dnscryptPort != uiState.encryptedDnsPort ||
            trimmedDnsCryptProvider != uiState.encryptedDnsDnscryptProviderName ||
            trimmedDnsCryptPublicKey != uiState.encryptedDnsDnscryptPublicKey ||
            normalizedBootstrapIps != uiState.encryptedDnsBootstrapIps

    RipDpiContentScreenScaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.title_dns_settings),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        contentWidth = RipDpiScaffoldWidth.Form,
    ) {
        if (!uiState.isVpn) {
            WarningBanner(
                title = stringResource(R.string.dns_proxy_banner_title),
                message = stringResource(R.string.dns_proxy_banner_body),
                tone = WarningBannerTone.Restricted,
            )
        }

        RipDpiCard(
            variant =
                if (selectedResolver != null) {
                    RipDpiCardVariant.Elevated
                } else {
                    RipDpiCardVariant.Outlined
                },
        ) {
            Text(
                text = stringResource(R.string.dns_active_section_title),
                style = type.sectionTitle,
                color = colors.mutedForeground,
            )
            Text(
                text =
                    selectedResolver?.let { stringResource(it.titleRes) }
                        ?: if (uiState.dnsMode == DnsModeEncrypted) {
                            stringResource(R.string.dns_custom_title)
                        } else {
                            stringResource(R.string.dns_mode_plain)
                        },
                style = type.screenTitle,
                color = colors.foreground,
            )
            Text(
                text = uiState.dnsSummary,
                style = type.monoValue,
                color = colors.foreground,
            )
            Text(
                text =
                    if (uiState.isVpn) {
                        stringResource(R.string.config_dns_helper)
                    } else {
                        stringResource(R.string.config_dns_disabled_helper)
                    },
                style = type.body,
                color = colors.mutedForeground,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.dns_mode_section))
            RipDpiCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    RipDpiButton(
                        text = stringResource(R.string.dns_mode_doh),
                        onClick = { onModeSelected(DnsModeEncrypted) },
                        variant =
                            if (uiState.dnsMode == DnsModeEncrypted) {
                                RipDpiButtonVariant.Primary
                            } else {
                                RipDpiButtonVariant.Outline
                            },
                        modifier = Modifier.weight(1f),
                    )
                    RipDpiButton(
                        text = stringResource(R.string.dns_mode_plain),
                        onClick = { onModeSelected(DnsModePlainUdp) },
                        variant =
                            if (uiState.dnsMode == DnsModePlainUdp) {
                                RipDpiButtonVariant.Primary
                            } else {
                                RipDpiButtonVariant.Outline
                            },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.dns_mode_helper),
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
        }

        if (uiState.dnsMode == DnsModeEncrypted) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.dns_protocol_section))
                RipDpiCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        ProtocolButton(
                            title = stringResource(R.string.dns_protocol_doh),
                            selected = uiState.encryptedDnsProtocol == EncryptedDnsProtocolDoh,
                            onClick = { onProtocolSelected(EncryptedDnsProtocolDoh) },
                            modifier = Modifier.weight(1f),
                        )
                        ProtocolButton(
                            title = stringResource(R.string.dns_protocol_dot),
                            selected = uiState.encryptedDnsProtocol == EncryptedDnsProtocolDot,
                            onClick = { onProtocolSelected(EncryptedDnsProtocolDot) },
                            modifier = Modifier.weight(1f),
                        )
                        ProtocolButton(
                            title = stringResource(R.string.dns_protocol_dnscrypt),
                            selected = uiState.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt,
                            onClick = { onProtocolSelected(EncryptedDnsProtocolDnsCrypt) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.dns_protocol_helper),
                        style = type.body,
                        color = colors.mutedForeground,
                    )
                }
            }
        }

        if (uiState.dnsMode == DnsModeEncrypted && uiState.encryptedDnsProtocol == EncryptedDnsProtocolDoh) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.dns_resolvers_section))
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    resolverOptions.forEach { resolver ->
                        DnsResolverCard(
                            resolver = resolver,
                            selected = resolver.providerId == uiState.dnsProviderId,
                            onClick = { onResolverSelected(resolver) },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.dns_custom_section))
            RipDpiCard {
                when (uiState.dnsMode) {
                    DnsModePlainUdp -> {
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
                            value = plainDnsInput,
                            onValueChange = { plainDnsInput = it },
                            label = stringResource(R.string.dbs_ip_setting),
                            placeholder = stringResource(R.string.config_placeholder_dns),
                            helperText =
                                if (uiState.isVpn) {
                                    stringResource(R.string.config_dns_helper)
                                } else {
                                    stringResource(R.string.config_dns_disabled_helper)
                                },
                            errorText =
                                if (plainDnsInput.isNotBlank() && !plainDnsValid) {
                                    stringResource(R.string.config_error_invalid_dns)
                                } else {
                                    null
                                },
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Done,
                                ),
                            keyboardActions =
                                KeyboardActions(
                                    onDone = {
                                        if (plainDnsValid && plainDnsDirty) {
                                            onSavePlainDns(trimmedPlainDns)
                                        }
                                    },
                                ),
                        )
                        RipDpiButton(
                            text = stringResource(R.string.config_save),
                            onClick = { onSavePlainDns(trimmedPlainDns) },
                            enabled = plainDnsValid && plainDnsDirty,
                            trailingIcon = RipDpiIcons.Check,
                        )
                    }

                    DnsModeEncrypted -> {
                        CustomEncryptedDnsSection(
                            uiState = uiState,
                            dohUrl = customDohUrl,
                            onDohUrlChange = { customDohUrl = it },
                            dotHost = customDotHost,
                            onDotHostChange = { customDotHost = it },
                            dnscryptHost = customDnsCryptHost,
                            onDnscryptHostChange = { customDnsCryptHost = it },
                            portInput = portInput,
                            onPortInputChange = { portInput = it },
                            tlsServerNameInput = tlsServerNameInput,
                            onTlsServerNameChange = { tlsServerNameInput = it },
                            bootstrapInput = bootstrapInput,
                            onBootstrapInputChange = { bootstrapInput = it },
                            dnscryptProviderInput = dnscryptProviderInput,
                            onDnscryptProviderChange = { dnscryptProviderInput = it },
                            dnscryptPublicKeyInput = dnscryptPublicKeyInput,
                            onDnscryptPublicKeyChange = { dnscryptPublicKeyInput = it },
                            customDohValid = customDohValid,
                            customDohDirty = customDohDirty,
                            customDotValid = customDotValid,
                            customDotDirty = customDotDirty,
                            customDnsCryptValid = customDnsCryptValid,
                            customDnsCryptDirty = customDnsCryptDirty,
                            dnscryptPublicKeyValid = dnscryptPublicKeyValid,
                            bootstrapIpsValid = bootstrapIpsValid,
                            onSaveCustomDoh = { onSaveCustomDoh(trimmedDohUrl, normalizedBootstrapIps) },
                            onSaveCustomDot = {
                                onSaveCustomDot(
                                    trimmedDotHost,
                                    dotPort,
                                    trimmedDotTlsServerName,
                                    normalizedBootstrapIps,
                                )
                            },
                            onSaveCustomDnsCrypt = {
                                onSaveCustomDnsCrypt(
                                    trimmedDnsCryptHost,
                                    dnscryptPort,
                                    trimmedDnsCryptProvider,
                                    trimmedDnsCryptPublicKey,
                                    normalizedBootstrapIps,
                                )
                            },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.protocol_ipv6_title))
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.protocol_ipv6_title),
                    subtitle = stringResource(R.string.dns_ipv6_helper),
                    checked = uiState.ipv6Enable,
                    onCheckedChange = onIpv6Changed,
                )
            }
        }
    }
}

@Composable
private fun ProtocolButton(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiButton(
        text = title,
        onClick = onClick,
        variant = if (selected) RipDpiButtonVariant.Primary else RipDpiButtonVariant.Outline,
        modifier = modifier,
    )
}

@Composable
private fun CustomEncryptedDnsSection(
    uiState: SettingsUiState,
    dohUrl: String,
    onDohUrlChange: (String) -> Unit,
    dotHost: String,
    onDotHostChange: (String) -> Unit,
    dnscryptHost: String,
    onDnscryptHostChange: (String) -> Unit,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    tlsServerNameInput: String,
    onTlsServerNameChange: (String) -> Unit,
    bootstrapInput: String,
    onBootstrapInputChange: (String) -> Unit,
    dnscryptProviderInput: String,
    onDnscryptProviderChange: (String) -> Unit,
    dnscryptPublicKeyInput: String,
    onDnscryptPublicKeyChange: (String) -> Unit,
    customDohValid: Boolean,
    customDohDirty: Boolean,
    customDotValid: Boolean,
    customDotDirty: Boolean,
    customDnsCryptValid: Boolean,
    customDnsCryptDirty: Boolean,
    dnscryptPublicKeyValid: Boolean,
    bootstrapIpsValid: Boolean,
    onSaveCustomDoh: () -> Unit,
    onSaveCustomDot: () -> Unit,
    onSaveCustomDnsCrypt: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    when (uiState.encryptedDnsProtocol) {
        EncryptedDnsProtocolDot -> {
            Text(
                text = stringResource(R.string.dns_custom_dot_title),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.dns_custom_dot_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            CommonEndpointFields(
                host = dotHost,
                onHostChange = onDotHostChange,
                portInput = portInput,
                onPortInputChange = onPortInputChange,
                bootstrapInput = bootstrapInput,
                onBootstrapInputChange = onBootstrapInputChange,
                hostLabel = stringResource(R.string.dns_custom_host_label),
                hostHelper = stringResource(R.string.dns_custom_dot_host_helper),
                bootstrapError = if (bootstrapInput.isNotBlank() && !bootstrapIpsValid) stringResource(R.string.dns_custom_bootstrap_error) else null,
            )
            RipDpiTextField(
                value = tlsServerNameInput,
                onValueChange = onTlsServerNameChange,
                label = stringResource(R.string.dns_custom_tls_server_name_label),
                placeholder = "resolver.example",
                helperText = stringResource(R.string.dns_custom_dot_tls_helper),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            if (customDotValid && customDotDirty) {
                                onSaveCustomDot()
                            }
                        },
                    ),
            )
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = onSaveCustomDot,
                enabled = customDotValid && customDotDirty,
                trailingIcon = RipDpiIcons.Check,
            )
        }

        EncryptedDnsProtocolDnsCrypt -> {
            Text(
                text = stringResource(R.string.dns_custom_dnscrypt_title),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.dns_custom_dnscrypt_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            CommonEndpointFields(
                host = dnscryptHost,
                onHostChange = onDnscryptHostChange,
                portInput = portInput,
                onPortInputChange = onPortInputChange,
                bootstrapInput = bootstrapInput,
                onBootstrapInputChange = onBootstrapInputChange,
                hostLabel = stringResource(R.string.dns_custom_host_label),
                hostHelper = stringResource(R.string.dns_custom_dnscrypt_host_helper),
                bootstrapError = if (bootstrapInput.isNotBlank() && !bootstrapIpsValid) stringResource(R.string.dns_custom_bootstrap_error) else null,
            )
            RipDpiTextField(
                value = dnscryptProviderInput,
                onValueChange = onDnscryptProviderChange,
                label = stringResource(R.string.dns_custom_dnscrypt_provider_label),
                placeholder = "2.dnscrypt-cert.resolver.example",
                helperText = stringResource(R.string.dns_custom_dnscrypt_provider_helper),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
            )
            RipDpiTextField(
                value = dnscryptPublicKeyInput,
                onValueChange = onDnscryptPublicKeyChange,
                label = stringResource(R.string.dns_custom_dnscrypt_public_key_label),
                placeholder = "0123abcd...",
                helperText = stringResource(R.string.dns_custom_dnscrypt_public_key_helper),
                errorText =
                    if (dnscryptPublicKeyInput.isNotBlank() && !dnscryptPublicKeyValid) {
                        stringResource(R.string.dns_custom_dnscrypt_public_key_error)
                    } else {
                        null
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            if (customDnsCryptValid && customDnsCryptDirty) {
                                onSaveCustomDnsCrypt()
                            }
                        },
                    ),
            )
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = onSaveCustomDnsCrypt,
                enabled = customDnsCryptValid && customDnsCryptDirty,
                trailingIcon = RipDpiIcons.Check,
            )
        }

        else -> {
            Text(
                text = stringResource(R.string.dns_custom_doh_title),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.dns_custom_doh_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            RipDpiTextField(
                value = dohUrl,
                onValueChange = onDohUrlChange,
                label = stringResource(R.string.dns_custom_doh_url_label),
                placeholder = "https://resolver.example/dns-query",
                helperText = stringResource(R.string.dns_custom_doh_url_helper),
                errorText =
                    if (dohUrl.isNotBlank() && !isValidHttpsUrl(dohUrl.trim())) {
                        stringResource(R.string.dns_custom_doh_url_error)
                    } else {
                        null
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
            )
            RipDpiTextField(
                value = bootstrapInput,
                onValueChange = onBootstrapInputChange,
                label = stringResource(R.string.dns_custom_bootstrap_label),
                placeholder = "1.1.1.1, 1.0.0.1",
                helperText = stringResource(R.string.dns_custom_bootstrap_helper),
                errorText =
                    if (bootstrapInput.isNotBlank() && !bootstrapIpsValid) {
                        stringResource(R.string.dns_custom_bootstrap_error)
                    } else {
                        null
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            if (customDohValid && customDohDirty) {
                                onSaveCustomDoh()
                            }
                        },
                    ),
            )
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = onSaveCustomDoh,
                enabled = customDohValid && customDohDirty,
                variant =
                    if (uiState.dnsProviderId == DnsProviderCustom) {
                        RipDpiButtonVariant.Primary
                    } else {
                        RipDpiButtonVariant.Outline
                    },
                trailingIcon = RipDpiIcons.Check,
            )
        }
    }
}

@Composable
private fun CommonEndpointFields(
    host: String,
    onHostChange: (String) -> Unit,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    bootstrapInput: String,
    onBootstrapInputChange: (String) -> Unit,
    hostLabel: String,
    hostHelper: String,
    bootstrapError: String?,
) {
    RipDpiTextField(
        value = host,
        onValueChange = onHostChange,
        label = hostLabel,
        placeholder = "resolver.example",
        helperText = hostHelper,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
    )
    RipDpiTextField(
        value = portInput,
        onValueChange = onPortInputChange,
        label = stringResource(R.string.dns_custom_port_label),
        placeholder = "443",
        helperText = stringResource(R.string.dns_custom_port_helper),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
    )
    RipDpiTextField(
        value = bootstrapInput,
        onValueChange = onBootstrapInputChange,
        label = stringResource(R.string.dns_custom_bootstrap_label),
        placeholder = "1.1.1.1, 1.0.0.1",
        helperText = stringResource(R.string.dns_custom_bootstrap_helper),
        errorText = bootstrapError,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
    )
}

@Composable
private fun DnsResolverCard(
    resolver: DnsResolverOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    RipDpiCard(
        variant = if (selected) RipDpiCardVariant.Elevated else RipDpiCardVariant.Outlined,
        onClick = onClick,
    ) {
        Text(
            text = stringResource(resolver.titleRes),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(resolver.descriptionRes),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = resolver.dohUrl,
            style = RipDpiThemeTokens.type.monoValue,
            color = RipDpiThemeTokens.colors.foreground,
        )
    }
}

private fun parseBootstrapIps(value: String): List<String> =
    value
        .split(',', ' ', '\n', '\t')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

private fun isValidHttpsUrl(value: String): Boolean =
    runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

@Preview(showBackground = true)
@Composable
private fun DnsSettingsEncryptedPreview() {
    RipDpiTheme {
        DnsSettingsScreen(
            uiState =
                SettingsUiState(
                    ripdpiMode = Mode.VPN.preferenceValue,
                    dnsMode = DnsModeEncrypted,
                    dnsProviderId = DnsProviderCloudflare,
                    dnsSummary = "Encrypted DNS · Cloudflare (DoH)",
                    encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                    encryptedDnsHost = "cloudflare-dns.com",
                    encryptedDnsPort = 443,
                    encryptedDnsTlsServerName = "cloudflare-dns.com",
                    encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                    encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
                    isVpn = true,
                ),
            onBack = {},
            onModeSelected = {},
            onProtocolSelected = {},
            onResolverSelected = {},
            onSaveCustomDoh = { _, _ -> },
            onSaveCustomDot = { _, _, _, _ -> },
            onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
            onSavePlainDns = {},
            onIpv6Changed = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DnsSettingsPlainPreview() {
    RipDpiTheme {
        DnsSettingsScreen(
            uiState =
                SettingsUiState(
                    ripdpiMode = Mode.VPN.preferenceValue,
                    dnsMode = DnsModePlainUdp,
                    dnsProviderId = DnsProviderCustom,
                    dnsIp = "9.9.9.9",
                    dnsSummary = "Plain DNS · 9.9.9.9",
                    isVpn = true,
                ),
            onBack = {},
            onModeSelected = {},
            onProtocolSelected = {},
            onResolverSelected = {},
            onSaveCustomDoh = { _, _ -> },
            onSaveCustomDot = { _, _, _, _ -> },
            onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
            onSavePlainDns = {},
            onIpv6Changed = {},
        )
    }
}
