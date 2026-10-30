package com.poyka.ripdpi.ui.journeys.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

private const val ImportedProfileName = "M6 Reality"
private const val VlessRealityLink =
    "vless://550e8400-e29b-41d4-a716-446655440000@edge.example.com:443" +
        "?security=reality&pbk=AbCdEf0123456789AbCdEf0123456789AbCdEf01234" +
        "&sni=www.cloudflare.com&flow=xtls-rprx-vision#M6%20Reality"

@HiltAndroidTest
class VpnImportProfileKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Config.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    private fun seedClipboard(text: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("server link", text))
        }
    }

    @Test
    fun pastedVlessRealityLinkIsConfirmedAndAddedToTheProfileList() =
        run {
            step("The profile choices offer paste and scan entry points") {
                with(ConfigPage(composeRule)) {
                    click(profileChange)
                    waitFor(profileChoices)
                    require(vpnAddServerPaste)
                    require(vpnAddServerScan)
                }
            }
            step("Pasting the seeded link opens the import confirmation with the profile name") {
                seedClipboard(VlessRealityLink)
                with(ConfigPage(composeRule)) { click(vpnAddServerPaste) }
                with(ProfileImportConfirmPage(composeRule)) {
                    awaitScreen()
                    awaitText(ImportedProfileName)
                }
            }
            step("Adding the profile lists it in the profile choices") {
                with(ProfileImportConfirmPage(composeRule)) {
                    node(screenTag).assertIsDisplayed()
                    confirmAdd()
                    waitGone(screenTag, 15_000)
                }
                with(ConfigPage(composeRule)) {
                    if (!exists(screenTag)) {
                        click(bottomNavConfig)
                    }
                    awaitScreen()
                    if (!exists(profileChoices)) {
                        click(profileChange)
                    }
                    waitFor(profileChoices)
                    awaitProfileRow()
                }
            }
        }
}
