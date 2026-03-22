package com.poyka.ripdpi.ui.screens.dns

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DnsUiState
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
import com.poyka.ripdpi.data.normalizeDnsBootstrapIps
import com.poyka.ripdpi.data.protocolDisplayName
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
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.checkNotLocalIp
import java.net.URI

private val DnsCryptPublicKeyPattern = Regex("^[0-9a-fA-F]{64}$")

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
                if (uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh &&
                    uiState.dns.dnsProviderId != DnsProviderCustom
                ) {
                    viewModel.selectBuiltInDnsProvider(uiState.dns.dnsProviderId)
                } else {
                    viewModel.setEncryptedDnsProtocol(
                        uiState.dns.encryptedDnsProtocol.ifBlank { EncryptedDnsProtocolDoh },
                    )
                }
            } else {
                viewModel.setPlainDnsServer(uiState.dns.dnsIp)
            }
        },
        onProtocolSelected = { protocol ->
            when (protocol) {
                EncryptedDnsProtocolDoh -> {
                    val providerId =
                        resolverOptions.firstOrNull { it.providerId == uiState.dns.dnsProviderId }?.providerId
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
        remember(uiState.dns.dnsProviderId, uiState.dns.encryptedDnsProtocol) {
            resolverOptions.firstOrNull {
                uiState.dns.dnsMode == DnsModeEncrypted &&
                    uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh &&
                    it.providerId == uiState.dns.dnsProviderId
            }
        }

    var plainDnsInput by rememberSaveable(uiState.dns.dnsIp, uiState.dns.dnsMode) {
        mutableStateOf(if (uiState.dns.dnsMode == DnsModePlainUdp) uiState.dns.dnsIp else "")
    }
    var customDohUrl by rememberSaveable(uiState.dns.encryptedDnsDohUrl, uiState.dns.encryptedDnsProtocol) {
        mutableStateOf(
            if (uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh) uiState.dns.encryptedDnsDohUrl else "",
        )
    }
    var customDotHost by rememberSaveable(uiState.dns.encryptedDnsHost, uiState.dns.encryptedDnsProtocol) {
        mutableStateOf(
            if (uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDot) uiState.dns.encryptedDnsHost else "",
        )
    }
    var customDnsCryptHost by rememberSaveable(uiState.dns.encryptedDnsHost, uiState.dns.encryptedDnsProtocol) {
        mutableStateOf(
            if (uiState.dns.encryptedDnsProtocol ==
                EncryptedDnsProtocolDnsCrypt
            ) {
                uiState.dns.encryptedDnsHost
            } else {
                ""
            },
        )
    }
    var portInput by rememberSaveable(uiState.dns.encryptedDnsPort, uiState.dns.encryptedDnsProtocol) {
        val value =
            if (uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDot ||
                uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt
            ) {
                uiState.dns.encryptedDnsPort
                    .takeIf { it > 0 }
                    ?.toString()
                    .orEmpty()
            } else {
                ""
            }
        mutableStateOf(value)
    }
    var tlsServerNameInput by rememberSaveable(
        uiState.dns.encryptedDnsTlsServerName,
        uiState.dns.encryptedDnsProtocol,
    ) {
        mutableStateOf(
            if (uiState.dns.encryptedDnsProtocol ==
                EncryptedDnsProtocolDot
            ) {
                uiState.dns.encryptedDnsTlsServerName
            } else {
                ""
            },
        )
    }
    var bootstrapInput by rememberSaveable(uiState.dns.encryptedDnsBootstrapIps, uiState.dns.encryptedDnsProtocol) {
        mutableStateOf(
            if (uiState.dns.dnsMode == DnsModeEncrypted) {
                uiState.dns.encryptedDnsBootstrapIps.joinToString(separator = ", ")
            } else {
                ""
            },
        )
    }
    var dnscryptProviderInput by rememberSaveable(
        uiState.dns.encryptedDnsDnscryptProviderName,
        uiState.dns.encryptedDnsProtocol,
    ) {
        mutableStateOf(
            if (uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) {
                uiState.dns.encryptedDnsDnscryptProviderName
            } else {
                ""
            },
        )
    }
    var dnscryptPublicKeyInput by rememberSaveable(
        uiState.dns.encryptedDnsDnscryptPublicKey,
        uiState.dns.encryptedDnsProtocol,
    ) {
        mutableStateOf(
            if (uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) {
                uiState.dns.encryptedDnsDnscryptPublicKey
            } else {
                ""
            },
        )
    }

    val trimmedPlainDns = plainDnsInput.trim()
    val normalizedBootstrapIps = remember(bootstrapInput) { parseBootstrapIps(bootstrapInput) }
    val bootstrapIpsValid = normalizedBootstrapIps.isNotEmpty() && normalizedBootstrapIps.all(::checkNotLocalIp)

    val plainDnsValid = trimmedPlainDns.isNotEmpty() && checkNotLocalIp(trimmedPlainDns)
    val plainDnsDirty = uiState.dns.dnsMode != DnsModePlainUdp || trimmedPlainDns != uiState.dns.dnsIp

    val trimmedDohUrl = customDohUrl.trim()
    val customDohValid = isValidHttpsUrl(trimmedDohUrl) && bootstrapIpsValid
    val customDohDirty =
        uiState.dns.dnsMode != DnsModeEncrypted ||
            uiState.dns.encryptedDnsProtocol != EncryptedDnsProtocolDoh ||
            uiState.dns.dnsProviderId != DnsProviderCustom ||
            trimmedDohUrl != uiState.dns.encryptedDnsDohUrl ||
            normalizedBootstrapIps != uiState.dns.encryptedDnsBootstrapIps

    val trimmedDotHost = customDotHost.trim()
    val trimmedDotTlsServerName = tlsServerNameInput.trim()
    val dotPort = portInput.toIntOrNull() ?: 0
    val customDotValid =
        trimmedDotHost.isNotEmpty() &&
            dotPort in 1..65535 &&
            trimmedDotTlsServerName.isNotEmpty() &&
            bootstrapIpsValid
    val customDotDirty =
        uiState.dns.dnsMode != DnsModeEncrypted ||
            uiState.dns.encryptedDnsProtocol != EncryptedDnsProtocolDot ||
            uiState.dns.dnsProviderId != DnsProviderCustom ||
            trimmedDotHost != uiState.dns.encryptedDnsHost ||
            dotPort != uiState.dns.encryptedDnsPort ||
            trimmedDotTlsServerName != uiState.dns.encryptedDnsTlsServerName ||
            normalizedBootstrapIps != uiState.dns.encryptedDnsBootstrapIps

    val trimmedDnsCryptHost = customDnsCryptHost.trim()
    val dnscryptPort = portInput.toIntOrNull() ?: 0
    val trimmedDnsCryptProvider = dnscryptProviderInput.trim()
    val trimmedDnsCryptPublicKey = dnscryptPublicKeyInput.trim()
    val dnscryptPublicKeyValid = trimmedDnsCryptPublicKey.matches(DnsCryptPublicKeyPattern)
    val customDnsCryptValid =
        trimmedDnsCryptHost.isNotEmpty() &&
            dnscryptPort in 1..65535 &&
            trimmedDnsCryptProvider.isNotEmpty() &&
            dnscryptPublicKeyValid &&
            bootstrapIpsValid
    val customDnsCryptDirty =
        uiState.dns.dnsMode != DnsModeEncrypted ||
            uiState.dns.encryptedDnsProtocol != EncryptedDnsProtocolDnsCrypt ||
            uiState.dns.dnsProviderId != DnsProviderCustom ||
            trimmedDnsCryptHost != uiState.dns.encryptedDnsHost ||
            dnscryptPort != uiState.dns.encryptedDnsPort ||
            trimmedDnsCryptProvider != uiState.dns.encryptedDnsDnscryptProviderName ||
            trimmedDnsCryptPublicKey != uiState.dns.encryptedDnsDnscryptPublicKey ||
            normalizedBootstrapIps != uiState.dns.encryptedDnsBootstrapIps

    RipDpiContentScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.DnsSettings))
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

        DnsActiveConfigurationCard(
            uiState = uiState,
            selectedResolver = selectedResolver,
        )

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
                )
                DnsOptionCard(
                    icon = RipDpiIcons.Dns,
                    title = stringResource(R.string.dns_mode_plain),
                    body = stringResource(R.string.dns_mode_plain_body),
                    selected = uiState.dns.dnsMode == DnsModePlainUdp,
                    badges = listOf(uiState.dns.dnsIp),
                    onClick = { onModeSelected(DnsModePlainUdp) },
                )
            }
        }

        AnimatedVisibility(
            visible = uiState.dns.dnsMode == DnsModeEncrypted,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.dns_protocol_section))
                Text(
                    text = stringResource(R.string.dns_protocol_helper),
                    style = type.body,
                    color = colors.mutedForeground,
                )
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    DnsOptionCard(
                        icon = RipDpiIcons.Lock,
                        title = stringResource(R.string.dns_protocol_doh),
                        body = stringResource(R.string.dns_protocol_doh_body),
                        selected = uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh,
                        badges = listOf(stringResource(R.string.dns_protocol_builtin_and_custom)),
                        onClick = { onProtocolSelected(EncryptedDnsProtocolDoh) },
                    )
                    DnsOptionCard(
                        icon = RipDpiIcons.Lock,
                        title = stringResource(R.string.dns_protocol_dot),
                        body = stringResource(R.string.dns_protocol_dot_body),
                        selected = uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDot,
                        badges = listOf(stringResource(R.string.dns_protocol_custom_only)),
                        onClick = { onProtocolSelected(EncryptedDnsProtocolDot) },
                    )
                    DnsOptionCard(
                        icon = RipDpiIcons.Vpn,
                        title = stringResource(R.string.dns_protocol_dnscrypt),
                        body = stringResource(R.string.dns_protocol_dnscrypt_body),
                        selected = uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt,
                        badges = listOf(stringResource(R.string.dns_protocol_custom_only)),
                        onClick = { onProtocolSelected(EncryptedDnsProtocolDnsCrypt) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible =
                uiState.dns.dnsMode == DnsModeEncrypted &&
                    uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh,
            enter = fadeIn(),
            exit = fadeOut(),
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

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.dns_custom_section))
            Text(
                text = stringResource(R.string.dns_custom_section_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            RipDpiCard(modifier = Modifier.animateContentSize()) {
                when (uiState.dns.dnsMode) {
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
                            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DnsPlainSave),
                            trailingIcon = RipDpiIcons.Check,
                        )
                    }

                    DnsModeEncrypted -> {
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
private fun DnsActiveConfigurationCard(
    uiState: SettingsUiState,
    selectedResolver: DnsResolverOption?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val title =
        selectedResolver?.let { stringResource(it.titleRes) }
            ?: if (uiState.dns.dnsMode == DnsModeEncrypted) {
                stringResource(R.string.dns_custom_title)
            } else {
                stringResource(R.string.dns_mode_plain)
            }
    val endpointSummary =
        remember(
            uiState.dns.dnsMode,
            uiState.dns.encryptedDnsProtocol,
            uiState.dns.encryptedDnsDohUrl,
            uiState.dns.encryptedDnsHost,
            uiState.dns.encryptedDnsPort,
            uiState.dns.dnsIp,
        ) {
            activeEndpointSummary(uiState)
        }
    val bootstrapSummary =
        remember(uiState.dns.dnsMode, uiState.dns.encryptedDnsBootstrapIps) {
            if (uiState.dns.dnsMode == DnsModeEncrypted) {
                formatBootstrapPreview(uiState.dns.encryptedDnsBootstrapIps)
            } else {
                ""
            }
        }

    RipDpiCard(
        variant = if (uiState.dns.dnsMode == DnsModeEncrypted) RipDpiCardVariant.Elevated else RipDpiCardVariant.Tonal,
        modifier = Modifier.animateContentSize(),
    ) {
        Text(
            text = stringResource(R.string.dns_active_section_title),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(
                            if (uiState.dns.dnsMode == DnsModeEncrypted) {
                                colors.infoContainer
                            } else {
                                colors.inputBackground
                            },
                            RipDpiThemeTokens.shapes.full,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (uiState.dns.dnsMode == DnsModeEncrypted) RipDpiIcons.Lock else RipDpiIcons.Dns,
                    contentDescription = null,
                    tint =
                        if (uiState.dns.dnsMode == DnsModeEncrypted) {
                            colors.infoContainerForeground
                        } else {
                            colors.foreground
                        },
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = title,
                    style = type.screenTitle,
                    color = colors.foreground,
                )
                Text(
                    text = uiState.dns.dnsSummary,
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            DnsBadge(
                text =
                    if (uiState.dns.dnsMode ==
                        DnsModeEncrypted
                    ) {
                        stringResource(R.string.dns_mode_doh)
                    } else {
                        stringResource(
                            R.string.dns_mode_plain,
                        )
                    },
            )
            if (uiState.dns.dnsMode == DnsModeEncrypted) {
                DnsBadge(text = protocolDisplayName(uiState.dns.encryptedDnsProtocol))
            }
            DnsBadge(
                text =
                    if (selectedResolver != null || uiState.dns.dnsMode == DnsModePlainUdp) {
                        stringResource(R.string.dns_selected_badge)
                    } else {
                        stringResource(R.string.dns_resolver_custom_active)
                    },
                highlighted = true,
            )
        }
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_endpoint),
            value = endpointSummary,
        )
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_bootstrap),
            value =
                if (uiState.dns.dnsMode == DnsModeEncrypted) {
                    bootstrapSummary
                } else {
                    stringResource(R.string.dns_active_bootstrap_not_required)
                },
            monospace = uiState.dns.dnsMode == DnsModeEncrypted,
        )
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_route),
            value =
                when {
                    !uiState.isVpn -> stringResource(R.string.dns_active_route_saved)
                    uiState.dns.dnsMode == DnsModeEncrypted -> stringResource(R.string.dns_active_route_encrypted_vpn)
                    else -> stringResource(R.string.dns_active_route_plain_vpn)
                },
            monospace = false,
        )
    }
}

@Composable
private fun DnsOptionCard(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    badges: List<String>,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        variant = if (selected) RipDpiCardVariant.Elevated else RipDpiCardVariant.Outlined,
        onClick = onClick,
        modifier = Modifier.animateContentSize(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .background(
                            if (selected) colors.infoContainer else colors.inputBackground,
                            RipDpiThemeTokens.shapes.full,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) colors.infoContainerForeground else colors.mutedForeground,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = title,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = body,
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
            if (selected) {
                DnsBadge(
                    text = stringResource(R.string.dns_selected_badge),
                    highlighted = true,
                )
            }
        }
        if (badges.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                badges.forEach { badge ->
                    DnsBadge(text = badge)
                }
            }
        }
    }
}

@Composable
private fun DnsBadge(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Box(
        modifier =
            modifier
                .background(
                    if (highlighted) colors.infoContainer else colors.inputBackground,
                    RipDpiThemeTokens.shapes.full,
                ).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.smallLabel,
            color = if (highlighted) colors.infoContainerForeground else colors.mutedForeground,
        )
    }
}

@Composable
private fun DnsDetailRow(
    label: String,
    value: String,
    monospace: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = label,
            style = type.smallLabel,
            color = colors.mutedForeground,
        )
        Text(
            text = value,
            style = if (monospace) type.monoSmall else type.body,
            color = colors.foreground,
        )
    }
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
    when (uiState.dns.encryptedDnsProtocol) {
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
                bootstrapError =
                    if (bootstrapInput.isNotBlank() &&
                        !bootstrapIpsValid
                    ) {
                        stringResource(R.string.dns_custom_bootstrap_error)
                    } else {
                        null
                    },
            )
            RipDpiTextField(
                value = tlsServerNameInput,
                onValueChange = onTlsServerNameChange,
                testTag = RipDpiTestTags.DnsCustomTlsServerName,
                label = stringResource(R.string.dns_custom_tls_server_name_label),
                placeholder = stringResource(R.string.dns_placeholder_resolver_example),
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
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DnsCustomSave),
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
                bootstrapError =
                    if (bootstrapInput.isNotBlank() &&
                        !bootstrapIpsValid
                    ) {
                        stringResource(R.string.dns_custom_bootstrap_error)
                    } else {
                        null
                    },
            )
            RipDpiTextField(
                value = dnscryptProviderInput,
                onValueChange = onDnscryptProviderChange,
                testTag = RipDpiTestTags.DnsCustomDnsCryptProvider,
                label = stringResource(R.string.dns_custom_dnscrypt_provider_label),
                placeholder = stringResource(R.string.dns_placeholder_dnscrypt_cert),
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
                testTag = RipDpiTestTags.DnsCustomDnsCryptPublicKey,
                label = stringResource(R.string.dns_custom_dnscrypt_public_key_label),
                placeholder = stringResource(R.string.dns_placeholder_public_key),
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
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DnsCustomSave),
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
                testTag = RipDpiTestTags.DnsCustomDohUrl,
                label = stringResource(R.string.dns_custom_doh_url_label),
                placeholder = stringResource(R.string.dns_placeholder_doh_url),
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
                testTag = RipDpiTestTags.DnsCustomBootstrap,
                label = stringResource(R.string.dns_custom_bootstrap_label),
                placeholder = stringResource(R.string.dns_placeholder_bootstrap_ips),
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
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DnsCustomSave),
                variant =
                    if (uiState.dns.dnsProviderId == DnsProviderCustom) {
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
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        RipDpiTextField(
            value = host,
            onValueChange = onHostChange,
            modifier = Modifier.weight(1f),
            testTag = RipDpiTestTags.DnsCustomHost,
            label = hostLabel,
            placeholder = stringResource(R.string.dns_placeholder_resolver_example),
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
            modifier = Modifier.weight(0.4f),
            testTag = RipDpiTestTags.DnsCustomPort,
            label = stringResource(R.string.dns_custom_port_label),
            placeholder = stringResource(R.string.dns_placeholder_port),
            helperText = stringResource(R.string.dns_custom_port_helper),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
        )
    }
    RipDpiTextField(
        value = bootstrapInput,
        onValueChange = onBootstrapInputChange,
        testTag = RipDpiTestTags.DnsCustomBootstrap,
        label = stringResource(R.string.dns_custom_bootstrap_label),
        placeholder = stringResource(R.string.dns_placeholder_bootstrap_ips),
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
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    RipDpiCard(
        variant = if (selected) RipDpiCardVariant.Elevated else RipDpiCardVariant.Outlined,
        onClick = onClick,
        modifier =
            Modifier
                .animateContentSize()
                .ripDpiTestTag(RipDpiTestTags.dnsResolver(resolver.providerId)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = stringResource(resolver.titleRes),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = stringResource(resolver.descriptionRes),
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
            if (selected) {
                DnsBadge(
                    text = stringResource(R.string.dns_selected_badge),
                    highlighted = true,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            DnsBadge(text = resolver.address)
            DnsBadge(text = protocolDisplayName(resolver.protocol))
        }
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_endpoint),
            value = resolver.dohUrl,
        )
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_bootstrap),
            value = formatBootstrapPreview(resolver.bootstrapIps),
        )
    }
}

private fun parseBootstrapIps(value: String): List<String> = normalizeDnsBootstrapIps(listOf(value))

private fun activeEndpointSummary(uiState: SettingsUiState): String =
    when {
        uiState.dns.dnsMode != DnsModeEncrypted -> {
            uiState.dns.dnsIp
        }

        uiState.dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh -> {
            uiState.dns.encryptedDnsDohUrl.ifBlank {
                "${uiState.dns.encryptedDnsHost}:${uiState.dns.encryptedDnsPort}"
            }
        }

        else -> {
            "${uiState.dns.encryptedDnsHost}:${uiState.dns.encryptedDnsPort}"
        }
    }

private fun formatBootstrapPreview(values: List<String>): String =
    when {
        values.isEmpty() -> ""
        values.size <= 2 -> values.joinToString(" · ")
        else -> values.take(2).joinToString(" · ") + " · +${values.size - 2}"
    }

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
                    dns =
                        DnsUiState(
                            dnsMode = DnsModeEncrypted,
                            dnsProviderId = DnsProviderCloudflare,
                            dnsSummary = "Encrypted DNS · Cloudflare (DoH)",
                            encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                            encryptedDnsHost = "cloudflare-dns.com",
                            encryptedDnsPort = 443,
                            encryptedDnsTlsServerName = "cloudflare-dns.com",
                            encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                            encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
                        ),
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
                    dns =
                        DnsUiState(
                            dnsMode = DnsModePlainUdp,
                            dnsProviderId = DnsProviderCustom,
                            dnsIp = "9.9.9.9",
                            dnsSummary = "Plain DNS · 9.9.9.9",
                        ),
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
