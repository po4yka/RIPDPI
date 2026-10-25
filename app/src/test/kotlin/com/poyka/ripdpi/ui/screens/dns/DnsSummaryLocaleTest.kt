package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU")
class DnsSummaryLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun builtInResolverSummaryUsesRussianModeAndKeepsProviderName() {
        render(DnsUiState(dnsProviderId = "cloudflare"))
        composeRule.onNodeWithText("Зашифрованный DNS · Cloudflare (DoH)").assertExists()
    }

    @Test
    fun customResolverSummaryUsesRussianNameAndKeepsProtocol() {
        render(DnsUiState(dnsProviderId = DnsProviderCustom, encryptedDnsProtocol = EncryptedDnsProtocolDot))
        composeRule.onNodeWithText("Зашифрованный DNS · Пользовательский DNS (DoT)").assertExists()
    }

    @Test
    fun plainResolverSummaryUsesRussianModeAndKeepsAddress() {
        render(DnsUiState(dnsMode = DnsModePlainUdp, dnsIp = "9.9.9.9"))
        composeRule.onNodeWithText("Обычный DNS · 9.9.9.9").assertExists()
    }

    @Test
    fun connectionTabSummaryUsesTheSameRussianText() {
        val draft = ConfigDraft(dnsMode = DnsModePlainUdp, dnsIp = "9.9.9.9")
        composeRule.setContent { RipDpiTheme { Text(draft.localizedDnsSummary()) } }
        composeRule.onNodeWithText("Обычный DNS · 9.9.9.9").assertExists()
    }

    private fun render(dns: DnsUiState) {
        composeRule.setContent {
            // The summary feeds the Settings DNS row; the active resolver card no longer repeats it.
            RipDpiTheme { Text(dns.localizedSummary()) }
        }
    }
}
