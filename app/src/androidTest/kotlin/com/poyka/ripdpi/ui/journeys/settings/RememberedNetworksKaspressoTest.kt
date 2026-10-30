package com.poyka.ripdpi.ui.journeys.settings

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitWithTree
import com.poyka.ripdpi.ui.navigation.Route
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class RememberedNetworksKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.AdvancedSettings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var policyStore: RememberedNetworkPolicyStore

    @Test
    fun forgettingOneNetworkRemovesOnlyThatEntry() =
        run {
            var kept = 0L
            var forgotten = 0L
            step("Seed two remembered networks with synthetic fingerprints") {
                runBlocking {
                    policyStore.clearAll()
                    kept = policyStore.upsertObservedPolicy(syntheticPolicy("journey-fingerprint-kept")).id
                    forgotten = policyStore.upsertObservedPolicy(syntheticPolicy("journey-fingerprint-forgotten")).id
                }
            }
            step("Open remembered networks from advanced settings: both entries are listed") {
                with(RememberedNetworksEntryPage(composeRule)) {
                    awaitInspectEnabled()
                    click(inspect)
                }
                with(RememberedNetworksPage(composeRule)) {
                    awaitScreen()
                    require(card(kept))
                    require(card(forgotten))
                }
            }
            step("Forget one entry and confirm") {
                with(RememberedNetworksPage(composeRule)) {
                    click(delete(forgotten))
                    waitFor(deleteConfirm)
                    click(deleteConfirm)
                    waitGone(card(forgotten))
                    require(card(kept))
                }
                composeRule.waitWithTree("policy $forgotten was never deleted") {
                    val policies = runBlocking { policyStore.observePolicies().first() }
                    val ids = policies.map(RememberedNetworkPolicyEntity::id)
                    ids == listOf(kept)
                }
            }
            step("Leave and reopen: only the kept entry is listed") {
                composeRule.backTo(RememberedNetworksEntryPage(composeRule).screenTag)
                with(RememberedNetworksEntryPage(composeRule)) {
                    awaitScreen()
                    awaitInspectEnabled()
                    click(inspect)
                }
                with(RememberedNetworksPage(composeRule)) {
                    awaitScreen()
                    require(card(kept))
                    check(!exists(card(forgotten))) { "forgotten network $forgotten is listed again" }
                }
            }
        }

    /** A policy with a made-up fingerprint hash; it names no real network. */
    private fun syntheticPolicy(fingerprintHash: String) =
        RememberedNetworkPolicyJson(
            fingerprintHash = fingerprintHash,
            mode = Mode.VPN.preferenceValue,
            summary =
                NetworkFingerprintSummary(
                    transport = "wifi",
                    networkState = "validated",
                    identityKind = "wifi",
                    privateDnsMode = "system",
                    dnsServerCount = 1,
                ),
            proxyConfigJson = "{}",
        )
}
