package com.poyka.ripdpi.ui.journeys.imports

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.xray.DefaultXrayProfileId
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.settings.ConfigPage
import com.poyka.ripdpi.ui.journeys.settings.ModeEditorPage
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Inject

private const val XrayServerHost = "203.0.113.10"
private const val XrayProfileLink =
    "vless://00000000-0000-4000-8000-000000000002@$XrayServerHost:443" +
        "?security=reality&pbk=AbCdEf0123456789AbCdEf0123456789AbCdEf01234&sni=www.example.com&sid=ab12#Journey"

/** The Xray provider import from the mode editor: a checked profile is stored and restored on reopen. */
@HiltAndroidTest
class XrayImportKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Config.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var xrayProfileMetadataStore: XrayProfileMetadataStore

    private fun storedProfile() = runBlocking { xrayProfileMetadataStore.load(DefaultXrayProfileId) }

    private fun openModeEditor() {
        with(ConfigPage(composeRule)) {
            awaitScreen()
            click(profileChange)
            waitFor(profileChoices)
            click(preset("custom"))
        }
    }

    private fun openXrayImport() {
        with(ModeEditorPage(composeRule)) {
            awaitScreen()
            click(RipDpiTestTags.ModeEditorXrayImport)
        }
        XrayImportPage(composeRule).awaitScreen()
    }

    @Test
    fun checkedXrayProfileIsStoredAndRestoredWhenTheScreenReopens() =
        run {
            step("Choose the Xray provider and check a pasted link") {
                openModeEditor()
                openXrayImport()
                with(XrayImportPage(composeRule)) {
                    click(option(XrayServiceModeOption.XrayVpn.name))
                    awaitSelected(option(XrayServiceModeOption.XrayVpn.name))
                    enterProfile(XrayProfileLink)
                    validate()
                    awaitAccepted()
                }
            }
            step("Using the mode stores the profile with the pasted server and returns to the editor") {
                with(XrayImportPage(composeRule)) {
                    finish()
                    waitGone(screenTag, ImportJourneyLaunchMillis)
                }
                ModeEditorPage(composeRule).awaitScreen()
                composeRule.waitWithTree("xray profile never stored") { storedProfile() != null }
                assertEquals(XrayServerHost, storedProfile()?.serverAddress)
            }
            step("Reopening the import restores the Xray choice and the checked profile") {
                openXrayImport()
                with(XrayImportPage(composeRule)) {
                    awaitSelected(option(XrayServiceModeOption.XrayVpn.name))
                    awaitAccepted()
                }
            }
        }
}
