package com.poyka.ripdpi.ui.screens.dns

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.net.URI

private const val dnsPortMax = 65535
internal const val dnsPortWeightFraction = 0.4f

private val DnsCryptPublicKeyPattern = Regex("^[0-9a-fA-F]{64}$")

internal data class DnsResolverOption(
    val providerId: String,
    val protocol: String,
    val address: String,
    val host: String,
    val port: Int,
    val tlsServerName: String,
    val dohUrl: String,
    val bootstrapIps: ImmutableList<String>,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
)

internal val resolverOptions =
    listOf(
        DnsResolverOption(
            providerId = DnsProviderCloudflare,
            protocol = EncryptedDnsProtocolDoh,
            address = "1.1.1.1",
            host = "cloudflare-dns.com",
            port = 443,
            tlsServerName = "cloudflare-dns.com",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            bootstrapIps = persistentListOf("1.1.1.1", "1.0.0.1"),
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
            bootstrapIps = persistentListOf("8.8.8.8", "8.8.4.4"),
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
            bootstrapIps = persistentListOf("9.9.9.9", "149.112.112.112"),
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
            bootstrapIps = persistentListOf("94.140.14.14", "94.140.15.15"),
            titleRes = R.string.dns_resolver_adguard_title,
            descriptionRes = R.string.dns_resolver_adguard_body,
        ),
    )

@Suppress("LongMethod", "CyclomaticComplexMethod")
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
    val motion = RipDpiThemeTokens.motion
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
            dotPort in 1..dnsPortMax &&
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
            dnscryptPort in 1..dnsPortMax &&
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

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.dns_custom_section))
            Text(
                text = stringResource(R.string.dns_custom_section_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            RipDpiCard(
                modifier =
                    Modifier.animateContentSize(
                        animationSpec = motion.stateTween(),
                    ),
            ) {
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
                                        if (plainDnsInput.isNotBlank() && !plainDnsValid) {
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
                                                if (plainDnsValid && plainDnsDirty) {
                                                    onSavePlainDns(trimmedPlainDns)
                                                }
                                            },
                                        ),
                                ),
                        )
                        RipDpiButton(
                            text = stringResource(R.string.config_save),
                            onClick = { onSavePlainDns(trimmedPlainDns) },
                            enabled = plainDnsValid && plainDnsDirty,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .ripDpiTestTag(RipDpiTestTags.DnsPlainSave),
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

@Preview(showBackground = true)
@Composable
@Suppress("UnusedPrivateMember")
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
@Suppress("UnusedPrivateMember")
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
