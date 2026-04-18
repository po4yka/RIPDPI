package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DnsSettingsCustomResolverTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun parseBootstrapIpsNormalizesCommaSeparatedList() {
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), parseBootstrapIps(" 1.1.1.1, 8.8.8.8 "))
    }

    @Test
    fun formatBootstrapPreviewCollapsesLongLists() {
        assertEquals("1.1.1.1 · 8.8.8.8 · +1", formatBootstrapPreview(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")))
    }

    @Test
    fun activeEndpointSummaryPrefersDohUrlAndFallsBackToHostPort() {
        val dohState =
            SettingsUiState(
                dns =
                    DnsUiState(
                        dnsMode = DnsModeEncrypted,
                        encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                        encryptedDnsDohUrl = "https://dns.example/dns-query",
                        encryptedDnsHost = "dns.example",
                        encryptedDnsPort = 443,
                    ),
            )
        val dotFallbackState =
            SettingsUiState(
                dns =
                    DnsUiState(
                        dnsMode = DnsModeEncrypted,
                        encryptedDnsProtocol = EncryptedDnsProtocolDot,
                        encryptedDnsHost = "resolver.example",
                        encryptedDnsPort = 853,
                    ),
            )

        assertEquals("https://dns.example/dns-query", activeEndpointSummary(dohState))
        assertEquals("resolver.example:853", activeEndpointSummary(dotFallbackState))
    }

    @Test
    fun isValidHttpsUrlRejectsNonHttpsAndMissingHost() {
        assertTrue(isValidHttpsUrl("https://dns.example/dns-query"))
        assertFalse(isValidHttpsUrl("http://dns.example/dns-query"))
        assertFalse(isValidHttpsUrl("https:///dns-query"))
    }

    @Test
    fun customDohSectionEnablesSaveOnlyWhenEndpointAndBootstrapAreValid() {
        composeRule.setContent {
            RipDpiTheme {
                CustomEncryptedDnsSection(
                    uiState =
                        SettingsUiState(
                            dns =
                                DnsUiState(
                                    dnsMode = DnsModeEncrypted,
                                    encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                                ),
                        ),
                    dohUrl = "",
                    onDohUrlChange = {},
                    dotHost = "",
                    onDotHostChange = {},
                    dnscryptHost = "",
                    onDnscryptHostChange = {},
                    portInput = "",
                    onPortInputChange = {},
                    tlsServerNameInput = "",
                    onTlsServerNameChange = {},
                    bootstrapInput = "",
                    onBootstrapInputChange = {},
                    dnscryptProviderInput = "",
                    onDnscryptProviderChange = {},
                    dnscryptPublicKeyInput = "",
                    onDnscryptPublicKeyChange = {},
                    customDohValid = false,
                    customDohDirty = false,
                    customDotValid = false,
                    customDotDirty = false,
                    customDnsCryptValid = false,
                    customDnsCryptDirty = false,
                    dnscryptPublicKeyValid = false,
                    bootstrapIpsValid = true,
                    onSaveCustomDoh = {},
                    onSaveCustomDot = {},
                    onSaveCustomDnsCrypt = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsNotEnabled()
    }

    @Test
    fun customDnsCryptSectionEnablesSaveWhenDedicatedFieldsAreValid() {
        composeRule.setContent {
            RipDpiTheme {
                CustomEncryptedDnsSection(
                    uiState =
                        SettingsUiState(
                            dns =
                                DnsUiState(
                                    dnsMode = DnsModeEncrypted,
                                    encryptedDnsProtocol = EncryptedDnsProtocolDnsCrypt,
                                ),
                        ),
                    dohUrl = "",
                    onDohUrlChange = {},
                    dotHost = "",
                    onDotHostChange = {},
                    dnscryptHost = "",
                    onDnscryptHostChange = {},
                    portInput = "",
                    onPortInputChange = {},
                    tlsServerNameInput = "",
                    onTlsServerNameChange = {},
                    bootstrapInput = "",
                    onBootstrapInputChange = {},
                    dnscryptProviderInput = "",
                    onDnscryptProviderChange = {},
                    dnscryptPublicKeyInput = "",
                    onDnscryptPublicKeyChange = {},
                    customDohValid = false,
                    customDohDirty = false,
                    customDotValid = false,
                    customDotDirty = false,
                    customDnsCryptValid = true,
                    customDnsCryptDirty = true,
                    dnscryptPublicKeyValid = true,
                    bootstrapIpsValid = true,
                    onSaveCustomDoh = {},
                    onSaveCustomDot = {},
                    onSaveCustomDnsCrypt = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsEnabled()
    }
}
