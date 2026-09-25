package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.OdohResolverFields
import com.poyka.ripdpi.activities.ValidOdohConfigsHex
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.EncryptedDnsProtocolOdoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
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
                    onSaveCustomDot = { _, _, _, _, _ -> },
                    onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
                    onSaveCustomOdoh = { _, _ -> },
                    onSavePlainDns = {},
                    onIpv6Changed = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.dnsResolver("google"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Google Public DNS"),
                ),
            )
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
                                    encryptedDnsBootstrapIps = persistentListOf(),
                                ),
                        ),
                    onBack = {},
                    onModeSelected = {},
                    onProtocolSelected = {},
                    onResolverSelected = {},
                    onSaveCustomDoh = { _, _ -> },
                    onSaveCustomDot = { _, _, _, _, _ -> },
                    onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
                    onSaveCustomOdoh = { _, _ -> },
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

    @Test
    fun savedDoqShowsTlsEditorButCannotReplaceRunningVpn() {
        var savedProtocol: String? = null
        composeRule.setContent {
            RipDpiTheme {
                DnsSettingsScreen(
                    uiState =
                        SettingsUiState(
                            selectedMode = Mode.Proxy,
                            activeMode = Mode.VPN,
                            serviceStatus = AppStatus.Running,
                            dns =
                                DnsUiState(
                                    dnsMode = DnsModeEncrypted,
                                    dnsProviderId = DnsProviderCustom,
                                    encryptedDnsProtocol = EncryptedDnsProtocolDoq,
                                    encryptedDnsHost = "quic.example",
                                    encryptedDnsPort = 853,
                                    encryptedDnsTlsServerName = "quic.example",
                                    encryptedDnsBootstrapIps = persistentListOf("1.1.1.1"),
                                ),
                        ),
                    onBack = {},
                    onModeSelected = {},
                    onProtocolSelected = {},
                    onResolverSelected = {},
                    onSaveCustomDoh = { _, _ -> },
                    onSaveCustomDot = { protocol, _, _, _, _ -> savedProtocol = protocol },
                    onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
                    onSaveCustomOdoh = { _, _ -> },
                    onSavePlainDns = {},
                    onIpv6Changed = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomDohUrl).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomHost).performTextReplacement("new.example")
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomTlsServerName).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(null, savedProtocol) }
    }

    @Test
    fun savedDoqCanBeEditedAndSavedInProxyMode() {
        var savedProtocol: String? = null
        composeRule.setContent {
            RipDpiTheme {
                DnsSettingsScreen(
                    uiState =
                        SettingsUiState(
                            selectedMode = Mode.Proxy,
                            dns =
                                DnsUiState(
                                    dnsMode = DnsModeEncrypted,
                                    dnsProviderId = DnsProviderCustom,
                                    encryptedDnsProtocol = EncryptedDnsProtocolDoq,
                                    encryptedDnsHost = "quic.example",
                                    encryptedDnsPort = 853,
                                    encryptedDnsTlsServerName = "quic.example",
                                    encryptedDnsBootstrapIps = persistentListOf("1.1.1.1"),
                                ),
                        ),
                    onBack = {},
                    onModeSelected = {},
                    onProtocolSelected = {},
                    onResolverSelected = {},
                    onSaveCustomDoh = { _, _ -> },
                    onSaveCustomDot = { protocol, _, _, _, _ -> savedProtocol = protocol },
                    onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
                    onSaveCustomOdoh = { _, _ -> },
                    onSavePlainDns = {},
                    onIpv6Changed = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomHost).performTextReplacement("new.example")
        composeRule
            .onNodeWithTag(RipDpiTestTags.DnsCustomSave)
            .assertIsEnabled()
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(EncryptedDnsProtocolDoq, savedProtocol) }
    }

    @Test
    fun selectingOdohAndDoqWhileRunningLeavesActiveResolverUnchanged() {
        var protocolCommits = 0
        var odohSaves = 0
        composeRule.setContent {
            RipDpiTheme {
                DnsSettingsScreen(
                    uiState = SettingsUiState(serviceStatus = AppStatus.Running),
                    onBack = {},
                    onModeSelected = {},
                    onProtocolSelected = { protocolCommits++ },
                    onResolverSelected = {},
                    onSaveCustomDoh = { _, _ -> },
                    onSaveCustomDot = { _, _, _, _, _ -> },
                    onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
                    onSaveCustomOdoh = { _, _ -> odohSaves++ },
                    onSavePlainDns = {},
                    onIpv6Changed = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.dnsProtocol(EncryptedDnsProtocolOdoh)).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsOdohTargetHost).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.dnsProtocol(EncryptedDnsProtocolDoq)).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomTlsServerName).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(0, protocolCommits)
            assertEquals(0, odohSaves)
        }
    }

    @Test
    fun savedOdohShowsTargetFieldsAndRequiresValidHex() {
        var saved: OdohResolverFields? = null
        val current =
            OdohResolverFields(
                proxyUrl = "https://proxy.example/dns-query",
                proxyOperatorId = "proxy",
                targetHost = "target.example",
                targetPath = "/dns-query",
                targetOperatorId = "target",
                configSource = "custom_bytes",
                configsHex = "0",
                configsRetrievedAtSecs = System.currentTimeMillis() / 1_000,
                configsTtlSecs = 86_400,
            )
        composeRule.setContent {
            RipDpiTheme {
                DnsSettingsScreen(
                    uiState =
                        SettingsUiState(
                            dns =
                                DnsUiState(
                                    dnsMode = DnsModeEncrypted,
                                    dnsProviderId = DnsProviderCustom,
                                    encryptedDnsProtocol = EncryptedDnsProtocolOdoh,
                                    encryptedDnsBootstrapIps = persistentListOf("1.1.1.1"),
                                    odoh = current,
                                ),
                        ),
                    onBack = {},
                    onModeSelected = {},
                    onProtocolSelected = {},
                    onResolverSelected = {},
                    onSaveCustomDoh = { _, _ -> },
                    onSaveCustomDot = { _, _, _, _, _ -> },
                    onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
                    onSaveCustomOdoh = { fields, _ -> saved = fields },
                    onSavePlainDns = {},
                    onIpv6Changed = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomDohUrl).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsOdohTargetHost).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsOdohConfigsHex).performTextReplacement(ValidOdohConfigsHex)
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(ValidOdohConfigsHex, saved?.configsHex) }
    }

    @Test
    fun odohBootstrapOnlyEditCanBeSaved() {
        var savedBootstrap: List<String>? = null
        composeRule.setContent {
            RipDpiTheme {
                DnsSettingsScreen(
                    uiState =
                        SettingsUiState(
                            dns =
                                DnsUiState(
                                    dnsMode = DnsModeEncrypted,
                                    dnsProviderId = DnsProviderCustom,
                                    encryptedDnsProtocol = EncryptedDnsProtocolOdoh,
                                    encryptedDnsBootstrapIps = persistentListOf("1.1.1.1"),
                                    odoh =
                                        OdohResolverFields(
                                            proxyUrl = "https://proxy.example/dns-query",
                                            proxyOperatorId = "proxy",
                                            targetHost = "target.example",
                                            targetPath = "/dns-query",
                                            targetOperatorId = "target",
                                            configSource = "custom_bytes",
                                            configsHex = ValidOdohConfigsHex,
                                            configsRetrievedAtSecs = System.currentTimeMillis() / 1_000,
                                            configsTtlSecs = 86_400,
                                        ),
                                ),
                        ),
                    onBack = {},
                    onModeSelected = {},
                    onProtocolSelected = {},
                    onResolverSelected = {},
                    onSaveCustomDoh = { _, _ -> },
                    onSaveCustomDot = { _, _, _, _, _ -> },
                    onSaveCustomDnsCrypt = { _, _, _, _, _ -> },
                    onSaveCustomOdoh = { _, bootstrap -> savedBootstrap = bootstrap },
                    onSavePlainDns = {},
                    onIpv6Changed = {},
                )
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomSave).assertIsNotEnabled()
        composeRule.onNodeWithTag(RipDpiTestTags.DnsCustomBootstrap).performTextReplacement("8.8.8.8")
        composeRule
            .onNodeWithTag(RipDpiTestTags.DnsCustomSave)
            .assertIsEnabled()
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf("8.8.8.8"), savedBootstrap) }
    }
}
