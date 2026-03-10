package com.poyka.ripdpi.ui.screens.dns

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
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
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.checkNotLocalIp

internal data class DnsResolverOption(
    val address: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
)

private val resolverOptions =
    listOf(
        DnsResolverOption(
            address = "1.1.1.1",
            titleRes = R.string.dns_resolver_cloudflare_title,
            descriptionRes = R.string.dns_resolver_cloudflare_body,
        ),
        DnsResolverOption(
            address = "8.8.8.8",
            titleRes = R.string.dns_resolver_google_title,
            descriptionRes = R.string.dns_resolver_google_body,
        ),
        DnsResolverOption(
            address = "9.9.9.9",
            titleRes = R.string.dns_resolver_quad9_title,
            descriptionRes = R.string.dns_resolver_quad9_body,
        ),
        DnsResolverOption(
            address = "94.140.14.14",
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
        onResolverSelected = { resolver ->
            viewModel.updateSetting(
                key = "dnsIp",
                value = resolver.address,
            ) {
                setDnsIp(resolver.address)
            }
        },
        onSaveCustomDns = { dnsAddress ->
            viewModel.updateSetting(
                key = "dnsIp",
                value = dnsAddress,
            ) {
                setDnsIp(dnsAddress)
            }
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
    onResolverSelected: (DnsResolverOption) -> Unit,
    onSaveCustomDns: (String) -> Unit,
    onIpv6Changed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    val activeDns = uiState.dnsIp.trim().ifEmpty { "1.1.1.1" }
    val selectedResolver =
        remember(activeDns) {
            resolverOptions.firstOrNull { it.address == activeDns }
        }
    var customDnsInput by rememberSaveable(activeDns) {
        mutableStateOf(activeDns)
    }
    val trimmedCustomDns = customDnsInput.trim()
    val customDnsValid = trimmedCustomDns.isNotEmpty() && checkNotLocalIp(trimmedCustomDns)
    val customDnsDirty = trimmedCustomDns != activeDns
    val customDnsError =
        if (trimmedCustomDns.isNotEmpty() && !customDnsValid) {
            stringResource(R.string.config_error_invalid_dns)
        } else {
            null
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
    ) {
        RipDpiTopAppBar(
            title = stringResource(R.string.title_dns_settings),
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = onBack,
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = layout.horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
        ) {
            Spacer(modifier = Modifier.height(spacing.sm))

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
                    text = activeDns,
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
                SettingsCategoryHeader(title = stringResource(R.string.dns_resolvers_section))
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    resolverOptions.forEach { resolver ->
                        DnsResolverCard(
                            resolver = resolver,
                            selected = resolver.address == activeDns,
                            onClick = { onResolverSelected(resolver) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.dns_custom_section))
                RipDpiCard {
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
                        value = customDnsInput,
                        onValueChange = { customDnsInput = it },
                        label = stringResource(R.string.dbs_ip_setting),
                        placeholder = stringResource(R.string.config_placeholder_dns),
                        helperText =
                            if (selectedResolver == null && customDnsValid && !customDnsDirty) {
                                stringResource(R.string.dns_resolver_custom_active)
                            } else if (uiState.isVpn) {
                                stringResource(R.string.config_dns_helper)
                            } else {
                                stringResource(R.string.config_dns_disabled_helper)
                            },
                        errorText = customDnsError,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    if (customDnsValid && customDnsDirty) {
                                        onSaveCustomDns(trimmedCustomDns)
                                    }
                                },
                            ),
                    )
                    RipDpiButton(
                        text = stringResource(R.string.config_save),
                        onClick = { onSaveCustomDns(trimmedCustomDns) },
                        enabled = customDnsValid && customDnsDirty,
                        variant =
                            if (selectedResolver == null) {
                                RipDpiButtonVariant.Primary
                            } else {
                                RipDpiButtonVariant.Outline
                            },
                        trailingIcon = RipDpiIcons.Check,
                    )
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

            Spacer(modifier = Modifier.height(spacing.xxl))
        }
    }
}

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
        variant = if (selected) RipDpiCardVariant.Elevated else RipDpiCardVariant.Outlined,
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
                    ipv6Enable = true,
                    isVpn = true,
                    selectedMode = Mode.VPN,
                ),
            onBack = {},
            onResolverSelected = {},
            onSaveCustomDns = {},
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
                    ipv6Enable = false,
                    isVpn = false,
                    selectedMode = Mode.Proxy,
                ),
            onBack = {},
            onResolverSelected = {},
            onSaveCustomDns = {},
            onIpv6Changed = {},
        )
    }
}
