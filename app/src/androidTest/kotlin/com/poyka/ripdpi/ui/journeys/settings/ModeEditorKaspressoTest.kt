package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class ModeEditorKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Config.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    private fun openCustomEditor() {
        composeRule.clickTag(RipDpiTestTags.ConfigProfileChange)
        composeRule.awaitTag(RipDpiTestTags.ConfigProfileChoices)
        composeRule.clickTag(RipDpiTestTags.configPreset("custom"))
        composeRule.awaitTag(RipDpiTestTags.screen(Route.ModeEditor))
    }

    private fun replaceText(
        tag: String,
        value: String,
    ) {
        composeRule.scrollToTag(tag)
        composeRule.onNodeWithTag(tag).performTextReplacement(value)
    }

    private fun expandAdvanced() {
        composeRule.clickTag(RipDpiTestTags.ModeEditorAdvanced)
        composeRule.awaitTag(RipDpiTestTags.ModeEditorMaxConnections)
    }

    @Test
    fun validEditorValuesArePersistedOnSaveAndReturnToConfig() =
        run {
            step("Open the mode editor through the custom preset") { openCustomEditor() }
            step("Edit listener and engine values") {
                replaceText(RipDpiTestTags.ModeEditorProxyIp, "127.0.0.2")
                replaceText(RipDpiTestTags.ModeEditorProxyPort, "1082")
                expandAdvanced()
                replaceText(RipDpiTestTags.ModeEditorMaxConnections, "256")
                replaceText(RipDpiTestTags.ModeEditorBufferSize, "32768")
                replaceText(RipDpiTestTags.ModeEditorDefaultTtl, "64")
            }
            step("Save returns to the config screen") {
                composeRule.clickTag(RipDpiTestTags.ModeEditorSave)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Config))
            }
            step("App settings hold the edited values instead of the defaults") {
                composeRule.awaitSettings(repository = appSettingsRepository) { it.proxyPort == 1082 }
                val settings = runBlocking { appSettingsRepository.snapshot() }
                assertEquals("127.0.0.2", settings.proxyIp)
                assertEquals(256, settings.maxConnections)
                assertEquals(32768, settings.bufferSize)
                assertEquals(64, settings.defaultTtl)
                assertEquals(true, settings.customTtl)
            }
        }

    @Test
    fun invalidPortIsRejectedWithValidationFeedbackAndNothingIsSaved() =
        run {
            val defaultPort = AppSettingsSerializer.defaultValue.proxyPort
            step("Open the editor and enter an out-of-range port") {
                openCustomEditor()
                replaceText(RipDpiTestTags.ModeEditorProxyPort, "99999")
            }
            step("Save shows the validation snackbar and stays in the editor") {
                composeRule.clickTag(RipDpiTestTags.ModeEditorSave)
                composeRule.awaitTag(RipDpiTestTags.ModeEditorValidationSnackbar)
                composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.ModeEditor)).assertIsDisplayed()
            }
            step("The stored port is unchanged") {
                assertEquals(defaultPort, runBlocking { appSettingsRepository.snapshot() }.proxyPort)
            }
        }

    @Test
    fun commandLineToggleAndArgumentsArePersisted() =
        run {
            step("Enable command line settings and enter arguments") {
                openCustomEditor()
                expandAdvanced()
                composeRule.clickTag(RipDpiTestTags.ModeEditorCommandLineToggle)
                replaceText(RipDpiTestTags.ModeEditorCommandLineArgs, "--split 2")
            }
            step("Save and read the persisted override") {
                composeRule.clickTag(RipDpiTestTags.ModeEditorSave)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Config))
                composeRule.awaitSettings(repository = appSettingsRepository) { it.enableCmdSettings }
                assertEquals("--split 2", runBlocking { appSettingsRepository.snapshot() }.cmdArgs)
            }
        }

    @Test
    fun chainDslEntryIsParsedAndPersisted() =
        run {
            val dsl = "[tcp]\ntlsrec auto(sniext)\nsplit auto(balanced)"
            step("Enter a chain in the raw DSL editor") {
                openCustomEditor()
                expandAdvanced()
                composeRule.scrollToTag(RipDpiTestTags.ModeEditorChainRawToggle)
                if (!composeRule.tagExists(RipDpiTestTags.ModeEditorChainDsl)) {
                    composeRule.clickTag(RipDpiTestTags.ModeEditorChainRawToggle)
                }
                replaceText(RipDpiTestTags.ModeEditorChainDsl, dsl)
            }
            step("The chain is valid and saving persists it") {
                composeRule.clickTag(RipDpiTestTags.ModeEditorSave)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Config))
                composeRule.awaitSettings(repository = appSettingsRepository) {
                    formatStrategyChainDsl(it.effectiveTcpChainSteps(), it.effectiveUdpChainSteps()) == dsl
                }
                val defaults = AppSettingsSerializer.defaultValue
                assertNotEquals(
                    dsl,
                    formatStrategyChainDsl(defaults.effectiveTcpChainSteps(), defaults.effectiveUdpChainSteps()),
                )
            }
        }
}
