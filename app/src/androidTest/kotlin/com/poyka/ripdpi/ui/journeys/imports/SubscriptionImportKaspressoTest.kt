package com.poyka.ripdpi.ui.journeys.imports

import androidx.compose.ui.test.assertIsNotEnabled
import com.poyka.ripdpi.R
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.settings.ConfigPage
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Inject

// The .invalid TLD never resolves, so the first refresh fails the same way online and offline.
private const val TypedSubscriptionHost = "subs.example.invalid"
private const val TypedSubscriptionUrl = "https://$TypedSubscriptionHost/journey/typed"
private const val SharedSubscriptionName = "Journey Feed"
private const val SharedSubscriptionUrl = "https://feed.example.invalid/journey/shared"
private const val SharedSubscriptionLink =
    "sing-box://import-remote-profile?url=https%3A%2F%2Ffeed.example.invalid%2Fjourney%2Fshared#Journey%20Feed"

/**
 * Subscription import end to end. Offline (and for an unresolvable host) the first refresh
 * cannot succeed, so the honest outcome is a saved subscription plus a visible failure, not
 * a success that hides a missing entry.
 */
@HiltAndroidTest
class SubscriptionImportKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Config.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var proxyGroupRepository: ProxyGroupRepository

    private fun subscriptionGroups() =
        runBlocking { proxyGroupRepository.list() }.filter { it.type == ProxyGroupType.SUBSCRIPTION }

    private fun openSubscriptionAdd() {
        with(ConfigPage(composeRule)) {
            awaitScreen()
            click(profileChange)
            waitFor(profileChoices)
            click(RipDpiTestTags.ConfigVpnAddProfile)
            click(RipDpiTestTags.configVpnCreateProfile(Route.SubscriptionAdd))
        }
        SubscriptionAddPage(composeRule).awaitScreen()
    }

    private fun openSubscriptionStatus() {
        with(SettingsEntryPage(composeRule)) {
            click(bottomNavSettings)
            awaitScreen()
            click(subscriptionStatus)
        }
        SubscriptionStatusPage(composeRule).awaitScreen()
    }

    @Test
    fun typedSubscriptionLinkIsSavedAndListedWhenTheFirstRefreshFails() =
        run {
            step("Add subscription from the profile choices and type the link") {
                openSubscriptionAdd()
                with(SubscriptionAddPage(composeRule)) {
                    enterUrl(TypedSubscriptionUrl)
                    click(continueAction)
                }
            }
            step("The confirmation shows the typed link; Add reports the failed first refresh") {
                with(SubscriptionImportConfirmPage(composeRule)) {
                    awaitScreen()
                    composeRule.awaitText(TypedSubscriptionUrl)
                    composeRule.awaitText(string(R.string.import_subscription_unnamed))
                    confirmAdd()
                    awaitImportFailed()
                    // A failed import stays on the confirmation instead of reporting success.
                    awaitScreen()
                }
            }
            step("The subscription is saved under its host with the typed link") {
                composeRule.waitWithTree("subscription group never saved") { subscriptionGroups().isNotEmpty() }
                val group = subscriptionGroups().single()
                assertEquals(TypedSubscriptionHost, group.name)
                assertEquals(TypedSubscriptionUrl, group.subscription?.link)
            }
            step("Subscription status lists it by host with a failed refresh") {
                pressBackToMainScreen()
                openSubscriptionStatus()
                composeRule.awaitText(TypedSubscriptionHost)
                composeRule.awaitText(
                    string(
                        R.string.subscription_status_refresh_format,
                        string(R.string.subscription_status_refresh_failed),
                    ),
                )
            }
        }

    @Test
    fun sharedSubscriptionDeepLinkKeepsItsNameThroughConfirmation() =
        run {
            step("Another app opens a sing-box subscription link") {
                ConfigPage(composeRule).awaitScreen()
                sendToApp(viewLinkIntent(SharedSubscriptionLink))
            }
            step("The confirmation shows the link's name and URL; Add reports the failed refresh") {
                with(SubscriptionImportConfirmPage(composeRule)) {
                    awaitScreen(ImportJourneyLaunchMillis)
                    composeRule.awaitText(SharedSubscriptionName)
                    composeRule.awaitText(SharedSubscriptionUrl)
                    confirmAdd()
                    awaitImportFailed()
                }
            }
            step("The subscription is saved under the shared name and listed in subscription status") {
                composeRule.waitWithTree("subscription group never saved") { subscriptionGroups().isNotEmpty() }
                val group = subscriptionGroups().single()
                assertEquals(SharedSubscriptionName, group.name)
                assertEquals(SharedSubscriptionUrl, group.subscription?.link)
                pressBackToMainScreen()
                openSubscriptionStatus()
                composeRule.awaitText(SharedSubscriptionName)
            }
        }

    @Test
    fun linkThatIsNotASubscriptionIsRejectedAndSavesNothing() =
        run {
            step("A plain http link and free text are both refused with an error") {
                openSubscriptionAdd()
                with(SubscriptionAddPage(composeRule)) {
                    enterUrl("http://$TypedSubscriptionHost/journey")
                    composeRule.awaitText(string(R.string.subscription_add_url_invalid))
                    node(continueAction).assertIsNotEnabled()
                    enterUrl("not a subscription link")
                    composeRule.awaitText(string(R.string.subscription_add_url_invalid))
                    node(continueAction).assertIsNotEnabled()
                }
            }
            step("Leaving the screen creates no subscription") {
                pressBackToMainScreen()
                assertTrue(subscriptionGroups().isEmpty())
            }
        }

    /** Back until a bottom-navigation screen is shown (the confirmation replaced the add screen). */
    private fun pressBackToMainScreen() {
        val settings = SettingsEntryPage(composeRule)
        repeat(MaxBackPresses) {
            if (settings.exists(settings.bottomNavSettings)) return
            settings.pressBack()
        }
        settings.waitFor(settings.bottomNavSettings)
    }
}

internal const val ImportJourneyLaunchMillis = 20_000L
private const val MaxBackPresses = 4
