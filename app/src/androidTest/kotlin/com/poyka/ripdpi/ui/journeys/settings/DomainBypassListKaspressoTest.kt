package com.poyka.ripdpi.ui.journeys.settings

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.rules.RuleRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import javax.inject.Inject

private const val FirstDomain = "journey-one.example.com"
private const val SecondDomain = "journey-two.example.com"

@HiltAndroidTest
class DomainBypassListKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Settings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var ruleRepository: RuleRepository

    @Test
    fun addedDomainsAreStoredAndShownAfterReopen() =
        run {
            val list = "$FirstDomain\n$SecondDomain"
            step("Open the bypass list from settings, enter two domains and save") {
                with(SettingsRoutingEntryPage(composeRule)) { openAdvancedRow(domainBypass) }
                with(DomainBypassPage(composeRule)) {
                    awaitScreen()
                    awaitSaveEnabled()
                    replaceText(list)
                    activate(save)
                }
                awaitStoredDomains(setOf(FirstDomain, SecondDomain))
            }
            step("Leave and reopen: the editor shows both domains") {
                reopenBypassList()
                DomainBypassPage(composeRule).awaitEditorText(list)
            }
        }

    @Test
    fun removedDomainIsDroppedFromStoreAndEditor() =
        run {
            step("Seed a two-domain bypass list") {
                runBlocking { ruleRepository.saveDomainBypassList("$FirstDomain\n$SecondDomain") }
            }
            step("Open the bypass list: both seeded domains are shown") {
                with(SettingsRoutingEntryPage(composeRule)) { openAdvancedRow(domainBypass) }
                DomainBypassPage(composeRule).awaitEditorText("$FirstDomain\n$SecondDomain")
            }
            step("Remove the second domain and save") {
                with(DomainBypassPage(composeRule)) {
                    replaceText(FirstDomain)
                    activate(save)
                }
                awaitStoredDomains(setOf(FirstDomain))
            }
            step("Leave and reopen: only the first domain remains") {
                reopenBypassList()
                DomainBypassPage(composeRule).awaitEditorText(FirstDomain)
            }
        }

    private fun reopenBypassList() {
        composeRule.backTo(SettingsRoutingEntryPage(composeRule).screenTag)
        with(SettingsRoutingEntryPage(composeRule)) {
            openAdvancedRow(domainBypass)
        }
        DomainBypassPage(composeRule).awaitScreen()
    }

    private fun awaitStoredDomains(expected: Set<String>) =
        composeRule.waitWithTree("stored bypass domains never became $expected") {
            val rule = runBlocking { ruleRepository.domainBypassRule().first() }
            val domains = rule?.domains.orEmpty()
            domains.lines().filter(String::isNotBlank).toSet() == expected
        }
}
