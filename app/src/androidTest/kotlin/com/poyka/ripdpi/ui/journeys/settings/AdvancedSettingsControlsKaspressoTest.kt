package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.settings.ActivationWindowDimension
import com.poyka.ripdpi.ui.screens.settings.AdvancedOptionSetting
import com.poyka.ripdpi.ui.screens.settings.AdvancedTextSetting
import com.poyka.ripdpi.ui.screens.settings.AdvancedToggleSetting
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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

    private fun replaceText(
        tag: String,
        value: String,
    ) {
        composeRule.scrollToTag(tag)
        composeRule.onNodeWithTag(tag).performTextReplacement(value)
    }

    @Test
    fun sectionsAndControlsAreReachable() =
        run {
            val tags =
                listOf(
                    RipDpiTestTags.advancedSection("diagnostics_history"),
                    RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DiagnosticsMonitorEnabled),
                    RipDpiTestTags.advancedSection("overrides"),
                    RipDpiTestTags.advancedToggle(AdvancedToggleSetting.UseCommandLine),
                    RipDpiTestTags.advancedInput(AdvancedTextSetting.ProxyPort),
                    RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttp),
                    RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DesyncHttps),
                )
            composeRule.awaitTag(RipDpiTestTags.screen(Route.AdvancedSettings))
            tags.forEach { tag ->
                step("Reach $tag") {
                    composeRule.scrollToTag(tag)
                    composeRule.onNodeWithTag(tag).assertIsDisplayed()
                }
            }
        }

    @Test
    fun tcpFastOpenToggleIsPersisted() =
        run {
            val before = AppSettingsSerializer.defaultValue.tcpFastOpen
            step("Toggle TCP fast open") {
                composeRule.clickTag(RipDpiTestTags.advancedToggle(AdvancedToggleSetting.TcpFastOpen))
            }
            step("The stored flag flips") {
                composeRule.awaitSettings(repository = appSettingsRepository) { it.tcpFastOpen != before }
            }
        }

    @Test
    fun proxyPortIsPersistedOnSave() =
        run {
            step("Replace the proxy port with 1081 and save") {
                replaceText(RipDpiTestTags.advancedInput(AdvancedTextSetting.ProxyPort), "1081")
                composeRule.clickTag(RipDpiTestTags.advancedSave(AdvancedTextSetting.ProxyPort))
            }
            step("The stored port replaces the default") {
                composeRule.awaitSettings(repository = appSettingsRepository) { it.proxyPort == 1081 }
            }
        }

    @Test
    fun dropdownOptionIsPersisted() =
        run {
            val dropdown = RipDpiTestTags.advancedOption(AdvancedOptionSetting.TlsFingerprintProfile)
            step("Pick the Firefox fingerprint profile") {
                composeRule.clickTag(dropdown)
                composeRule.clickTag(RipDpiTestTags.dropdownOption(dropdown, TlsFingerprintProfileFirefoxStable))
            }
            step("The stored profile changes") {
                composeRule.awaitSettings(repository = appSettingsRepository) {
                    it.tlsFingerprintProfile == TlsFingerprintProfileFirefoxStable
                }
            }
        }

    @Test
    fun activationRoundAndPayloadRangesArePersisted() =
        run {
            step("Edit and save the round range 1-3") {
                replaceText(RipDpiTestTags.activationStart(ActivationWindowDimension.Round), "1")
                replaceText(RipDpiTestTags.activationEnd(ActivationWindowDimension.Round), "3")
                composeRule.clickTag(RipDpiTestTags.activationSave(ActivationWindowDimension.Round))
            }
            step("Edit and save the payload size range 32-512") {
                replaceText(RipDpiTestTags.activationStart(ActivationWindowDimension.PayloadSize), "32")
                replaceText(RipDpiTestTags.activationEnd(ActivationWindowDimension.PayloadSize), "512")
                composeRule.clickTag(RipDpiTestTags.activationSave(ActivationWindowDimension.PayloadSize))
            }
            step("Both ranges are stored") {
                composeRule.awaitSettings(repository = appSettingsRepository) {
                    val filter = it.effectiveGroupActivationFilter()
                    filter.round.start == 1L && filter.round.end == 3L &&
                        filter.payloadSize.start == 32L && filter.payloadSize.end == 512L
                }
                val filter = runBlocking { appSettingsRepository.snapshot() }.effectiveGroupActivationFilter()
                assertEquals(512L, filter.payloadSize.end)
            }
        }
}
