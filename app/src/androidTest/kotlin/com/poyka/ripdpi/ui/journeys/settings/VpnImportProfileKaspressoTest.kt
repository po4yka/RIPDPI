package com.poyka.ripdpi.ui.journeys.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
    fun pastedVlessRealityLinkIsConfirmedAndAddedToTheServerList() =
        run {
            step("The server section offers paste and scan entry points") {
                composeRule.clickTag(RipDpiTestTags.configModeSection("vpn"))
                composeRule.awaitTag(RipDpiTestTags.ConfigVpnSimple)
                composeRule.scrollToTag(RipDpiTestTags.ConfigVpnAddServerPaste)
                composeRule.scrollToTag(RipDpiTestTags.ConfigVpnAddServerScan)
            }
            step("Pasting the seeded link opens the import confirmation with the profile name") {
                seedClipboard(VlessRealityLink)
                composeRule.clickTag(RipDpiTestTags.ConfigVpnAddServerPaste)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.ProfileImportConfirm()))
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodes(hasText(ImportedProfileName)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            step("Adding the profile lists it in the server section") {
                composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.ProfileImportConfirm())).assertIsDisplayed()
                composeRule.onNode(hasText("Add")).performClick()
                composeRule.waitUntil(15_000) {
                    composeRule.onAllNodes(hasText("Added")).fetchSemanticsNodes().isNotEmpty()
                }
                Espresso.pressBack()
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Config))
                composeRule.clickTag(RipDpiTestTags.configModeSection("vpn"))
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodes(hasText(ImportedProfileName)).fetchSemanticsNodes().isNotEmpty()
                }
            }
        }
}
