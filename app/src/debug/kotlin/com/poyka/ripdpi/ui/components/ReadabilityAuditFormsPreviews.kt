package com.poyka.ripdpi.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.screens.config.MasqueAuthModeRow
import com.poyka.ripdpi.ui.screens.config.MasqueCloudflareMtlsFields
import com.poyka.ripdpi.ui.screens.config.RelayMasqueActions
import com.poyka.ripdpi.ui.screens.config.RelayVlessActions
import com.poyka.ripdpi.ui.screens.config.VlessTransportFields
import com.poyka.ripdpi.ui.screens.dns.DnsOptionCard
import com.poyka.ripdpi.ui.screens.dns.DnsResolverCard
import com.poyka.ripdpi.ui.screens.dns.resolverOptions
import com.poyka.ripdpi.ui.screens.settings.RememberedNetworkCard
import com.poyka.ripdpi.ui.screens.settings.rememberedNetworksPreviewEntries
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionServerRow
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionServerStatus
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionServerUiState
import com.poyka.ripdpi.ui.theme.RipDpiIcons

@Preview(name = "DNS light", widthDp = 420, heightDp = 900, locale = "ru")
@Preview(name = "DNS dark", widthDp = 420, heightDp = 900, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DNS large font", widthDp = 320, heightDp = 1700, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditDnsPreview() {
    RipDpiComponentPreview(themePreference = formsReadabilityTheme()) {
        DnsOptionCard(
            icon = RipDpiIcons.Lock,
            title = stringResource(R.string.dns_mode_doh),
            body = stringResource(R.string.dns_mode_encrypted_body),
            selected = true,
            badges = listOf("DoH", "DNSCrypt", "DoT"),
            onClick = {},
        )
        DnsResolverCard(resolver = resolverOptions.first(), selected = true, onClick = {})
    }
}

@Preview(name = "Relay options light", widthDp = 420, heightDp = 900, locale = "ru")
@Preview(
    name = "Relay options dark",
    widthDp = 420,
    heightDp = 900,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Relay options large font", widthDp = 320, heightDp = 1700, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditRelayOptionsPreview() {
    RipDpiComponentPreview(themePreference = formsReadabilityTheme()) {
        RipDpiCard {
            SettingsCategoryHeader(title = stringResource(R.string.config_relay_kind_masque))
            MasqueAuthModeRow(
                draft = ConfigDraft(relayMasqueAuthMode = RelayMasqueAuthModeCloudflareMtls),
                uiState = ConfigUiState(supportsMasquePrivacyPass = true),
                actions = RelayMasqueActions(),
            )
            VlessTransportFields(
                draft = ConfigDraft(relayVlessTransport = RelayVlessTransportXhttp),
                actions = RelayVlessActions(),
            )
        }
    }
}

@Preview(name = "MASQUE import light", widthDp = 420, heightDp = 1200, locale = "ru")
@Preview(
    name = "MASQUE import dark",
    widthDp = 420,
    heightDp = 1200,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "MASQUE import large font", widthDp = 320, heightDp = 2200, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditMasqueImportPreview() {
    RipDpiComponentPreview(themePreference = formsReadabilityTheme()) {
        RipDpiCard {
            MasqueCloudflareMtlsFields(draft = ConfigDraft(), actions = RelayMasqueActions())
        }
    }
}

@Preview(name = "Connection facts light", widthDp = 420, heightDp = 900, locale = "ru")
@Preview(
    name = "Connection facts dark",
    widthDp = 420,
    heightDp = 900,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Connection facts large font", widthDp = 320, heightDp = 1700, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditConnectionFactsPreview() {
    RipDpiComponentPreview(themePreference = formsReadabilityTheme()) {
        SubscriptionServerRow(
            SubscriptionServerUiState(
                id = "preview",
                name = "backup-relay.example.net",
                endpoint = "backup-relay.example.net:443",
                status = SubscriptionServerStatus.Checking,
                positionLabel = "server 2/4",
                detail = "TLS 1.3 · 24 ms",
            ),
        )
        RememberedNetworkCard(entry = rememberedNetworksPreviewEntries().first(), onDelete = {})
    }
}

@Composable
private fun formsReadabilityTheme(): String = if (isSystemInDarkTheme()) "dark" else "light"
