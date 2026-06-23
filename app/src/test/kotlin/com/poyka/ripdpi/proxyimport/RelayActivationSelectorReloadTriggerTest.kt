package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
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
    fun `hotReload activates the selected member and refreshes the runtime`() =
        runTest {
            val groups = FakeProxyGroupRepository().apply { add(selectorGroup(listOf(member("a"), member("b")))) }
            val profileStore = FakeRelayProfileStore()
            val settings = FakeAppSettingsRepository()
            val refresher = RecordingRelayRefresher()
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groupRepository = groups,
                    relayProfileActivator =
                        RelayProfileActivator(
                            relayProfileStore = profileStore,
                            relayCredentialStore = FakeRelayCredentialStore(),
                            settingsRepository = settings,
                        ),
                    relayRefresher = refresher,
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
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groupRepository = groups,
                    relayProfileActivator =
                        RelayProfileActivator(
                            relayProfileStore = profileStore,
                            relayCredentialStore = FakeRelayCredentialStore(),
                            settingsRepository = FakeAppSettingsRepository(),
                        ),
                    relayRefresher = refresher,
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
            val trigger =
                RelayActivationSelectorReloadTrigger(
                    groupRepository = groups,
                    relayProfileActivator =
                        RelayProfileActivator(
                            relayProfileStore = profileStore,
                            relayCredentialStore = FakeRelayCredentialStore(),
                            settingsRepository = FakeAppSettingsRepository(),
                        ),
                    relayRefresher = refresher,
                )
            val selection = MutableStateFlow<String?>("a")
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectedProfileId = selection,
                    trigger = trigger,
                )

            coordinator.start()
            runCurrent()
            // Seed value must not reload.
            assertEquals(0, refresher.refreshCount)

            selection.value = "b"
            runCurrent()

            assertEquals("b.example.com", profileStore.load("b")?.server)
            assertEquals(1, refresher.refreshCount)
        }

    /** Records refresh / teardown calls instead of touching a real Android service. */
    private class RecordingRelayRefresher : RunningRelayRefresher {
        var refreshCount = 0
        var teardownCount = 0

        override suspend fun refresh() {
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

        override suspend fun delete(id: String) {
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
