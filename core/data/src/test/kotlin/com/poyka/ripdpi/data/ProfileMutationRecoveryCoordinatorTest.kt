package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMutationRecoveryCoordinatorTest {
    @Test
    fun `awg secrets and metadata converge after interrupted upsert`() =
        runTest {
            val fixture = Fixture()
            val profile = AwgProfileEntity(id = "awg-1", name = "AWG", requestJson = "{}", updatedAt = 1L)
            val secrets = AwgSecrets(privateKey = "private", presharedKey = "psk")
            fixture.awgProfiles.failNextUpsert = true

            val failure = runCatching { fixture.coordinator().upsertAwg(profile, secrets) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(secrets, fixture.awgCredentials.load(profile.id))
            assertNull(fixture.awgProfiles.getProfile(profile.id))
            assertTrue(fixture.journal.pending() != null)

            fixture.coordinator().recover()
            fixture.coordinator().recover()

            assertEquals(profile, fixture.awgProfiles.getProfile(profile.id))
            assertEquals(secrets, fixture.awgCredentials.load(profile.id))
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `relay after-image survives failure and replays after process restart`() =
        runTest {
            val fixture = Fixture()
            val profile = RelayProfileRecord(id = "relay-1", kind = RelayKindVlessReality, server = "relay.example")
            val credentials = RelayCredentialRecord(profileId = profile.id, vlessUuid = "uuid")
            fixture.relayCredentials.failNextSave = true

            val failure =
                runCatching {
                    fixture.coordinator().upsertRelay(profile, credentials, enabled = true, select = true)
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(profile, fixture.relayProfiles.load(profile.id))
            assertNull(fixture.relayCredentials.load(profile.id))
            assertTrue(fixture.journal.pending() != null)

            fixture.coordinator().recover()

            assertEquals(credentials, fixture.relayCredentials.load(profile.id))
            assertEquals(profile.id, fixture.settings.snapshot().relayProfileId)
            assertTrue(fixture.settings.snapshot().relayEnabled)
            assertNull(fixture.journal.pending())
            val savesAfterReplay = fixture.relayProfiles.saveCount

            fixture.coordinator().recover()

            assertEquals(savesAfterReplay, fixture.relayProfiles.saveCount)
        }

    @Test
    fun `warp delete clears selection before deleting all profile stores`() =
        runTest {
            val fixture = Fixture()
            val profile = WarpProfile(id = "warp-1", displayName = "WARP")
            fixture.warpProfiles.save(profile)
            fixture.warpProfiles.setActiveProfileId(profile.id)
            fixture.warpCredentials.save(
                profile.id,
                WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "token"),
            )
            fixture.warpEndpoints.save(
                WarpEndpointCacheEntry(
                    profileId = profile.id,
                    networkScopeKey = GlobalWarpEndpointScopeKey,
                    host = "warp.example",
                    port = 2408,
                ),
            )
            fixture.settings.update { setWarpProfileId(profile.id) }

            fixture.coordinator().deleteWarp(profile.id, clearActive = true)

            assertNull(fixture.warpProfiles.load(profile.id))
            assertNull(fixture.warpProfiles.activeProfileId())
            assertNull(fixture.warpCredentials.load(profile.id))
            assertTrue(fixture.warpEndpoints.loadAll(profile.id).isEmpty())
            assertFalse(fixture.settings.snapshot().warpProfileId == profile.id)
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `warp after-image recovers endpoint and both active pointers`() =
        runTest {
            val fixture = Fixture()
            val profile = WarpProfile(id = "warp-replay", displayName = "WARP", setupState = WarpSetupStateProvisioned)
            val credentials = WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "token")
            val endpoint =
                WarpEndpointCacheEntry(
                    profileId = profile.id,
                    networkScopeKey = GlobalWarpEndpointScopeKey,
                    host = "warp.example",
                    port = 2408,
                )
            fixture.warpEndpoints.failNextSave = true

            val failure =
                runCatching {
                    fixture.coordinator().upsertWarp(
                        profile = profile,
                        credentials = credentials,
                        endpoints = listOf(endpoint),
                        activate = true,
                        scannerMode = WarpScannerModeAutomatic,
                    )
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(fixture.journal.pending() != null)

            fixture.coordinator().recover()

            assertEquals(endpoint, fixture.warpEndpoints.load(profile.id, GlobalWarpEndpointScopeKey))
            assertEquals(profile.id, fixture.warpProfiles.activeProfileId())
            assertEquals(profile.id, fixture.settings.snapshot().warpProfileId)
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `corrupt pending intent fails once then allows startup recovery retry`() =
        runTest {
            val fixture = Fixture()
            val coordinator = fixture.coordinator()
            fixture.journal.prepare(PendingProfileMutation(family = ProfileMutationFamily.Relay, payload = "not-json"))

            val failure = runCatching { coordinator.recover() }.exceptionOrNull()

            assertTrue(failure is ProfileMutationJournalCorruptionException)
            assertTrue(fixture.journal.pending() != null)
            coordinator.recover()
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `unreadable journal marker is discarded only by startup recovery retry`() =
        runTest {
            val fixture = Fixture()
            val coordinator = fixture.coordinator()
            fixture.journal.pendingCorruptionFailuresRemaining = 2

            val failure = runCatching { coordinator.recover() }.exceptionOrNull()

            assertTrue(failure is ProfileMutationJournalCorruptionException)
            assertEquals(0, fixture.journal.discardCount)

            coordinator.recover()

            assertEquals(1, fixture.journal.discardCount)
        }

    @Test
    fun `transient unreadable journal marker is preserved when retry can read it`() =
        runTest {
            val fixture = Fixture()
            val coordinator = fixture.coordinator()
            val profile = RelayProfileRecord(id = "relay-transient", server = "relay.example")
            val credentials = RelayCredentialRecord(profileId = profile.id, vlessUuid = "uuid")
            fixture.relayCredentials.failNextSave = true
            val interruptedMutation =
                runCatching {
                    coordinator.upsertRelay(profile, credentials, enabled = true, select = true)
                }.exceptionOrNull()
            assertTrue(interruptedMutation != null)
            fixture.journal.pendingCorruptionFailuresRemaining = 1

            val failure = runCatching { coordinator.recover() }.exceptionOrNull()

            assertTrue(failure is ProfileMutationJournalCorruptionException)
            coordinator.recover()
            assertEquals(0, fixture.journal.discardCount)
            assertEquals(credentials, fixture.relayCredentials.load(profile.id))
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `private backup failure persists rollback intent rather than target`() =
        runTest {
            val fixture = Fixture()
            val originalProfile = RelayProfileRecord(id = "relay-old", server = "old.example")
            val originalCredentials = RelayCredentialRecord(profileId = originalProfile.id, vlessUuid = "old-uuid")
            fixture.relayProfiles.save(originalProfile)
            fixture.relayCredentials.save(originalCredentials)
            val preimage =
                BackupPrivateDataV1(
                    relayProfiles = listOf(originalProfile),
                    relayCredentials = listOf(originalCredentials),
                )
            val targetProfile = RelayProfileRecord(id = "relay-new", server = "new.example")
            val targetCredentials = RelayCredentialRecord(profileId = targetProfile.id, vlessUuid = "new-uuid")
            val target =
                BackupPrivateDataV1(
                    relayProfiles = listOf(targetProfile),
                    relayCredentials = listOf(targetCredentials),
                )
            fixture.relayCredentials.failSaveAttempts = 2

            val failure =
                runCatching {
                    fixture.coordinator().replacePrivateBackup(target, rollbackData = preimage)
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(1, failure?.suppressed?.size)
            assertTrue(fixture.journal.pending() != null)

            fixture.coordinator().recover()

            assertEquals(originalProfile, fixture.relayProfiles.load(originalProfile.id))
            assertEquals(originalCredentials, fixture.relayCredentials.load(originalProfile.id))
            assertNull(fixture.relayProfiles.load(targetProfile.id))
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `reset barrier prevents a mutation from preparing until the wipe completes`() =
        runTest {
            val fixture = Fixture()
            val coordinator = fixture.coordinator()
            val resetStarted = CompletableDeferred<Unit>()
            val finishReset = CompletableDeferred<Unit>()
            val resetJob =
                launch {
                    coordinator.runReset {
                        resetStarted.complete(Unit)
                        finishReset.await()
                    }
                }
            resetStarted.await()
            val profile = AwgProfileEntity(id = "after-reset", name = "AWG", requestJson = "{}", updatedAt = 1L)
            val mutationJob =
                launch {
                    coordinator.upsertAwg(profile, AwgSecrets(privateKey = "private"))
                }

            runCurrent()
            assertFalse(mutationJob.isCompleted)
            assertNull(fixture.journal.pending())

            finishReset.complete(Unit)
            resetJob.join()
            mutationJob.join()

            assertEquals(profile, fixture.awgProfiles.getProfile(profile.id))
            assertNull(fixture.journal.pending())
        }

    private class Fixture {
        val settings = InMemorySettingsRepository()
        val relayProfiles = InMemoryRelayProfileStore()
        val relayCredentials = InMemoryRelayCredentialStore()
        val warpProfiles = InMemoryWarpProfileStore()
        val warpCredentials = InMemoryWarpCredentialStore()
        val warpEndpoints = InMemoryWarpEndpointStore()
        val awgProfiles = InMemoryAwgProfileDao()
        val awgCredentials = InMemoryAwgCredentialStore()
        val xrayMetadata = InMemoryXrayMetadataStore()
        val xraySecrets = InMemoryXraySecretStore()
        val xraySelection = InMemoryXraySelectionStore()
        val journal = InMemoryProfileMutationJournal()

        fun coordinator() =
            ProfileMutationRecoveryCoordinator(
                stores =
                    ProfileMutationStores(
                        settings = settings,
                        relayProfiles = relayProfiles,
                        relayCredentials = relayCredentials,
                        warpProfiles = warpProfiles,
                        warpCredentials = warpCredentials,
                        warpEndpoints = warpEndpoints,
                        xrayMetadata = xrayMetadata,
                        xraySecrets = xraySecrets,
                        xraySelection = xraySelection,
                    ),
                awgProfiles = awgProfiles,
                awgCredentials = awgCredentials,
                journal = journal,
            )
    }
}

private class InMemoryProfileMutationJournal : ProfileMutationJournal {
    private var value: PendingProfileMutation? = null
    var pendingCorruptionFailuresRemaining = 0
    var discardCount = 0

    override suspend fun prepare(mutation: PendingProfileMutation) {
        check(value == null)
        value = mutation
    }

    override suspend fun pending(): PendingProfileMutation? {
        if (pendingCorruptionFailuresRemaining > 0) {
            pendingCorruptionFailuresRemaining -= 1
            throw ProfileMutationJournalCorruptionException("corrupt marker")
        }
        return value
    }

    override suspend fun replace(
        expectedMutationId: String,
        mutation: PendingProfileMutation,
    ) {
        check(value?.mutationId == expectedMutationId)
        value = mutation
    }

    override suspend fun complete(mutationId: String) {
        check(value?.mutationId == mutationId)
        value = null
    }

    override suspend fun discardCorruptPending() {
        discardCount += 1
        pendingCorruptionFailuresRemaining = 0
        value = null
    }

    override suspend fun clearForReset() {
        value = null
    }
}

private class InMemorySettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)
    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

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

private class InMemoryRelayProfileStore : RelayProfileStore {
    private val values = mutableMapOf<String, RelayProfileRecord>()
    var saveCount = 0

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun list() = values.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        saveCount += 1
        values[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryRelayCredentialStore : RelayCredentialStore {
    private val values = mutableMapOf<String, RelayCredentialRecord>()
    var failNextSave = false
    var failSaveAttempts = 0

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        if (failNextSave || failSaveAttempts > 0) {
            failNextSave = false
            if (failSaveAttempts > 0) failSaveAttempts -= 1
            error("simulated credential store failure")
        }
        values[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryWarpProfileStore : WarpProfileStore {
    private val values = mutableMapOf<String, WarpProfile>()
    private var activeId: String? = null

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun loadAll() = values.values.toList()

    override suspend fun save(profile: WarpProfile) {
        values[profile.id] = profile
    }

    override suspend fun remove(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun activeProfileId() = activeId

    override suspend fun setActiveProfileId(profileId: String?) {
        activeId = profileId
    }

    override suspend fun clearAll() {
        values.clear()
        activeId = null
    }
}

private class InMemoryWarpCredentialStore : WarpCredentialStore {
    private val values = mutableMapOf<String, WarpCredentials>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun loadAll() = values.values.toList()

    override suspend fun save(
        profileId: String,
        credentials: WarpCredentials,
    ) {
        values[profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryWarpEndpointStore : WarpEndpointStore {
    private val values = mutableMapOf<Pair<String, String>, WarpEndpointCacheEntry>()
    var failNextSave = false

    override suspend fun load(
        profileId: String,
        networkScopeKey: String,
    ) = values[profileId to networkScopeKey]

    override suspend fun loadAll(profileId: String) = values.values.filter { it.profileId == profileId }

    override suspend fun save(entry: WarpEndpointCacheEntry) {
        if (failNextSave) {
            failNextSave = false
            error("simulated endpoint store failure")
        }
        values[entry.profileId to entry.networkScopeKey] = entry
    }

    override suspend fun clear(
        profileId: String,
        networkScopeKey: String,
    ) {
        values.remove(profileId to networkScopeKey)
    }

    override suspend fun clearProfile(profileId: String) {
        values.keys.removeAll { it.first == profileId }
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryAwgProfileDao : AwgProfileDao {
    private val values = mutableMapOf<String, AwgProfileEntity>()
    private val state = MutableStateFlow<List<AwgProfileEntity>>(emptyList())
    var failNextUpsert = false

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = state

    override suspend fun allProfiles() = values.values.toList()

    override suspend fun getProfile(id: String) = values[id]

    override suspend fun upsertProfile(profile: AwgProfileEntity) {
        if (failNextUpsert) {
            failNextUpsert = false
            error("simulated room failure")
        }
        values[profile.id] = profile
        state.value = values.values.toList()
    }

    override suspend fun deleteProfile(profile: AwgProfileEntity) {
        values.remove(profile.id)
        state.value = values.values.toList()
    }

    override suspend fun deleteAll() {
        values.clear()
        state.value = emptyList()
    }
}

private class InMemoryAwgCredentialStore : AwgCredentialStore {
    private val values = mutableMapOf<String, AwgSecrets>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        values[profileId] = secrets
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryXrayMetadataStore : XrayProfileMetadataStore {
    private val values = mutableMapOf<String, XrayProfileMetadataRecord>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun list() = values.values.toList()

    override suspend fun save(record: XrayProfileMetadataRecord) {
        values[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryXraySecretStore : XrayProfileSecretStore {
    private val values = mutableMapOf<String, XrayProfileSecretRecord>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(record: XrayProfileSecretRecord) {
        values[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryXraySelectionStore : XrayProviderSelectionStore {
    private var value = XrayProviderSelectionRecord()

    override suspend fun current() = value

    override suspend fun update(record: XrayProviderSelectionRecord) {
        value = record
    }
}
