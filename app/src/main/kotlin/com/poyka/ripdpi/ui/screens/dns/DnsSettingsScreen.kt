package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScaffoldWidth
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
    val input = rememberDnsSettingsInputState(uiState.dns)
    val validation = rememberDnsSettingsValidation(uiState.dns, input)
    val selectedResolver = selectedResolverOption(uiState.dns)
    val colors = RipDpiThemeTokens.colors

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
        DnsProxyModeWarning(uiState)
        DnsActiveConfigurationCard(uiState = uiState, selectedResolver = selectedResolver)
        DnsModeSection(uiState = uiState, onModeSelected = onModeSelected)
        DnsProtocolSection(uiState = uiState, onProtocolSelected = onProtocolSelected)
        DnsResolverCatalogSection(uiState = uiState, onResolverSelected = onResolverSelected)
        DnsCustomResolverSettingsSection(
            uiState = uiState,
            input = input,
            validation = validation,
            onSaveCustomDoh = onSaveCustomDoh,
            onSaveCustomDot = onSaveCustomDot,
            onSaveCustomDnsCrypt = onSaveCustomDnsCrypt,
            onSavePlainDns = onSavePlainDns,
        )
        DnsIpv6Section(ipv6Enable = uiState.ipv6Enable, onIpv6Changed = onIpv6Changed)
    }
}

@Composable
private fun DnsProxyModeWarning(uiState: SettingsUiState) {
    if (!uiState.isVpn) {
        WarningBanner(
            title = stringResource(R.string.dns_proxy_banner_title),
            message = stringResource(R.string.dns_proxy_banner_body),
            tone = WarningBannerTone.Restricted,
        )
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
