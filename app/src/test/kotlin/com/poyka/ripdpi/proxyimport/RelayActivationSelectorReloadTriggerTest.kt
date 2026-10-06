package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.selector.SelectorReloadCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (B) Wiring `SelectorReloadCoordinator` into production: a selection change must
 * drive a real hot-reload through the production [RelayActivationSelectorReloadTrigger]
 * — activating the selected member as the live relay and refreshing the runtime
 * (audit finding P1-8). The relay activation uses the real [RelayProfileActivator]
 * over in-memory stores; the running-runtime refresh is a fake so no Android
 * service is needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayActivationSelectorReloadTriggerTest {
    private fun member(id: String) =
        ProxyProfile.Trojan(
            id = id,
            displayName = id,
            groupId = "selector",
            server = "$id.example.com",
            serverPort = 443,
            password = "pw-$id",
        )

    private fun selectorGroup(members: List<ProxyProfile>) =
        ProxyGroup(
            id = "selector",
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
            val groups = FakeProxyGroupRepository().apply { add(selectorGroup(listOf(original))) }
            val selections = TestSelections("b")
            var guardAccepted: Boolean? = null
            val refresher =
                object : RunningRelayRefresher {
                    override suspend fun refresh(isCurrent: suspend () -> Boolean) {
                        groups.update(selectorGroup(listOf(original.copy(server = "replacement.example.com"))))
                        guardAccepted = isCurrent()
                    }

                    override suspend fun teardown() = Unit
                }
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groups,
                    RelayProfileActivator(
                        com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                            FakeRelayProfileStore(),
                            FakeRelayCredentialStore(),
                            FakeAppSettingsRepository(),
                        ),
                    ),
                    refresher,
                    ActiveSelectorSelectionProvider(groups, selections),
                )
            trigger.hotReload("b")
            assertFalse(guardAccepted ?: error("Runtime refresh was never requested"))
        }

    @Test
    fun `hotReload activates the selected member and refreshes the runtime`() =
        runTest {
            val groups = FakeProxyGroupRepository().apply { add(selectorGroup(listOf(member("a"), member("b")))) }
            val profileStore = FakeRelayProfileStore()
            val settings = FakeAppSettingsRepository()
            val refresher = RecordingRelayRefresher()
            val selections = TestSelections("b")
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groupRepository = groups,
                    relayProfileActivator =
                        RelayProfileActivator(
                            com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                                profiles = profileStore,
                                credentials = FakeRelayCredentialStore(),
                                settings = settings,
                            ),
                        ),
                    relayRefresher = refresher,
                    selectionProvider = ActiveSelectorSelectionProvider(groups, selections),
                )

            trigger.hotReload("b")

            // The selected member's relay record is persisted under its id (proving
            // activation occurred through RelayProfileActivator),
            assertEquals("b.example.com", profileStore.load("b")?.server)
            assertTrue(profileStore.load("b")?.kind?.isNotBlank() == true)
            // and the running runtime was asked to refresh exactly once.
            assertEquals(1, refresher.refreshCount)
        }

    @Test
    fun `an unknown member id neither activates nor refreshes`() =
        runTest {
            val groups = FakeProxyGroupRepository().apply { add(selectorGroup(listOf(member("a")))) }
            val profileStore = FakeRelayProfileStore()
            val refresher = RecordingRelayRefresher()
            val selections = TestSelections("b")
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groupRepository = groups,
                    relayProfileActivator =
                        RelayProfileActivator(
                            com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                                profiles = profileStore,
                                credentials = FakeRelayCredentialStore(),
                                settings = FakeAppSettingsRepository(),
                            ),
                        ),
                    relayRefresher = refresher,
                    selectionProvider = ActiveSelectorSelectionProvider(groups, selections),
                )

            trigger.hotReload("ghost")

            assertEquals(0, refresher.refreshCount)
            // Nothing was activated for an unknown member.
            assertTrue(profileStore.list().isEmpty())
        }

    @Test
    fun `a selection change through the coordinator drives the trigger`() =
        runTest {
            val groups = FakeProxyGroupRepository().apply { add(selectorGroup(listOf(member("a"), member("b")))) }
            val profileStore = FakeRelayProfileStore()
            val refresher = RecordingRelayRefresher()
            val selections = TestSelections("b")
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groupRepository = groups,
                    relayProfileActivator =
                        RelayProfileActivator(
                            com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                                profiles = profileStore,
                                credentials = FakeRelayCredentialStore(),
                                settings = FakeAppSettingsRepository(),
                            ),
                        ),
                    relayRefresher = refresher,
                    selectionProvider = ActiveSelectorSelectionProvider(groups, selections),
                )
            selections.select("selector", "a")
            val selection = selections.state
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectedProfileId = selection,
                    trigger = trigger,
                )

            coordinator.start(Mode.VPN)
            runCurrent()
            // Seed value must not reload.
            assertEquals(0, refresher.refreshCount)

            selection.value = "b"
            runCurrent()

            assertEquals("b.example.com", profileStore.load("b")?.server)
            assertEquals(1, refresher.refreshCount)
        }

    @Test
    fun `prepare applies persisted choice without refreshing or starting service`() =
        runTest {
            val groups = FakeProxyGroupRepository().apply { add(selectorGroup(listOf(member("a"), member("b")))) }
            val profiles = FakeRelayProfileStore()
            val settings = FakeAppSettingsRepository()
            val refresher = RecordingRelayRefresher()
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groups,
                    RelayProfileActivator(
                        com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                            profiles,
                            FakeRelayCredentialStore(),
                            settings,
                        ),
                    ),
                    refresher,
                    ActiveSelectorSelectionProvider(groups, TestSelections("b")),
                )
            trigger.prepare()
            assertEquals("b", settings.snapshot().relayProfileId)
            assertEquals("b.example.com", profiles.load("b")?.server)
            assertEquals(0, refresher.refreshCount)
            assertEquals(0, refresher.teardownCount)
        }

    @Test
    fun `choice changing during startup is applied after session registration`() =
        runTest {
            val groups = FakeProxyGroupRepository().apply { add(selectorGroup(listOf(member("a"), member("b")))) }
            val settings = FakeAppSettingsRepository()
            val refresher = RecordingRelayRefresher()
            val selections = TestSelections("a")
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groups,
                    RelayProfileActivator(
                        com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                            FakeRelayProfileStore(),
                            FakeRelayCredentialStore(),
                            settings,
                        ),
                    ),
                    refresher,
                    ActiveSelectorSelectionProvider(groups, selections),
                )
            trigger.prepare()
            trigger.afterStart()
            assertEquals(0, refresher.refreshCount)
            selections.select("selector", "b")
            trigger.afterStart()
            assertEquals("b", settings.snapshot().relayProfileId)
            assertEquals(1, refresher.refreshCount)
            assertEquals(0, refresher.teardownCount)
        }

    @Test
    fun `lifecycle preparation establishes seed before a delayed watcher can miss selection`() =
        runTest {
            val groups = FakeProxyGroupRepository().apply { add(selectorGroup(listOf(member("a"), member("b")))) }
            val settings = FakeAppSettingsRepository()
            val refresher = RecordingRelayRefresher()
            val selections = TestSelections("a")
            val provider = ActiveSelectorSelectionProvider(groups, selections)
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groups,
                    RelayProfileActivator(
                        com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                            FakeRelayProfileStore(),
                            FakeRelayCredentialStore(),
                            settings,
                        ),
                    ),
                    refresher,
                    provider,
                )
            val coordinator = SelectorReloadCoordinator(backgroundScope, provider.selectedProfileId(), trigger)
            val listener = SelectorReloadModule.provideSelectorReloadLifecycleListener(coordinator, trigger)
            listener.start(Mode.VPN)
            // Intentionally do not runCurrent before production preparation.
            listener.prepare()
            listener.afterStart()
            selections.select("selector", "b")
            runCurrent()
            assertEquals("b", settings.snapshot().relayProfileId)
            assertEquals(1, refresher.refreshCount)
        }

    private class TestSelections(
        initial: String?,
    ) : SelectorSelectionStore {
        val state = MutableStateFlow(initial)

        override fun selectedProfileId(groupId: String) = state.asStateFlow()

        override fun snapshot(groupId: String) = SelectorSelectionSnapshot(state.value, true, 0)

        override fun invalidatePendingSelection(groupId: String) = Unit

        override fun selectAutomatically(
            groupId: String,
            expected: SelectorSelectionSnapshot,
            profileId: String,
        ): Boolean = false

        override suspend fun select(
            groupId: String,
            profileId: String,
        ) {
            state.value = profileId
        }

        override fun clearSelection(groupId: String) {
            state.value = null
        }
    }

    /** Records refresh / teardown calls instead of touching a real Android service. */
    private class RecordingRelayRefresher : RunningRelayRefresher {
        var refreshCount = 0
        var teardownCount = 0

        override suspend fun refresh(isCurrent: suspend () -> Boolean) {
            if (!isCurrent()) return
            refreshCount += 1
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
            replaceAll(
                com.poyka.ripdpi.data
                    .testPauseAuthority()
                    .supersede(com.poyka.ripdpi.data.RuntimeUserCommand.Stop),
                groups,
            )
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
