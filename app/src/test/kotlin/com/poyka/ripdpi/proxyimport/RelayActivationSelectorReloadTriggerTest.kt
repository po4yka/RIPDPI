package com.poyka.ripdpi.proxyimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityPersistence
import com.poyka.ripdpi.data.PauseAuthorityState
import com.poyka.ripdpi.data.PauseClock
import com.poyka.ripdpi.data.PauseClockReading
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PauseMutationPreparationSource
import com.poyka.ripdpi.data.ProfileActivationReceipt
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationPreparation
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RuntimeActivationPhase
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeAppliedUseReceipt
import com.poyka.ripdpi.data.RuntimeIntentLinearizer
import com.poyka.ripdpi.data.SelectorRelayProfileMapping
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SharedPreferencesSelectorSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RuntimePolicyReloadIntent
import com.poyka.ripdpi.services.selector.SelectorReloadCoordinator
import com.poyka.ripdpi.services.selector.SelectorReloadRequest
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Exercises production selection publication and reload wiring without starting an Android runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RelayActivationSelectorReloadTriggerTest {
    private fun member(
        id: String,
        groupId: String = "selector",
    ) = ProxyProfile.Trojan(
        id = id,
        displayName = id,
        groupId = groupId,
        server = "$id.example.com",
        serverPort = 443,
        password = "pw-$id",
    )

    private fun selectorGroup(
        members: List<ProxyProfile>,
        id: String = "selector",
    ) = ProxyGroup(
        id = id,
        name = "Selector",
        type = ProxyGroupType.SUBSCRIPTION,
        order = 0,
        isSelector = true,
        members = members,
    )

    @Test
    fun `same id profile replacement rejects in flight runtime reload`() =
        runTest {
            val original = member("b")
            val fixture = fixture(listOf(selectorGroup(listOf(original))), "b")
            val request = fixture.provider.selectionChanges().first()!!
            var guardAccepted: Boolean? = null
            var deliveredIntent: RuntimePolicyReloadIntent? = null
            val refresher =
                object : RunningRelayRefresher {
                    override suspend fun refresh(
                        intent: RuntimePolicyReloadIntent,
                        isCurrent: suspend () -> Boolean,
                    ) {
                        deliveredIntent = intent
                        fixture.groups.update(selectorGroup(listOf(original.copy(server = "replacement.example.com"))))
                        guardAccepted = isCurrent()
                    }

                    override suspend fun teardown() = Unit
                }
            fixture.trigger(refresher).hotReload(request)
            assertFalse(guardAccepted ?: error("Runtime refresh was never requested"))
            assertExplicitReceipt(request, deliveredIntent ?: error("Missing reload intent"))
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `hotReload activates the selected member and refreshes the runtime`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "b")
            val request = fixture.provider.selectionChanges().first()!!

            fixture.trigger.hotReload(request)

            // Selector activation is transient; the native standalone profile and credentials stay intact.
            fixture.assertTransientSelection("selector", "b")
            assertEquals(
                "b.example.com",
                fixture.refresher.selections
                    .single()
                    .profile.server,
            )
            assertTrue(
                fixture.refresher.selections
                    .single()
                    .profile.kind
                    .isNotBlank(),
            )
            assertExplicitReceipt(request, fixture.refresher.intents.single())
            assertEquals(1, fixture.refresher.refreshCount)
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `an unknown member id neither activates nor refreshes`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a")))), "b")
            val originalReceipt = fixture.selections.manualReceipt("selector") as ProfileActivationReceipt
            val before = fixture.authority.snapshotAuthority()

            fixture.trigger.hotReload(SelectorReloadRequest("selector", "ghost", originalReceipt))
            // Also exercise a selected but unavailable member, beyond the initial choice mismatch guard.
            fixture.trigger.hotReload(SelectorReloadRequest("selector", "b", originalReceipt))

            assertEquals(0, fixture.refresher.refreshCount)
            assertEquals(before, fixture.authority.snapshotAuthority())
            assertEquals(null, fixture.profiles.load("ghost"))
            assertEquals(null, fixture.profiles.load("b"))
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `a selection change through the coordinator drives the trigger`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val coordinator =
                SelectorReloadCoordinator(backgroundScope, fixture.provider.selectionChanges(), fixture.trigger)

            coordinator.start(Mode.VPN)
            runCurrent()
            // Seed value must not reload.
            assertEquals(0, fixture.refresher.refreshCount)

            val request = fixture.select("selector", "b")
            runCurrent()

            fixture.assertTransientSelection("selector", "b")
            assertExplicitReceipt(request, fixture.refresher.intents.single())
            assertEquals(1, fixture.refresher.refreshCount)
            assertEquals(0, fixture.refresher.teardownCount)
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `reconstructed manual choice has metadata but emits no live reload command`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val before = fixture.authority.snapshotAuthority()
            fixture.activeGroup.commitMember(
                "selector",
                "b",
                com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Reconstruction,
            )
            assertEquals(true, fixture.selections.snapshot("selector").isManual)
            assertEquals(null, fixture.selections.manualReceipt("selector"))
            assertEquals(null, fixture.provider.selectionChanges().first())
            val coordinator =
                SelectorReloadCoordinator(backgroundScope, fixture.provider.selectionChanges(), fixture.trigger)
            coordinator.start(Mode.VPN)
            runCurrent()
            assertEquals(0, fixture.refresher.refreshCount)
            assertEquals(0, fixture.refresher.teardownCount)
            assertEquals(before, fixture.authority.snapshotAuthority())
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `prepare applies persisted choice without refreshing or starting service`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "b")
            val before = fixture.authority.snapshotAuthority()
            fixture.trigger.prepare()
            fixture.trigger.afterStart()
            fixture.assertTransientSelection("selector", "b")
            assertEquals(before, fixture.authority.snapshotAuthority())
            assertEquals(0, fixture.refresher.refreshCount)
            assertEquals(0, fixture.refresher.teardownCount)
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `choice changing during startup is applied after session registration`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            fixture.trigger.prepare()
            fixture.trigger.afterStart()
            assertEquals(0, fixture.refresher.refreshCount)
            val request = fixture.select("selector", "b")
            fixture.trigger.afterStart()
            fixture.assertTransientSelection("selector", "b")
            assertExplicitReceipt(request, fixture.refresher.intents.single())
            assertEquals(1, fixture.refresher.refreshCount)
            assertEquals(0, fixture.refresher.teardownCount)
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `lifecycle preparation establishes seed before a delayed watcher can miss selection`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val coordinator =
                SelectorReloadCoordinator(backgroundScope, fixture.provider.selectionChanges(), fixture.trigger)
            val listener = SelectorReloadModule.provideSelectorReloadLifecycleListener(coordinator, fixture.trigger)
            listener.start(Mode.VPN)
            // Intentionally do not runCurrent before production preparation.
            listener.prepare()
            listener.afterStart()
            assertEquals(0, fixture.refresher.refreshCount)
            val request = fixture.select("selector", "b")
            runCurrent()
            fixture.assertTransientSelection("selector", "b")
            assertExplicitReceipt(request, fixture.refresher.intents.single())
            assertEquals(1, fixture.refresher.refreshCount)
            assertEquals(0, fixture.refresher.teardownCount)
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `group switch with shared member id reloads the new group`() =
        runTest {
            val a = member("shared", "groupA").copy(server = "a.example.com", password = "fixture-group-a-password")
            val b = member("shared", "groupB").copy(server = "b.example.com", password = "fixture-group-b-password")
            val fixture = fixture(listOf(selectorGroup(listOf(a), "groupA"), selectorGroup(listOf(b), "groupB")))
            val oldRequest = fixture.select("groupA", "shared")
            val coordinator =
                SelectorReloadCoordinator(backgroundScope, fixture.provider.selectionChanges(), fixture.trigger)
            coordinator.start(Mode.VPN)
            runCurrent()
            assertEquals(0, fixture.refresher.refreshCount)

            val newRequest = fixture.select("groupB", "shared")
            runCurrent()

            fixture.assertTransientSelection("groupB", "shared")
            assertEquals(mapRelayProfile(b), fixture.refresher.selections.single())
            assertExplicitReceipt(newRequest, fixture.refresher.intents.single())
            assertEquals(1, fixture.refresher.refreshCount)
            val before = fixture.authority.snapshotAuthority()
            fixture.trigger.hotReload(oldRequest)
            assertEquals(before, fixture.authority.snapshotAuthority())
            assertEquals(1, fixture.refresher.refreshCount)
            assertEquals(0, fixture.refresher.teardownCount)
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `same member with new manual reservation reloads using its original receipt`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("b")))), "b")
            val seedRequest = fixture.provider.selectionChanges().first()!!
            val coordinator =
                SelectorReloadCoordinator(backgroundScope, fixture.provider.selectionChanges(), fixture.trigger)
            coordinator.start(Mode.VPN)
            runCurrent()
            assertEquals(0, fixture.refresher.refreshCount)

            val first = fixture.select("selector", "b")
            runCurrent()
            assertExplicitReceipt(first, fixture.refresher.intents.single())
            val second = fixture.select("selector", "b")
            assertNotEquals(first.manualReceipt!!.commandId, second.manualReceipt!!.commandId)
            runCurrent()

            assertEquals(2, fixture.refresher.refreshCount)
            assertExplicitReceipt(second, fixture.refresher.intents.last())
            val before = fixture.authority.snapshotAuthority()
            fixture.trigger.hotReload(seedRequest)
            fixture.trigger.hotReload(first)
            assertEquals(before, fixture.authority.snapshotAuthority())
            assertEquals(2, fixture.refresher.refreshCount)
            fixture.assertTransientSelection("selector", "b")
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `stale manual event cannot borrow the newer reservation for the same member`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("b")))), "b")
            val oldRequest = fixture.provider.selectionChanges().first()!!
            val currentRequest = fixture.select("selector", "b")
            val before = fixture.authority.snapshotAuthority()

            fixture.trigger.hotReload(oldRequest)

            assertEquals(0, fixture.refresher.refreshCount)
            assertEquals(before, fixture.authority.snapshotAuthority())
            assertEquals(RuntimeActivationPhase.Unbound, before.command!!.phase)
            fixture.trigger.hotReload(currentRequest)
            assertEquals(1, fixture.refresher.refreshCount)
            assertExplicitReceipt(currentRequest, fixture.refresher.intents.single())
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `watcher cannot bind or dispatch a measured selection before its proof owner`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a")))))
            val reference = ProfileUtilityReference.SelectorMember("selector", "a")
            val generation = fixture.authority.profileUtility.replaceCatalog(setOf(reference))
            val receipt =
                checkNotNull(
                    fixture.authority.reserveMeasuredActivation(
                        fixture.authority.snapshotAuthority(),
                        generation,
                        reference,
                        "measured-owner",
                    ),
                )
            fixture.activeGroup.commitMember(
                "selector",
                "a",
                com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                    .Manual(receipt),
            )
            val captured = fixture.authority.snapshotAuthority()
            fixture.trigger.hotReload(SelectorReloadRequest("selector", "a", receipt))
            assertEquals(captured, fixture.authority.snapshotAuthority())
            assertEquals(emptyList<RuntimePolicyReloadIntent>(), fixture.refresher.intents)
            assertEquals(0, fixture.refresher.refreshCount)
            assertEquals(0, fixture.refresher.teardownCount)
            assertNotNull(fixture.authority.bindProfileActivation(receipt, Mode.VPN))
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `automatic selection continues only from an acknowledged activation`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            fixture.trigger.hotReload(fixture.provider.selectionChanges().first()!!)
            val original =
                RuntimeAppliedIntent.Activation(
                    (fixture.refresher.intents.single() as RuntimePolicyReloadIntent.Explicit).receipt,
                )
            val identity = RuntimeAppliedUseIdentity("selector-runtime", 1L, Mode.VPN.preferenceValue)
            val selectedReference = ProfileUtilityReference.SelectorMember("selector", "a")
            val automaticReference = ProfileUtilityReference.SelectorMember("selector", "b")
            val generation =
                fixture.authority.profileUtility.replaceCatalog(
                    setOf(selectedReference, automaticReference),
                )
            assertTrue(fixture.authority.claimActivation(original.receipt, identity))
            val positive =
                RuntimeAppliedUseReceipt(
                    identity,
                    listOf(selectedReference),
                    1_800_000_000_000L,
                    generation,
                    true,
                    "a".repeat(64),
                )
            assertTrue(fixture.authority.acknowledgeApplied(original, positive))
            assertEquals(positive, fixture.authority.acknowledgedAttempt(original, identity))
            assertTrue(
                fixture.authority
                    .snapshotAuthority()
                    .command!!
                    .phase is RuntimeActivationPhase.Applied,
            )

            val expected = fixture.selections.snapshot("selector")
            assertTrue(fixture.selections.selectAutomatically("selector", expected, "b"))
            val automatic = fixture.provider.selectionChanges().first()!!
            assertEquals(null, automatic.manualReceipt)
            fixture.trigger.hotReload(automatic)

            assertEquals(2, fixture.refresher.refreshCount)
            assertSame(RuntimePolicyReloadIntent.Automatic, fixture.refresher.intents.last())
            fixture.assertTransientSelection("selector", "b")
            val nextIdentity = identity.copy(revision = 2L)
            val continuation = RuntimeAppliedIntent.Continuation(original.receipt, identity)
            assertTrue(fixture.authority.claimContinuation(continuation.receipt, identity, nextIdentity))
            val continued =
                positive.copy(
                    identity = nextIdentity,
                    references = listOf(automaticReference),
                    appliedAtMillis = positive.appliedAtMillis + 1L,
                    recordsUse = false,
                    payloadFingerprint = "b".repeat(64),
                )
            assertTrue(fixture.authority.acknowledgeApplied(continuation, continued))
            assertEquals(continued, fixture.authority.acknowledgedAttempt(continuation, nextIdentity))
            assertEquals(
                listOf(selectedReference),
                fixture.authority.states.value!!.profileUtility.recents.map {
                    it.reference
                },
            )
            assertEquals(0, fixture.refresher.teardownCount)
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    @Test
    fun `teardown delegates exactly once without changing standalone native stores`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("b")))), "b")
            fixture.trigger.teardown()
            assertEquals(1, fixture.refresher.teardownCount)
            assertEquals(0, fixture.refresher.refreshCount)
            fixture.assertStandaloneNativeStoresUnchanged()
        }

    private fun assertExplicitReceipt(
        request: SelectorReloadRequest,
        intent: RuntimePolicyReloadIntent,
    ) {
        assertTrue(intent is RuntimePolicyReloadIntent.Explicit)
        val original = request.manualReceipt ?: error("Missing original manual reservation")
        val bound = (intent as RuntimePolicyReloadIntent.Explicit).receipt
        assertEquals(original.commandId, bound.commandId)
        assertEquals(original.authority, bound.authority)
        assertEquals(original.origin, bound.origin)
        assertEquals(Mode.VPN, bound.mode)
    }

    private suspend fun fixture(
        groups: List<ProxyGroup>,
        selected: String? = null,
    ): Fixture =
        Fixture().apply {
            groups.forEach { this.groups.add(it) }
            seedStandaloneNativeStores()
            if (selected != null) select("selector", selected)
        }

    private class Fixture {
        val authority =
            PauseIntentAuthority(
                object : PauseAuthorityPersistence {
                    private var state: PauseAuthorityState? = null

                    override fun read() = state

                    override fun commit(state: PauseAuthorityState) {
                        this.state = state
                    }
                },
                object : PauseClock {
                    override fun read() = PauseClockReading(1_800_000_000_000L, 10_000L, 7)
                },
                RuntimeIntentLinearizer(),
            ).apply { initializeAfterMigration() }
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val activeGroup = SelectorActiveGroupStore(context, authority)
        private val preparations =
            object : PauseMutationPreparationSource {
                override suspend fun captureMutation(origin: ProfileMutationOrigin) =
                    ProfileMutationPreparation(origin, authority.reference())

                override suspend fun activateSelector(
                    preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
                    groupId: String,
                    memberId: String,
                    choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
                ): com.poyka.ripdpi.data.ProfileMutationOutcome {
                    val outcome = commitMutationIntent(preparation)
                    val receipt =
                        (outcome as? com.poyka.ripdpi.data.ProfileMutationOutcome.Reserved)?.receipt
                            as? com.poyka.ripdpi.data.ProfileActivationReceipt
                    if (receipt != null) {
                        choice.commitMember(
                            groupId,
                            memberId,
                            com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                                .Manual(receipt),
                        )
                    }
                    return outcome
                }

                override suspend fun commitMutationIntent(preparation: ProfileMutationPreparation) =
                    authority.invalidateForMutation(
                        preparation.origin,
                        UUID.randomUUID().toString(),
                        preparation.expectedPauseAuthority,
                    )

                override suspend fun <T> mutateCatalog(
                    preparation: ProfileMutationPreparation,
                    block: suspend () -> T,
                ): T {
                    check(authority.reference() == preparation.expectedPauseAuthority)
                    return block()
                }

                override suspend fun <T> mutateReservedCatalog(
                    receipt: DurableCommandReceipt,
                    block: suspend () -> T,
                ): T {
                    check(authority.isCurrent(receipt))
                    return block()
                }
            }
        val selections =
            SharedPreferencesSelectorSelectionStore(
                context,
                preparations,
                authority,
                activeGroup,
            )
        val groups = FakeProxyGroupRepository()
        val profiles = FakeRelayProfileStore()
        val credentials = FakeRelayCredentialStore()
        val settings = FakeAppSettingsRepository()
        val stateStore = FakeServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) }
        val provider = ActiveSelectorSelectionProvider(groups, selections, activeGroup)
        val refresher = RecordingRelayRefresher(this)
        val trigger = trigger(refresher)
        private lateinit var standalone: SelectorRelayProfileMapping
        private lateinit var originalSettings: AppSettings

        suspend fun select(
            groupId: String,
            memberId: String,
        ): SelectorReloadRequest {
            selections.select(groupId, memberId)
            val receipt =
                selections.manualReceipt(groupId) as? ProfileActivationReceipt
                    ?: error("Manual choice did not publish a typed reservation")
            assertTrue(authority.isCurrent(receipt))
            assertEquals(RuntimeActivationPhase.Unbound, authority.snapshotAuthority().command!!.phase)
            assertEquals(groupId, activeGroup.activeGroupId.value)
            return SelectorReloadRequest(groupId, memberId, receipt)
        }

        fun trigger(refresher: RunningRelayRefresher) =
            RelayActivationSelectorReloadTrigger(
                groupRepository = groups,
                selections = selections,
                authority = authority,
                stateStore = stateStore,
                relayRefresher = refresher,
                selectionProvider = provider,
            )

        suspend fun transientSelection(): SelectorRelayProfileMapping {
            val groupId = provider.activeGroupId() ?: error("No active selector group")
            val memberId = selections.snapshot(groupId).profileId ?: error("No selected member")
            val member =
                groups
                    .list()
                    .single { it.id == groupId }
                    .members
                    .single { it.id == memberId }
            check(member.groupId == groupId)
            return mapRelayProfile(member) ?: error("Selected member is not relay activatable")
        }

        suspend fun assertTransientSelection(
            groupId: String,
            memberId: String,
        ) {
            assertEquals(groupId, provider.activeGroupId())
            assertEquals(memberId, selections.snapshot(groupId).profileId)
            val request = provider.selectionChanges().first()!!
            assertEquals(groupId, request.groupId)
            assertEquals(memberId, request.memberId)
            assertSame(selections.manualReceipt(groupId), request.manualReceipt)
            assertNotNull(transientSelection())
        }

        suspend fun seedStandaloneNativeStores() {
            standalone =
                mapRelayProfile(
                    ProxyProfile.Trojan(
                        id = "standalone",
                        displayName = "Standalone",
                        groupId = "native",
                        server = "standalone.example.com",
                        serverPort = 443,
                        password = "fixture-standalone-password",
                    ),
                )!!
            profiles.save(standalone.profile)
            credentials.save(standalone.credentials)
            settings.update {
                setRelayEnabled(true)
                setRelayProfileId(standalone.profile.id)
                setRelayKind(standalone.profile.kind)
                setRelayServer(standalone.profile.server)
                setRelayServerPort(standalone.profile.serverPort)
            }
            originalSettings = settings.snapshot()
        }

        suspend fun assertStandaloneNativeStoresUnchanged() {
            assertEquals(listOf(standalone.profile), profiles.list())
            assertEquals(mapOf(standalone.credentials.profileId to standalone.credentials), credentials.snapshot())
            assertEquals(originalSettings, settings.snapshot())
        }
    }

    /** Observes transient selection at guarded dispatch; no native-store writes or fabricated runtime ACKs. */
    private class RecordingRelayRefresher(
        private val fixture: Fixture,
    ) : RunningRelayRefresher {
        val intents = mutableListOf<RuntimePolicyReloadIntent>()
        val selections = mutableListOf<SelectorRelayProfileMapping>()
        val refreshCount: Int get() = intents.size
        var teardownCount = 0

        override suspend fun refresh(
            intent: RuntimePolicyReloadIntent,
            isCurrent: suspend () -> Boolean,
        ) {
            if (!isCurrent()) return
            selections += fixture.transientSelection()
            intents += intent
        }

        override suspend fun teardown() {
            teardownCount += 1
        }
    }

    private class FakeProxyGroupRepository : ProxyGroupRepository {
        private val state = MutableStateFlow<List<ProxyGroup>>(emptyList())

        override suspend fun add(group: ProxyGroup) {
            state.value = state.value.filterNot { it.id == group.id } + group
        }

        override suspend fun update(group: ProxyGroup) {
            state.value = state.value.map { if (it.id == group.id) group else it }
        }

        override suspend fun replaceAll(
            receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
            groups: List<ProxyGroup>,
        ) {
            val preparation =
                com.poyka.ripdpi.data.ProfileMutationPreparation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.Compensation,
                    receipt.authority,
                )
            list().forEach { delete(preparation, it.id) }
            groups.forEach { add(it) }
        }

        override suspend fun compensateReplacement(groups: List<ProxyGroup>) {
            state.value = groups
        }

        override suspend fun delete(
            preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
            id: String,
        ) {
            state.value = state.value.filterNot { it.id == id }
        }

        override suspend fun list(): List<ProxyGroup> = state.value

        override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
    }

    private class FakeRelayProfileStore : RelayProfileStore {
        private val profiles = mutableMapOf<String, RelayProfileRecord>()

        override suspend fun load(profileId: String): RelayProfileRecord? = profiles[profileId]

        override suspend fun list(): List<RelayProfileRecord> = profiles.values.toList()

        override suspend fun save(profile: RelayProfileRecord) {
            profiles[profile.id] = profile
        }

        override suspend fun clear(profileId: String) {
            profiles.remove(profileId)
        }
    }

    private class FakeRelayCredentialStore : RelayCredentialStore {
        private val credentials = mutableMapOf<String, RelayCredentialRecord>()

        fun snapshot(): Map<String, RelayCredentialRecord> = credentials.toMap()

        override suspend fun load(profileId: String): RelayCredentialRecord? = credentials[profileId]

        override suspend fun save(credentials: RelayCredentialRecord) {
            this.credentials[credentials.profileId] = credentials
        }

        override suspend fun clear(profileId: String) {
            credentials.remove(profileId)
        }
    }

    private class FakeAppSettingsRepository : AppSettingsRepository {
        private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)

        override val settings: Flow<AppSettings> = state.asStateFlow()

        override suspend fun snapshot(): AppSettings = settings.first()

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            state.value =
                state.value
                    .toBuilder()
                    .apply(transform)
                    .build()
        }

        override suspend fun replace(settings: AppSettings) {
            state.value = settings
        }
    }
}
