package com.poyka.ripdpi.ui.journeys.imports

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.settings.ConfigPage
import com.poyka.ripdpi.ui.journeys.settings.ProfileImportConfirmPage
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
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

/** A server link shared from another app reaches the profile list and the active relay. */
@HiltAndroidTest
class SharedProfileImportKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Config.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var proxyGroupRepository: ProxyGroupRepository

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun sharedServerLinkIsConfirmedAndBecomesTheActiveRelay() =
        run {
            step("Another app shares a vless link as text") {
                ConfigPage(composeRule).awaitScreen()
                sendToApp(shareTextIntent(SharedProfileLink))
            }
            step("The confirmation shows the profile name and server") {
                with(ProfileImportConfirmPage(composeRule)) {
                    awaitScreen(ImportJourneyLaunchMillis)
                    composeRule.awaitText(SharedProfileName)
                    confirmAdd()
                    waitGone(screenTag, ImportJourneyLaunchMillis)
                }
            }
            step("The profile is saved by name and its server is the configured relay") {
                composeRule.waitWithTree("imported profile group never saved") {
                    runBlocking { proxyGroupRepository.list() }.any { it.name == SharedProfileName }
                }
                composeRule.waitForSettings(appSettingsRepository) {
                    it.relayEnabled && it.relayServer == SharedProfileHost
                }
            }
            step("Config lists the profile and shows its server on the relay row") {
                with(ConfigPage(composeRule)) {
                    if (!exists(screenTag)) click(bottomNavConfig)
                    awaitScreen()
                    click(modeSection("vpn"))
                    awaitDisplayed(vpnRelay)
                    assertEquals("$SharedProfileHost:443", stateDescription(vpnRelay))
                    click(profileChange)
                    waitFor(profileChoices)
                    awaitProfileRow()
                }
            }
        }
}
