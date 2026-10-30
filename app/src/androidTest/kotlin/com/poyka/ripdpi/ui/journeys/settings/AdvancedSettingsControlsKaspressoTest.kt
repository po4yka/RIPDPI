package com.poyka.ripdpi.ui.journeys.settings

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.settings.ActivationWindowDimension
import com.poyka.ripdpi.ui.screens.settings.AdvancedToggleSetting
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class AdvancedSettingsControlsKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.AdvancedSettings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun sectionsAndControlsAreReachable() =
        run {
            val page = AdvancedSettingsPage(composeRule)
            val tags =
                listOf(
                    page.section("diagnostics_history"),
                    page.toggle(AdvancedToggleSetting.DiagnosticsMonitorEnabled),
                    page.section("overrides"),
                    page.toggle(AdvancedToggleSetting.UseCommandLine),
                    page.proxyPortInput,
                    page.toggle(AdvancedToggleSetting.DesyncHttp),
                    page.toggle(AdvancedToggleSetting.DesyncHttps),
                )
            page.awaitScreen()
            tags.forEach { tag ->
                step("Reach $tag") {
                    page.require(tag)
                    page.node(tag).assertExists()
                }
            }
        }

    @Test
    fun tcpFastOpenToggleIsPersisted() =
        run {
            val before = AppSettingsSerializer.defaultValue.tcpFastOpen
            step("Toggle TCP fast open") {
                with(AdvancedSettingsPage(composeRule)) { click(toggle(AdvancedToggleSetting.TcpFastOpen)) }
            }
            step("The stored flag flips") {
                composeRule.waitForSettings(appSettingsRepository) { it.tcpFastOpen != before }
            }
        }

    @Test
    fun proxyPortIsPersistedOnSave() =
        run {
            step("Replace the proxy port with 1081 and save") {
                with(AdvancedSettingsPage(composeRule)) {
                    replaceText(proxyPortInput, "1081")
                    click(proxyPortSave)
                }
            }
            step("The stored port replaces the default") {
                composeRule.waitForSettings(appSettingsRepository) { it.proxyPort == 1081 }
            }
        }

    @Test
    fun dropdownOptionIsPersisted() =
        run {
            step("Pick the Firefox fingerprint profile") {
                with(AdvancedSettingsPage(composeRule)) {
                    click(tlsFingerprintDropdown)
                    click(dropdownOption(tlsFingerprintDropdown, TlsFingerprintProfileFirefoxStable))
                }
            }
            step("The stored profile changes") {
                composeRule.waitForSettings(appSettingsRepository) {
                    it.tlsFingerprintProfile == TlsFingerprintProfileFirefoxStable
                }
            }
        }

    @Test
    fun activationRoundAndPayloadRangesArePersisted() =
        run {
            step("Edit and save the round range 1-3") {
                with(AdvancedSettingsPage(composeRule)) {
                    replaceText(activationStart(ActivationWindowDimension.Round), "1")
                    replaceText(activationEnd(ActivationWindowDimension.Round), "3")
                    click(activationSave(ActivationWindowDimension.Round))
                }
            }
            step("Edit and save the payload size range 32-512") {
                with(AdvancedSettingsPage(composeRule)) {
                    replaceText(activationStart(ActivationWindowDimension.PayloadSize), "32")
                    replaceText(activationEnd(ActivationWindowDimension.PayloadSize), "512")
                    click(activationSave(ActivationWindowDimension.PayloadSize))
                }
            }
            step("Both ranges are stored") {
                composeRule.waitForSettings(appSettingsRepository) {
                    val filter = it.effectiveGroupActivationFilter()
                    filter.round.start == 1L && filter.round.end == 3L &&
                        filter.payloadSize.start == 32L && filter.payloadSize.end == 512L
                }
                val filter = runBlocking { appSettingsRepository.snapshot() }.effectiveGroupActivationFilter()
                assertEquals(512L, filter.payloadSize.end)
            }
        }
}
