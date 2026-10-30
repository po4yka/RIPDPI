package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.navigation.Route
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
        with(ConfigPage(composeRule)) {
            click(profileChange)
            waitFor(profileChoices)
            click(preset("custom"))
        }
        ModeEditorPage(composeRule).awaitScreen()
    }

    @Test
    fun validEditorValuesArePersistedOnSaveAndReturnToConfig() =
        run {
            step("Open the mode editor through the custom preset") { openCustomEditor() }
            step("Edit listener and engine values") {
                with(ModeEditorPage(composeRule)) {
                    replaceText(proxyIp, "127.0.0.2")
                    replaceText(proxyPort, "1082")
                    expandAdvanced()
                    replaceText(maxConnections, "256")
                    replaceText(bufferSize, "32768")
                    replaceText(defaultTtl, "64")
                }
            }
            step("Save returns to the config screen") {
                with(ModeEditorPage(composeRule)) { click(save) }
                ConfigPage(composeRule).awaitScreen()
            }
            step("App settings hold the edited values instead of the defaults") {
                composeRule.waitForSettings(appSettingsRepository) { it.proxyPort == 1082 }
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
                with(ModeEditorPage(composeRule)) { replaceText(proxyPort, "99999") }
            }
            step("Save shows the validation snackbar and stays in the editor") {
                with(ModeEditorPage(composeRule)) {
                    click(save)
                    waitFor(validationSnackbar)
                    node(screenTag).assertIsDisplayed()
                }
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
                with(ModeEditorPage(composeRule)) {
                    expandAdvanced()
                    click(commandLineToggle)
                    replaceText(commandLineArgs, "--split 2")
                }
            }
            step("Save and read the persisted override") {
                with(ModeEditorPage(composeRule)) { click(save) }
                ConfigPage(composeRule).awaitScreen()
                composeRule.waitForSettings(appSettingsRepository) { it.enableCmdSettings }
                assertEquals("--split 2", runBlocking { appSettingsRepository.snapshot() }.cmdArgs)
            }
        }

    @Test
    fun chainDslEntryIsParsedAndPersisted() =
        run {
            val dsl = "[tcp]\ntlsrec auto(sniext)\nsplit auto(balanced)"
            step("Enter a chain in the raw DSL editor") {
                openCustomEditor()
                with(ModeEditorPage(composeRule)) {
                    expandAdvanced()
                    require(chainRawToggle)
                    if (!exists(chainDsl)) {
                        click(chainRawToggle)
                    }
                    replaceText(chainDsl, dsl)
                }
            }
            step("The chain is valid and saving persists it") {
                with(ModeEditorPage(composeRule)) { click(save) }
                ConfigPage(composeRule).awaitScreen()
                composeRule.waitForSettings(appSettingsRepository) {
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
