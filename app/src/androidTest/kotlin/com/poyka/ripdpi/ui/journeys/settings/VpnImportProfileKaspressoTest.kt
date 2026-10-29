package com.poyka.ripdpi.ui.journeys.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
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
    private val profileRow =
        SemanticsMatcher("tag starts with config-vpn-profile-row-") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("config-vpn-profile-row-") == true
        }

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
                composeRule.clickTag(RipDpiTestTags.ConfigProfileChange)
                composeRule.awaitTag(RipDpiTestTags.ConfigProfileChoices)
                composeRule.scrollToTag(RipDpiTestTags.ConfigVpnAddServerPaste)
                composeRule.scrollToTag(RipDpiTestTags.ConfigVpnAddServerScan)
            }
            step("Pasting the seeded link opens the import confirmation with the profile name") {
                seedClipboard(VlessRealityLink)
                composeRule.clickTag(RipDpiTestTags.ConfigVpnAddServerPaste)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.ProfileImportConfirm()))
                runCatching {
                    composeRule.waitUntil(10_000) {
                        composeRule.onAllNodes(hasText(ImportedProfileName)).fetchSemanticsNodes().isNotEmpty()
                    }
                }.onFailure {
                    composeRule.onRoot(useUnmergedTree = true).printToLog("IMPORTDBG")
                    throw it
                }
            }
            step("Adding the profile lists it in the profile choices") {
                composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.ProfileImportConfirm())).assertIsDisplayed()
                composeRule.onNode(hasText("Add")).performClick()
                composeRule.awaitNoTag(RipDpiTestTags.screen(Route.ProfileImportConfirm()), timeoutMillis = 15_000)
                composeRule.awaitTag(RipDpiTestTags.screen(Route.Config))
                if (!composeRule.tagExists(RipDpiTestTags.ConfigProfileChoices)) {
                    composeRule.clickTag(RipDpiTestTags.ConfigProfileChange)
                }
                composeRule.awaitTag(RipDpiTestTags.ConfigProfileChoices)
                composeRule.waitUntil(15_000) {
                    composeRule.onAllNodes(profileRow).fetchSemanticsNodes().isNotEmpty()
                }
            }
        }
}
