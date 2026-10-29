package com.poyka.ripdpi.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.integration.scrollAndClick
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import javax.inject.Inject

private class DnsSettingsScreen(
    provider: SemanticsNodeInteractionsProvider,
) : ComposeScreen<DnsSettingsScreen>(provider, { hasTestTag(RipDpiTestTags.screen(Route.DnsSettings)) }) {
    fun tagged(tag: String): KNode = onNode { hasTestTag(tag) }

    val plainMode = tagged(RipDpiTestTags.dnsMode(DnsModePlainUdp))
    val encryptedMode = tagged(RipDpiTestTags.dnsMode(DnsModeEncrypted))
    val plainAddress = tagged(RipDpiTestTags.DnsPlainAddress)
    val plainSave = tagged(RipDpiTestTags.DnsPlainSave)
}

@HiltAndroidTest
class DnsSettingsKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.DnsSettings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun plainUdpResolverIsPersistedOnSave() =
        run {
            step("Switch to plain UDP, enter 9.9.9.9 and save") {
                ComposeScreen.onComposeScreen<DnsSettingsScreen>(composeRule) {
                    plainMode.scrollAndClick()
                    plainAddress.performTextReplacement("9.9.9.9")
                    plainSave.scrollAndClick()
                }
            }
            step("App settings hold the plain UDP resolver") {
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    val settings = runBlocking { appSettingsRepository.snapshot() }
                    settings.dnsMode == DnsModePlainUdp && settings.dnsIp == "9.9.9.9"
                }
            }
        }

    @Test
    fun encryptedProtocolsShowTheirResolverFields() =
        run {
            val fieldsByProtocol =
                mapOf(
                    EncryptedDnsProtocolDoh to
                        listOf(
                            RipDpiTestTags.DnsCustomDohUrl,
                            RipDpiTestTags.DnsCustomBootstrap,
                        ),
                    EncryptedDnsProtocolDot to
                        listOf(
                            RipDpiTestTags.DnsCustomHost,
                            RipDpiTestTags.DnsCustomPort,
                            RipDpiTestTags.DnsCustomTlsServerName,
                        ),
                    EncryptedDnsProtocolDnsCrypt to
                        listOf(RipDpiTestTags.DnsCustomDnsCryptProvider, RipDpiTestTags.DnsCustomDnsCryptPublicKey),
                )
            ComposeScreen.onComposeScreen<DnsSettingsScreen>(composeRule) {
                step("Switch to encrypted DNS") { encryptedMode.scrollAndClick() }
                fieldsByProtocol.forEach { (protocol, fields) ->
                    step("Protocol $protocol shows its resolver fields") {
                        tagged(RipDpiTestTags.dnsProtocol(protocol)).scrollAndClick()
                        fields.forEach { tagged(it).assertIsDisplayed() }
                    }
                }
            }
        }
}
