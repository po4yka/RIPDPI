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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.data.DnsModeDoh
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
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
    val address: String,
    val dohUrl: String,
    val bootstrapIps: List<String>,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
)

private val resolverOptions =
    listOf(
        DnsResolverOption(
            providerId = DnsProviderCloudflare,
            address = "1.1.1.1",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            titleRes = R.string.dns_resolver_cloudflare_title,
            descriptionRes = R.string.dns_resolver_cloudflare_body,
        ),
        DnsResolverOption(
            providerId = "google",
            address = "8.8.8.8",
            dohUrl = "https://dns.google/dns-query",
            bootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
            titleRes = R.string.dns_resolver_google_title,
            descriptionRes = R.string.dns_resolver_google_body,
        ),
        DnsResolverOption(
            providerId = "quad9",
            address = "9.9.9.9",
            dohUrl = "https://dns.quad9.net/dns-query",
            bootstrapIps = listOf("9.9.9.9", "149.112.112.112"),
            titleRes = R.string.dns_resolver_quad9_title,
            descriptionRes = R.string.dns_resolver_quad9_body,
        ),
        DnsResolverOption(
            providerId = "adguard",
            address = "94.140.14.14",
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
            if (mode == DnsModeDoh) {
                val providerId =
                    resolverOptions.firstOrNull { it.providerId == uiState.dnsProviderId }?.providerId
                        ?: DnsProviderCloudflare
                viewModel.selectBuiltInDnsProvider(providerId)
            } else {
                viewModel.setPlainDnsServer(uiState.dnsIp)
            }
        },
        onResolverSelected = { resolver ->
            viewModel.selectBuiltInDnsProvider(resolver.providerId)
        },
        onSaveCustomDoh = { dohUrl, bootstrapIps ->
            viewModel.setCustomDohResolver(dohUrl, bootstrapIps)
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
    onResolverSelected: (DnsResolverOption) -> Unit,
    onSaveCustomDoh: (String, List<String>) -> Unit,
    onSavePlainDns: (String) -> Unit,
    onIpv6Changed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val activeDns = uiState.dnsIp.trim().ifEmpty { "1.1.1.1" }
    val selectedResolver =
        remember(uiState.dnsProviderId) {
            resolverOptions.firstOrNull { it.providerId == uiState.dnsProviderId }
        }
    var customDnsInput by rememberSaveable(activeDns, uiState.dnsMode) {
        mutableStateOf(if (uiState.dnsMode == DnsModePlainUdp) activeDns else "")
    }
    var customDohUrl by rememberSaveable(uiState.dnsDohUrl, uiState.dnsMode) {
        mutableStateOf(if (uiState.dnsMode == DnsModeDoh) uiState.dnsDohUrl else "")
    }
    var customBootstrapInput by rememberSaveable(uiState.dnsDohBootstrapIps, uiState.dnsMode) {
        mutableStateOf(
            if (uiState.dnsMode == DnsModeDoh) {
                uiState.dnsDohBootstrapIps.joinToString(separator = ", ")
            } else {
                ""
            },
        )
    }
    val trimmedPlainDns = customDnsInput.trim()
    val plainDnsValid = trimmedPlainDns.isNotEmpty() && checkNotLocalIp(trimmedPlainDns)
    val plainDnsDirty = trimmedPlainDns != activeDns || uiState.dnsMode != DnsModePlainUdp
    val plainDnsError =
        if (trimmedPlainDns.isNotEmpty() && !plainDnsValid) {
            stringResource(R.string.config_error_invalid_dns)
        } else {
            null
        }
    val trimmedCustomDohUrl = customDohUrl.trim()
    val normalizedBootstrapIps = parseBootstrapIps(customBootstrapInput)
    val bootstrapIpsValid = normalizedBootstrapIps.isNotEmpty() && normalizedBootstrapIps.all(::checkNotLocalIp)
    val customDohValid = isValidHttpsUrl(trimmedCustomDohUrl) && bootstrapIpsValid
    val customDohDirty =
        trimmedCustomDohUrl != uiState.dnsDohUrl ||
            normalizedBootstrapIps != uiState.dnsDohBootstrapIps ||
            uiState.dnsMode != DnsModeDoh ||
            uiState.dnsProviderId != "custom"
    val customDohError =
        when {
            trimmedCustomDohUrl.isNotEmpty() && !isValidHttpsUrl(trimmedCustomDohUrl) -> {
                stringResource(R.string.dns_custom_doh_url_error)
            }
            customBootstrapInput.isNotBlank() && !bootstrapIpsValid -> {
                stringResource(R.string.dns_custom_bootstrap_error)
            }
            else -> null
        }

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
            StatusIndicator(
                label =
                    if (selectedResolver == null) {
                        stringResource(R.string.dns_resolver_custom_active)
                    } else {
                        stringResource(R.string.dns_resolver_active)
                    },
                tone = StatusIndicatorTone.Active,
            )
            Text(
                text =
                    selectedResolver?.let { stringResource(it.titleRes) }
                        ?: stringResource(R.string.dns_custom_title),
                style = type.screenTitle,
                color = colors.foreground,
            )
            Text(
                text =
                    if (uiState.dnsMode == DnsModeDoh) {
                        uiState.dnsSummary
                    } else {
                        activeDns
                    },
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
                        onClick = { onModeSelected(DnsModeDoh) },
                        variant =
                            if (uiState.dnsMode == DnsModeDoh) {
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

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.dns_resolvers_section))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                resolverOptions.forEach { resolver ->
                    DnsResolverCard(
                        resolver = resolver,
                        selected = uiState.dnsMode == DnsModeDoh && resolver.providerId == uiState.dnsProviderId,
                        onClick = { onResolverSelected(resolver) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.dns_custom_section))
            RipDpiCard {
                Text(
                    text =
                        if (uiState.dnsMode == DnsModeDoh) {
                            stringResource(R.string.dns_custom_doh_title)
                        } else {
                            stringResource(R.string.dns_custom_title)
                        },
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text =
                        if (uiState.dnsMode == DnsModeDoh) {
                            stringResource(R.string.dns_custom_doh_body)
                        } else {
                            stringResource(R.string.dns_custom_body)
                        },
                    style = type.body,
                    color = colors.mutedForeground,
                )
                if (uiState.dnsMode == DnsModeDoh) {
                    RipDpiTextField(
                        value = customDohUrl,
                        onValueChange = { customDohUrl = it },
                        label = stringResource(R.string.dns_custom_doh_url_label),
                        placeholder = "https://resolver.example/dns-query",
                        helperText = stringResource(R.string.dns_custom_doh_url_helper),
                        errorText = customDohError,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                    )
                    RipDpiTextField(
                        value = customBootstrapInput,
                        onValueChange = { customBootstrapInput = it },
                        label = stringResource(R.string.dns_custom_bootstrap_label),
                        placeholder = "1.1.1.1, 1.0.0.1",
                        helperText = stringResource(R.string.dns_custom_bootstrap_helper),
                        errorText = customDohError,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    if (customDohValid && customDohDirty) {
                                        onSaveCustomDoh(trimmedCustomDohUrl, normalizedBootstrapIps)
                                    }
                                },
                            ),
                    )
                    RipDpiButton(
                        text = stringResource(R.string.config_save),
                        onClick = { onSaveCustomDoh(trimmedCustomDohUrl, normalizedBootstrapIps) },
                        enabled = customDohValid && customDohDirty,
                        variant =
                            if (uiState.dnsProviderId == DnsProviderCustom) {
                                RipDpiButtonVariant.Primary
                            } else {
                                RipDpiButtonVariant.Outline
                            },
                        trailingIcon = RipDpiIcons.Check,
                    )
                } else {
                    RipDpiTextField(
                        value = customDnsInput,
                        onValueChange = { customDnsInput = it },
                        label = stringResource(R.string.dbs_ip_setting),
                        placeholder = stringResource(R.string.config_placeholder_dns),
                        helperText =
                            if (uiState.isVpn) {
                                stringResource(R.string.config_dns_helper)
                            } else {
                                stringResource(R.string.config_dns_disabled_helper)
                            },
                        errorText = plainDnsError,
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
                        variant = RipDpiButtonVariant.Primary,
                        trailingIcon = RipDpiIcons.Check,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.config_network_section))
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.ipv6_setting),
                    subtitle = stringResource(R.string.dns_ipv6_helper),
                    checked = uiState.ipv6Enable,
                    onCheckedChange = onIpv6Changed,
                )
            }
        }
    }
}

private fun parseBootstrapIps(input: String): List<String> =
    input
        .split(',', '\n', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private fun isValidHttpsUrl(value: String): Boolean =
    runCatching {
        val parsed = URI(value)
        parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
    }.getOrDefault(false)

@Composable
private fun DnsResolverCard(
    resolver: DnsResolverOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(
        modifier = modifier,
        variant = if (selected) RipDpiCardVariant.Tonal else RipDpiCardVariant.Outlined,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                StatusIndicator(
                    label = stringResource(R.string.dns_resolver_active),
                    tone = StatusIndicatorTone.Active,
                )
            }
        }
        Text(
            text = resolver.address,
            style = type.monoValue,
            color = if (selected) colors.foreground else colors.mutedForeground,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DnsSettingsScreenPreview() {
    RipDpiTheme {
        DnsSettingsScreen(
            uiState =
                SettingsUiState(
                    dnsIp = "8.8.8.8",
                    dnsMode = DnsModeDoh,
                    dnsProviderId = "google",
                    dnsSummary = "DoH · Google Public DNS",
                    ipv6Enable = true,
                    isVpn = true,
                    selectedMode = Mode.VPN,
                ),
            onBack = {},
            onModeSelected = {},
            onResolverSelected = {},
            onSaveCustomDoh = { _, _ -> },
            onSavePlainDns = {},
            onIpv6Changed = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DnsSettingsScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        DnsSettingsScreen(
            uiState =
                SettingsUiState(
                    ripdpiMode = "proxy",
                    dnsIp = "76.76.2.0",
                    dnsMode = DnsModePlainUdp,
                    dnsProviderId = DnsProviderCustom,
                    dnsSummary = "Plain DNS · 76.76.2.0",
                    ipv6Enable = false,
                    isVpn = false,
                    selectedMode = Mode.Proxy,
                ),
            onBack = {},
            onModeSelected = {},
            onResolverSelected = {},
            onSaveCustomDoh = { _, _ -> },
            onSavePlainDns = {},
            onIpv6Changed = {},
        )
    }
}
