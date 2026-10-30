package com.poyka.ripdpi.ui.journeys.settings

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import javax.inject.Inject

private const val NewRuleName = "Journey rule"
private const val NewRuleDomain = "journey-rule.example.com"

@HiltAndroidTest
class RoutingRulesKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Settings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var ruleRepository: RuleRepository

    @Inject
    lateinit var proxyGroupRepository: ProxyGroupRepository

    @Before
    fun loadProxyGroups() {
        // The app loads proxy groups during startup, which the test application does not run; the
        // rule list waits for that first load before it shows any row.
        runBlocking { proxyGroupRepository.list() }
    }

    @Test
    fun ruleAddedInEditorIsStoredAndListed() =
        run {
            step("Open routing rules from settings and add a rule") {
                with(SettingsRoutingEntryPage(composeRule)) { openAdvancedRow(routingRules) }
                with(RoutesPage(composeRule)) {
                    awaitScreen()
                    click(addRule)
                }
            }
            step("Enter a name and a domain, then save") {
                with(RuleEditorPage(composeRule)) {
                    awaitScreen()
                    waitFor(name)
                    replaceText(name, NewRuleName)
                    replaceText(domains, NewRuleDomain)
                    activate(save)
                }
                RoutesPage(composeRule).awaitScreen()
            }
            var savedId = 0L
            step("The rule is stored with the typed values and listed") {
                composeRule.waitWithTree("rule '$NewRuleName' was never stored") {
                    savedId = userRuleNamed(NewRuleName)?.id ?: 0L
                    savedId != 0L
                }
                assertEquals(NewRuleDomain, userRuleNamed(NewRuleName)?.domains?.trim())
                RoutesPage(composeRule).waitFor(RoutesPage(composeRule).delete(savedId))
            }
            step("Leave and reopen routing rules: the rule is still listed") {
                reopenRoutes()
                with(RoutesPage(composeRule)) { require(delete(savedId)) }
            }
        }

    @Test
    fun deletedRuleLeavesListAndStore() =
        run {
            var keptId = 0L
            var removedId = 0L
            step("Seed two rules") {
                runBlocking {
                    keptId = ruleRepository.insert(seedRule("Journey kept", "kept.example.com", order = 0))
                    removedId = ruleRepository.insert(seedRule("Journey removed", "removed.example.com", order = 1))
                }
            }
            step("Open routing rules, delete one rule and confirm") {
                with(SettingsRoutingEntryPage(composeRule)) { openAdvancedRow(routingRules) }
                with(RoutesPage(composeRule)) {
                    awaitScreen()
                    require(delete(keptId))
                    click(delete(removedId))
                    waitFor(deleteConfirm)
                    click(deleteConfirm)
                    waitGone(delete(removedId))
                }
            }
            step("The store keeps only the other rule") {
                composeRule.waitWithTree("rule $removedId was never deleted") {
                    val ids = runBlocking { ruleRepository.userRules().first() }.map(RuleEntity::id)
                    keptId in ids && removedId !in ids
                }
            }
            step("Leave and reopen routing rules: the deleted rule stays gone") {
                reopenRoutes()
                with(RoutesPage(composeRule)) {
                    require(delete(keptId))
                    check(!exists(delete(removedId))) { "deleted rule $removedId is listed again" }
                }
            }
        }

    private fun reopenRoutes() {
        composeRule.backTo(SettingsRoutingEntryPage(composeRule).screenTag)
        with(SettingsRoutingEntryPage(composeRule)) {
            openAdvancedRow(routingRules)
        }
        RoutesPage(composeRule).awaitScreen()
    }

    private fun userRuleNamed(name: String): RuleEntity? =
        runBlocking { ruleRepository.userRules().first() }.firstOrNull { it.name == name }

    private fun seedRule(
        name: String,
        domain: String,
        order: Int,
    ) = RuleEntity(name = name, domains = domain, userOrder = order)
}
