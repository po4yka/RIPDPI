package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
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
                            dnsMode = DnsModeEncrypted,
                            dnsProviderId = DnsProviderCloudflare,
                            encryptedDnsProtocol = EncryptedDnsProtocolDoh,
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

        composeRule.onNodeWithText("Google Public DNS").fetchSemanticsNode()
    }

    @Test
    fun customDotFormEnablesSaveOnlyWhenValid() {
        composeRule.setContent {
            RipDpiTheme {
                DnsSettingsScreen(
                    uiState =
                        SettingsUiState(
                            dnsMode = DnsModeEncrypted,
                            dnsProviderId = DnsProviderCustom,
                            encryptedDnsProtocol = EncryptedDnsProtocolDot,
                            encryptedDnsHost = "",
                            encryptedDnsPort = 0,
                            encryptedDnsTlsServerName = "",
                            encryptedDnsBootstrapIps = emptyList(),
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

        composeRule.onNodeWithText("Save").assertIsNotEnabled()

        composeRule.onNodeWithContentDescription("Host").performTextInput("resolver.example")
        composeRule.onNodeWithContentDescription("Port").performTextInput("853")
        composeRule.onNodeWithContentDescription("Bootstrap IPs").performTextInput("1.1.1.1, 1.0.0.1")
        composeRule.onNodeWithContentDescription("TLS server name").performTextInput("resolver.example")

        composeRule.onNodeWithTag("dns-custom-save").assertIsEnabled()
    }
}
