package com.poyka.ripdpi.ui.journeys.imports

import androidx.compose.ui.test.hasText
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.reveal
import com.poyka.ripdpi.ui.journeys.settings.ConnectionPage
import com.poyka.ripdpi.ui.journeys.settings.ProfileImportConfirmPage
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Inject

private const val SharedProfileName = "Journey Share"
private const val SharedProfileHost = "203.0.113.10"
private const val SharedProfileLink =
    "vless://00000000-0000-4000-8000-000000000001@$SharedProfileHost:443" +
        "?security=reality&pbk=AbCdEf0123456789AbCdEf0123456789AbCdEf01234" +
        "&sni=www.example.com&flow=xtls-rprx-vision#Journey%20Share"

/** A server link shared from another app is saved for later selection. */
@HiltAndroidTest
class SharedProfileImportKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Connection.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var relayProfileStore: RelayProfileStore

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    private fun importedProfile() =
        runBlocking { relayProfileStore.list() }.singleOrNull { it.operatorName == SharedProfileName }

    @Test
    fun sharedServerLinkIsSavedWithoutChangingRelaySelection() =
        run {
            ConnectionPage(composeRule).awaitScreen()
            val before = runBlocking { appSettingsRepository.snapshot() }
            step("Another app shares a vless link as text") {
                sendToApp(shareTextIntent(SharedProfileLink))
            }
            step("The confirmation shows the profile name and server") {
                with(ProfileImportConfirmPage(composeRule)) {
                    awaitScreen(ImportJourneyLaunchMillis)
                    composeRule.awaitText(SharedProfileName)
                    composeRule.waitWithTree("import confirmation server never appeared") {
                        composeRule.reveal(hasText("$SharedProfileHost:443", substring = true))
                    }
                    confirmAdd()
                    waitGone(screenTag, ImportJourneyLaunchMillis)
                }
            }
            step("The relay profile is saved and the current relay selection is preserved") {
                composeRule.waitWithTree("imported relay profile never saved") { importedProfile() != null }
                val saved = checkNotNull(importedProfile())
                assertEquals(SharedProfileHost, saved.server)
                assertEquals(443, saved.serverPort)
                val after = runBlocking { appSettingsRepository.snapshot() }
                assertEquals(before.relayEnabled, after.relayEnabled)
                assertEquals(before.relayProfileId, after.relayProfileId)
                assertEquals(before.relayServer, after.relayServer)
            }
            step("The saved relay profile appears in Config") {
                with(ConnectionPage(composeRule)) {
                    if (!exists(screenTag)) click(bottomNavConfig)
                    awaitScreen()
                    click(profileChange)
                    waitFor(profileChoices)
                    awaitDisplayed(RipDpiTestTags.connectionRelayProfileRow(checkNotNull(importedProfile()).id))
                }
            }
        }
}
