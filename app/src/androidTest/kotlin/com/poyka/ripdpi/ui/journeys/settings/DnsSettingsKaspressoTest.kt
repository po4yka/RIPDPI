package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import javax.inject.Inject

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
                with(DnsSettingsPage(composeRule)) {
                    click(plainMode)
                    node(plainAddress).performTextReplacement("9.9.9.9")
                    click(plainSave)
                }
            }
            step("App settings hold the plain UDP resolver") {
                composeRule.waitForSettings(appSettingsRepository, timeoutMillis = 5_000) {
                    it.dnsMode == DnsModePlainUdp && it.dnsIp == "9.9.9.9"
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
            with(DnsSettingsPage(composeRule)) {
                step("Switch to encrypted DNS") { click(encryptedMode) }
                fieldsByProtocol.forEach { (protocol, fields) ->
                    step("Protocol $protocol shows its resolver fields") {
                        click(protocolTag(protocol))
                        fields.forEach { node(it).assertIsDisplayed() }
                    }
                }
            }
        }
}
