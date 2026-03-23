package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DnsSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun builtInResolverCardIsRendered() {
        composeRule.setContent {
            RipDpiTheme {
                DnsSettingsScreen(
                    uiState =
                        SettingsUiState(
                            dns =
                                DnsUiState(
                                    dnsMode = DnsModeEncrypted,
                                    dnsProviderId = DnsProviderCloudflare,
                                    encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                                ),
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

        composeRule.onNodeWithTag(RipDpiTestTags.dnsResolver("google")).fetchSemanticsNode()
    }

    @Test
    fun customDotFormEnablesSaveOnlyWhenValid() {
        composeRule.setContent {
            RipDpiTheme {
                DnsSettingsScreen(
                    uiState =
                        SettingsUiState(
                            dns =
                                DnsUiState(
                                    dnsMode = DnsModeEncrypted,
                                    dnsProviderId = DnsProviderCustom,
                                    encryptedDnsProtocol = EncryptedDnsProtocolDot,
                                    encryptedDnsHost = "",
                                    encryptedDnsPort = 0,
                                    encryptedDnsTlsServerName = "",
                                    encryptedDnsBootstrapIps = emptyList(),
                                ),
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

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsNotEnabled()

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomHost).performTextInput("resolver.example")
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomPort).performTextInput("853")
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomBootstrap).performTextInput("1.1.1.1, 1.0.0.1")
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomTlsServerName).performTextInput("resolver.example")

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsEnabled()
    }
}
