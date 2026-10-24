package com.poyka.ripdpi.integration

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.core.RipDpiRelayBindings
import com.poyka.ripdpi.core.RipDpiRelayBindingsModule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProfileQualityBudgetLedger
import com.poyka.ripdpi.data.ProfileQualityDecisionReason
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProfileQualityStatus
import com.poyka.ripdpi.data.ProfileQualityStore
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.CandidateHttpPayloadProbe
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.CandidateRelayPayloadProbe
import com.poyka.ripdpi.services.OwnedQualityHttpsFixture
import com.poyka.ripdpi.services.PhysicalNetworkObserver
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.subscription.CurrentSelectorProbeScopeProvider
import com.poyka.ripdpi.subscription.MemberLatencyProbe
import com.poyka.ripdpi.subscription.SelectorUrltestCoordinator
import com.poyka.ripdpi.subscription.SelectorUrltestProber
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import javax.inject.Inject

/** Actual JNI candidate, authenticated TLS bytes and production selector reload acknowledgment. */
@HiltAndroidTest
@UninstallModules(RipDpiRelayBindingsModule::class)
class ProfileQualityNativeTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @get:Rule val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { statement, _ -> statement }
        }
    private val origin = OwnedQualityHttpsFixture()

    @BindValue @JvmField
    val httpProbe: CandidateHttpPayloadProbe = origin.httpProbe()

    @BindValue @JvmField
    val bindings: RipDpiRelayBindings = ObservedNativeRelayBindings()

    @Inject lateinit var candidateProbe: CandidateRelayPayloadProbe

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var groups: ProxyGroupRepository

    @Inject lateinit var selections: SelectorSelectionStore

    @Inject lateinit var quality: ProfileQualityStore

    @Inject lateinit var budget: ProfileQualityBudgetLedger

    @Inject lateinit var controller: ServiceController

    @Inject lateinit var state: ServiceStateStore

    @Inject lateinit var applied: AppliedRuntimeConfigurationSource

    @Inject lateinit var coordinator: SelectorUrltestCoordinator

    @Inject lateinit var networkEpoch: PhysicalNetworkObserver

    @Inject lateinit var recovery: ProfileMutationRecoveryAccess

    @Inject lateinit var mutations: ProfileMutationRecoveryCoordinator

    @Inject internal lateinit var memberProbe: MemberLatencyProbe

    @Inject internal lateinit var scopeProvider: CurrentSelectorProbeScopeProvider

    @Before fun inject() = hiltRule.inject()

    @After fun closeOrigin() = origin.close()

    @Test
    fun healthySlowerNativeMemberLosesOnlyAfterSustainedVerifiedTransferAndAppliedAck() =
        runBlocking {
            withTimeout(120_000) {
                val original = settings.snapshot()
                val id = "owned-quality-selector"
                val observed = bindings as ObservedNativeRelayBindings
                val previous = observed.handles.toSet()
                ActivityScenario.launch(MainActivity::class.java).use {
                    NativeQualityRelayFixture(origin.port, 60).use { slow ->
                        NativeQualityRelayFixture(origin.port, 0).use { fast ->
                            try {
                                recovery.recover()
                                settings.update {
                                    setRipdpiMode(Mode.Proxy.preferenceValue)
                                    setEnableCmdSettings(false)
                                    setUdpAssociateEnabled(false)
                                    setRelayEnabled(false)
                                }
                                val members =
                                    listOf("slow" to slow, "fast" to fast).map { (member, peer) ->
                                        ProxyProfile.Shadowsocks(
                                            member,
                                            member,
                                            id,
                                            "127.0.0.1",
                                            peer.port,
                                            "aes-256-gcm",
                                            FixturePassword,
                                        )
                                    }
                                groups.add(ProxyGroup(id, id, ProxyGroupType.BASIC, 0, true, members = members))
                                selections.select(id, "slow")
                                assertTrue(controller.start(Mode.Proxy) is ServiceStartResult.Accepted)
                                state.status.first { it == AppStatus.Running to Mode.Proxy }
                                awaitApplied(id, "slow")
                                coordinator.stop(Mode.Proxy)
                                val policy = origin.policy()
                                groups.update(
                                    groups.list().single { it.id == id }.copy(
                                        failover = SelectorFailover(policy.probes.first().url, 60, 0, policy),
                                    ),
                                )
                                val group = groups.list().single { it.id == id }
                                withContext(Dispatchers.Default) {
                                    awaitStableNetworkEpoch(networkEpoch.changes, networkEpoch::capture)
                                }
                                var time = 0L
                                val prober =
                                    SelectorUrltestProber(
                                        selections,
                                        memberProbe,
                                        scopeProvider,
                                        monotonicMillis = { time },
                                        qualityStore = quality,
                                        reserveBudget = budget::reserve,
                                    )
                                prober.runProbePass(group, "unused", 0)
                                assertEquals("slow", selections.snapshot(id).profileId)
                                val first =
                                    quality.state.value.observations.values
                                        .filter { it.key.groupId == id }
                                assertEquals(2, first.size)
                                assertTrue(first.all { it.status == ProfileQualityStatus.Verified && it.loadedOverlap })
                                val slowBytes =
                                    requireNotNull(first.single { it.key.profileId == "slow" }.goodputBytesPerSecond)
                                val fastBytes =
                                    requireNotNull(first.single { it.key.profileId == "fast" }.goodputBytesPerSecond)
                                assertTrue(
                                    "Real streamed payload must distinguish link speeds",
                                    fastBytes > slowBytes * 1.2,
                                )
                                time += 60_000
                                prober.runProbePass(group, "unused", 0)
                                assertEquals("fast", selections.snapshot(id).profileId)
                                assertEquals(
                                    ProfileQualityDecisionReason.PromotedForGoodput,
                                    quality.state.value.decisions[id]
                                        ?.reason,
                                )
                                awaitApplied(id, "fast")
                                assertTrue(slow.relayedConnections.get() >= 6)
                                assertTrue(fast.relayedConnections.get() >= 6)
                                assertTrue(origin.methods.count { it == "GET" } >= 4)
                                assertTrue(origin.methods.count { it == "HEAD" } >= 8)
                            } finally {
                                cleanupGroup(id, original, observed, previous)
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun nativeFastBodyReportsPartialLoadAndCancellationRetiresOwnedHandle() =
        runBlocking {
            withTimeout(40_000) {
                val original = settings.snapshot()
                val observed = bindings as ObservedNativeRelayBindings
                val previous = observed.handles.toSet()
                NativeQualityRelayFixture(origin.port, 0).use { peer ->
                    val profile =
                        RelayProfileRecord(
                            id = "owned-quality-partial",
                            kind = RelayKindShadowsocks,
                            server = "127.0.0.1",
                            serverPort = peer.port,
                            udpEnabled = false,
                        )
                    val credentials =
                        RelayCredentialRecord(
                            profileId = profile.id,
                            shadowsocksMethod = "aes-256-gcm",
                            shadowsocksPassword = FixturePassword,
                        )
                    origin.bodyDelayMillis = 0
                    origin.loadedHeadDelayMillis = 700
                    val result = candidateProbe.measurePayload(profile, credentials, origin.policy())
                    assertTrue(result is CandidateRelayMeasurement.Succeeded)
                    val measured = requireNotNull((result as CandidateRelayMeasurement.Succeeded).quality)
                    assertEquals(ProfileQualityReason.LoadNotOverlapped, measured.reason)
                    assertEquals(262_144, measured.verifiedBytes.toInt())
                    assertEquals(null, measured.loadedLatencyMillis)
                    observed.assertRetiredSince(previous)
                    origin.methods.clear()
                    origin.bodyDelayMillis = 5_000
                    origin.loadedHeadDelayMillis = 0
                    val pending = launch { candidateProbe.measurePayload(profile, credentials, origin.policy()) }
                    while ("GET" !in origin.methods) delay(10)
                    pending.cancelAndJoin()
                    assertTrue(!candidateProbe.cleanupPending.value)
                    observed.assertRetiredSince(previous)
                    assertEquals(original, settings.snapshot())
                }
            }
        }

    private suspend fun cleanupGroup(
        id: String,
        original: AppSettings,
        observed: ObservedNativeRelayBindings,
        previous: Set<Long>,
    ) = withContext(NonCancellable) {
        try {
            withTimeout(15_000) {
                controller.stop()
                state.status.first { it.first == AppStatus.Halted }
            }
        } finally {
            try {
                observed.assertRetiredSince(previous)
            } finally {
                try {
                    withTimeout(10_000) {
                        groups.delete(mutations.captureMutation(ProfileMutationOrigin.ExplicitDeletion), id)
                    }
                } finally {
                    withTimeout(10_000) { settings.replace(original) }
                }
            }
        }
    }

    private suspend fun awaitApplied(
        groupId: String,
        memberId: String,
    ) {
        applied.applications.first { values ->
            val selected =
                (values[Mode.Proxy] as? RuntimeConfigurationApplication.Applied)
                    ?.configuration
                    ?.effectiveSelection
            selected?.selectorGroupId == groupId && selected.selectorMemberId == memberId
        }
    }
}
