package com.poyka.ripdpi.proxyimport

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
import com.poyka.ripdpi.data.RuntimeActivationPhase
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeAppliedUseReceipt
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeIntentLinearizer
import com.poyka.ripdpi.data.SelectorRelayProfileMapping
import com.poyka.ripdpi.data.fakes.FakeAppSettingsRepository
import com.poyka.ripdpi.data.fakes.FakeRelayCredentialStore
import com.poyka.ripdpi.data.fakes.FakeRelayProfileStore
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.selector.DefaultSelectorSelectionStore
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RuntimePolicyReloadIntent
import com.poyka.ripdpi.services.selector.SelectorReloadCoordinator
import com.poyka.ripdpi.services.selector.SelectorReloadRequest
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
    fun `a rejected automatic reload puts the selection back on the member carrying traffic`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            fixture.selections.resumeAutomatic("selector")
            fixture.applied.applications.value = liveApplication("a")
            val automatic = fixture.selections.snapshot("selector")
            assertTrue(fixture.selections.selectAutomatically("selector", automatic, "b") { true })
            val request = fixture.provider.selectionChanges().first()!!
            val rejecting = RejectingRefresher()
            val trigger = fixture.trigger(rejecting)

            trigger.hotReload(request)

            assertEquals(1, rejecting.refreshes)
            assertEquals("a", fixture.selections.snapshot("selector").profileId)
            assertFalse(fixture.selections.snapshot("selector").isManual)
            // The restored member is already live, so its own selection change replaces no stack.
            trigger.hotReload(fixture.provider.selectionChanges().first()!!)
            assertEquals(1, rejecting.refreshes)
        }

    @Test
    fun `a successful refresh without an applied revision does not switch the running member`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val request = requestAutomaticReserve(fixture)

            fixture.trigger(fixture.refresher).hotReload(request)

            assertEquals(1, fixture.refresher.refreshCount)
            assertEquals("a", fixture.selections.snapshot("selector").profileId)
        }

    @Test
    fun `handover rejection reconciles even if application settles after the timeout`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val request = requestAutomaticReserve(fixture)
            val reload = async { fixture.trigger(rejectDuringHandover(fixture)).hotReload(request) }
            runCurrent()
            advanceTimeBy(60_001L)

            assertFalse(reload.isCompleted)
            assertEquals("b", fixture.selections.snapshot("selector").profileId)
            fixture.applied.applications.value = liveApplication("a", revision = 2L)
            reload.await()

            assertEquals("a", fixture.selections.snapshot("selector").profileId)
        }

    @Test
    fun `a late rejection keeps a newer manual choice of the same member`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val request = requestAutomaticReserve(fixture)
            val reload = async { fixture.trigger(rejectDuringHandover(fixture)).hotReload(request) }
            runCurrent()
            fixture.select("selector", "b")
            val manual = fixture.selections.snapshot("selector")
            runCurrent()
            assertTrue(reload.isCompleted)
            fixture.applied.applications.value = liveApplication("a", revision = 2L)
            reload.await()

            assertTrue(manual.isManual)
            assertEquals(manual, fixture.selections.snapshot("selector"))
        }

    @Test
    fun `an explicit stop retires reconciliation while handover is still applying`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val request = requestAutomaticReserve(fixture)
            val reload = async { fixture.trigger(rejectDuringHandover(fixture)).hotReload(request) }
            runCurrent()
            assertFalse(reload.isCompleted)

            fixture.authority.reserveStop()
            runCurrent()

            assertTrue(reload.isCompleted)
            assertEquals("b", fixture.selections.snapshot("selector").profileId)
        }

    @Test
    fun `a handover that applies the requested reserve keeps its selection`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val request = requestAutomaticReserve(fixture)
            val reload = async { fixture.trigger(rejectDuringHandover(fixture)).hotReload(request) }
            runCurrent()
            fixture.applied.applications.value = liveApplication("b", revision = 2L)
            reload.await()

            assertEquals("b", fixture.selections.snapshot("selector").profileId)
        }

    @Test
    fun `a runtime timeout reconciles against the still applied member`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("a"), member("b")))), "a")
            val request = requestAutomaticReserve(fixture)
            val refresher =
                object : RunningRelayRefresher by fixture.refresher {
                    override suspend fun refresh(
                        intent: RuntimePolicyReloadIntent,
                        isCurrent: suspend () -> Boolean,
                    ): Boolean = withTimeout(1L) { awaitCancellation() }
                }

            fixture.trigger(refresher).hotReload(request)

            assertEquals("a", fixture.selections.snapshot("selector").profileId)
        }

    private suspend fun requestAutomaticReserve(fixture: Fixture): SelectorReloadRequest {
        fixture.selections.resumeAutomatic("selector")
        fixture.handoverStart = fixture.authority.reserveStart(Mode.VPN)
        fixture.applied.applications.value = liveApplication("a")
        val automatic = fixture.selections.snapshot("selector")
        assertTrue(fixture.selections.selectAutomatically("selector", automatic, "b") { true })
        return requireNotNull(fixture.provider.selectionChanges().first())
    }

    private fun rejectDuringHandover(fixture: Fixture): RunningRelayRefresher =
        object : RunningRelayRefresher by fixture.refresher {
            override suspend fun refresh(
                intent: RuntimePolicyReloadIntent,
                isCurrent: suspend () -> Boolean,
            ): Boolean {
                val before =
                    (fixture.applied.applications.value[Mode.VPN] as RuntimeConfigurationApplication.Applied)
                        .configuration
                val attempt =
                    RuntimeConfigurationAttempt(
                        before.runtimeId,
                        before.revision + 1,
                        before.mode,
                        before.requestedSelection,
                        RuntimeConfigurationApplyReason.NetworkHandover,
                        RuntimeAppliedIntent.Activation(fixture.handoverStart),
                        catalogGeneration = 0L,
                    )
                fixture.applied.applications.value =
                    mapOf(Mode.VPN to RuntimeConfigurationApplication.Applying(attempt, before))
                return false
            }
        }

    @Test
    fun `a rejected reload never restores a live member that a refresh removed`() =
        runTest {
            val fixture = fixture(listOf(selectorGroup(listOf(member("x"), member("d")))), "x")
            fixture.selections.resumeAutomatic("selector")
            fixture.applied.applications.value = liveApplication("x")
            // A refresh drops the live member and falls back to the group's default member.
            fixture.groups.update(selectorGroup(listOf(member("d"))))
            val automatic = fixture.selections.snapshot("selector")
            assertTrue(fixture.selections.selectAutomatically("selector", automatic, "d") { true })

            fixture.trigger(RejectingRefresher()).hotReload(fixture.provider.selectionChanges().first()!!)

            assertEquals("d", fixture.selections.snapshot("selector").profileId)
        }

    @Test
    fun `a rejected manual pick puts back the previous member and its automatic origin`() =
        runTest {
            val starts =
                manualPickInRoom(RejectingRefresher()) { selections ->
                    assertEquals("a", selections.snapshot("selector").profileId)
                    assertFalse(selections.snapshot("selector").isManual)
                    assertEquals(null, selections.manualReceipt("selector"))
                }
            assertEquals(emptyList<Mode>(), starts)
        }

    @Test
    fun `a manual pick whose server fails restarts the previous member`() =
        runTest {
            val stateStore = FakeServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) }
            val failing =
                object : RunningRelayRefresher {
                    override suspend fun refresh(
                        intent: RuntimePolicyReloadIntent,
                        isCurrent: suspend () -> Boolean,
                    ): Boolean {
                        stateStore.setStatus(AppStatus.Halted, Mode.VPN)
                        error("Relay server could not be reached")
                    }

                    override suspend fun teardown() = Unit
                }

            val starts =
                manualPickInRoom(failing, stateStore) { selections ->
                    assertEquals("a", selections.snapshot("selector").profileId)
                }

            assertEquals(listOf(Mode.VPN), starts)
        }

    private suspend fun manualPickInRoom(
        refresher: RunningRelayRefresher,
        stateStore: FakeServiceStateStore = FakeServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) },
        verify: suspend (com.poyka.ripdpi.data.selector.SelectorSelectionStore) -> Unit,
    ): List<Mode> {
        val persistence =
            com.poyka.ripdpi.data.testing.RoomProfileFixture(
                androidx.test.core.app.ApplicationProvider
                    .getApplicationContext(),
                com.poyka.ripdpi.data
                    .testPauseAuthority(),
            )
        try {
            persistence.initialize()
            persistence.groups.add(selectorGroup(listOf(member("a"), member("b"))))
            val selections = persistence.selectorSelections
            assertTrue(selections.selectAutomatically("selector", selections.snapshot("selector"), "a") { true })
            persistence.selectorActiveGroup.restoreActiveGroup("selector")
            selections.select("selector", "b")
            val receipt = selections.manualReceipt("selector") ?: error("Manual pick published no reservation")
            val controller = RestartRecordingController()
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groupRepository = persistence.groups,
                    selections = selections,
                    authority = persistence.authority,
                    stateStore = stateStore,
                    relayRefresher = refresher,
                    selectionProvider =
                        ActiveSelectorSelectionProvider(
                            persistence.groups,
                            selections,
                            persistence.selectorActiveGroup,
                        ),
                    applied =
                        com.poyka.ripdpi.activities
                            .FakeAppliedRuntimeConfigurationSource(),
                    switches = persistence.coordinator,
                    failedSwitchRestorer = restorer(stateStore, controller),
                )

            trigger.hotReload(SelectorReloadRequest("selector", "b", receipt))

            verify(selections)
            assertEquals("selector", persistence.selectorActiveGroup.snapshotActiveGroupId())
            return controller.starts
        } finally {
            persistence.close()
        }
    }

    private class RestartRecordingController : com.poyka.ripdpi.services.TestSynchronousServiceController() {
        val starts = mutableListOf<Mode>()

        override fun recordStart(
            mode: Mode,
            receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt?,
        ): com.poyka.ripdpi.services.ServiceStartResult {
            starts += mode
            return com.poyka.ripdpi.services.ServiceStartResult
                .Accepted(checkNotNull(receipt))
        }

        override fun recordStop() = Unit
    }

    private class RejectingRefresher : RunningRelayRefresher {
        var refreshes = 0

        override suspend fun refresh(
            intent: RuntimePolicyReloadIntent,
            isCurrent: suspend () -> Boolean,
        ): Boolean {
            refreshes++
            return false
        }

        override suspend fun teardown() = Unit
    }

    private fun liveApplication(
        memberId: String,
        revision: Long = 1L,
    ) = mapOf(
        Mode.VPN to
            com.poyka.ripdpi.data.RuntimeConfigurationApplication.Applied(
                com.poyka.ripdpi.data.AppliedRuntimeConfiguration(
                    runtimeId = "runtime",
                    revision = revision,
                    appliedAt = 1,
                    mode = Mode.VPN,
                    requestedSelection = liveSelection(memberId),
                    effectiveSelection = liveSelection(memberId),
                    dns =
                        com.poyka.ripdpi.data
                            .RuntimeConfigurationDns("plain", "system"),
                    strategy =
                        com.poyka.ripdpi.data
                            .RuntimeConfigurationStrategy(false),
                    reason = com.poyka.ripdpi.data.RuntimeConfigurationApplyReason.InitialStart,
                ),
            ),
    )

    private fun liveSelection(memberId: String) =
        com.poyka.ripdpi.data.RuntimeConfigurationSelection(
            provider = "native",
            relayKind = "trojan",
            profileId = memberId,
            selectorGroupId = "selector",
            selectorMemberId = memberId,
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
                    ): Boolean {
                        deliveredIntent = intent
                        fixture.groups.update(selectorGroup(listOf(original.copy(server = "replacement.example.com"))))
                        guardAccepted = isCurrent()
                        return guardAccepted == true
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
            val listener =
                SelectorReloadModule.provideSelectorReloadLifecycleListener(
                    coordinator,
                    fixture.trigger,
                    fixture.groups,
                )
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

            // A manual pick pins its member until the user returns the group to automatic selection.
            fixture.selections.resumeAutomatic("selector")
            val expected = fixture.selections.snapshot("selector")
            assertTrue(fixture.selections.selectAutomatically("selector", expected, "b") { true })
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
        private val persistence =
            com.poyka.ripdpi.data.fakes
                .InMemorySelectorPersistence()
        val activeGroup = SelectorActiveGroupStore(persistence, authority)
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
            DefaultSelectorSelectionStore(
                persistence,
                preparations,
                authority,
                activeGroup,
            )
        val groups = FakeProxyGroupRepository()
        val profiles = FakeRelayProfileStore()
        val credentials = FakeRelayCredentialStore()
        val settings = FakeAppSettingsRepository()
        val stateStore = FakeServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) }
        lateinit var handoverStart: RuntimeActivationReceipt
        val provider = ActiveSelectorSelectionProvider(groups, selections, activeGroup)
        val applied =
            com.poyka.ripdpi.activities
                .FakeAppliedRuntimeConfigurationSource()
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
                selections.manualReceipt(groupId)
                    ?: error("Manual choice did not publish a typed reservation")
            assertTrue(authority.isCurrent(receipt))
            assertEquals(RuntimeActivationPhase.Unbound, authority.snapshotAuthority().command!!.phase)
            assertEquals(groupId, activeGroup.snapshotActiveGroupId())
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
                applied = applied,
                // The in-memory manual pick keeps no before-image; the Room-backed case covers the revert.
                switches =
                    object : com.poyka.ripdpi.data.RejectedSwitchReverter {
                        override suspend fun revertRejectedSwitch(receipt: DurableCommandReceipt) = false
                    },
                failedSwitchRestorer = restorer(stateStore),
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
        ): Boolean {
            if (!isCurrent()) return false
            selections += fixture.transientSelection()
            intents += intent
            return true
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
}

private fun restorer(
    stateStore: com.poyka.ripdpi.data.ServiceStateStore,
    controller: com.poyka.ripdpi.services.TestSynchronousServiceController =
        object : com.poyka.ripdpi.services.TestSynchronousServiceController() {
            override fun recordStart(
                mode: Mode,
                receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt?,
            ) = error("Unexpected restart")

            override fun recordStop() = Unit
        },
) = com.poyka.ripdpi.services
    .FailedSwitchRestorer(controller, stateStore, controller.intentArbiter)
