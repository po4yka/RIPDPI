package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.testsupport.NoOpProfileMutationCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ConfigRelayArtifactRepositoryTest {
    @Test
    fun `journaled relay persistence keeps the complete config settings after-image`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val profileMutations = RecordingRelayMutationCoordinator(settings, profiles, credentials)
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials, profileMutations)
            val draft =
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    mode = Mode.Proxy,
                    dnsIp = "9.9.9.9",
                    relayProfileId = "relay-journaled",
                    relayServer = "relay.example",
                )

            repository.persist(draft)

            assertEquals(Mode.Proxy.preferenceValue, settings.snapshot().ripdpiMode)
            assertEquals("9.9.9.9", settings.snapshot().dnsIp)
            assertEquals("relay.example", profiles.load("relay-journaled")?.server)
        }

    @Test
    fun `failed metadata write restores the previous relay snapshot`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials)
            val profileId = "relay-atomic"
            val previousDraft =
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    relayProfileId = profileId,
                    relayServer = "old.example",
                    relayVlessUuid = "old-credential",
                )
            profiles.save(previousDraft.toRelayProfileRecord(profileId))
            credentials.save(previousDraft.toRelayCredentialRecord(profileId))
            profiles.failNextSaveAfterWrite = true

            val error =
                runCatching {
                    repository.persist(previousDraft.copy(relayServer = "new.example"))
                }.exceptionOrNull()

            assertNotNull(error)
            assertEquals("old.example", profiles.load(profileId)?.server)
            assertEquals("old-credential", credentials.load(profileId)?.vlessUuid)
            assertEquals(AppSettingsSerializer.defaultValue, settings.snapshot())
        }

    @Test
    fun `failed DataStore write restores relay metadata credentials and settings`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials)
            val profileId = "relay-atomic"
            val previousDraft =
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    relayProfileId = profileId,
                    relayServer = "old.example",
                    relayVlessUuid = "old-credential",
                )
            profiles.save(previousDraft.toRelayProfileRecord(profileId))
            credentials.save(previousDraft.toRelayCredentialRecord(profileId))
            settings.update { applyConfigDraft(previousDraft) }
            settings.failNextUpdateAfterWrite = true

            val error =
                runCatching {
                    repository.persist(
                        previousDraft.copy(relayServer = "new.example", relayVlessUuid = "new-credential"),
                    )
                }.exceptionOrNull()

            assertNotNull(error)
            assertEquals("old.example", profiles.load(profileId)?.server)
            assertEquals("old-credential", credentials.load(profileId)?.vlessUuid)
            assertEquals("old.example", settings.snapshot().relayServer)
        }

    @Test
    fun `failed credential write restores relay metadata and credentials`() =
        runTest {
            val settings = FailingSettingsRepository()
            val profiles = FailingRelayProfileStore()
            val credentials = FailingRelayCredentialRepository()
            val repository = ConfigRelayArtifactRepository(settings, profiles, credentials)
            val profileId = "relay-atomic"
            val previousDraft =
                AppSettingsSerializer.defaultValue.toConfigDraft().copy(
                    relayProfileId = profileId,
                    relayServer = "old.example",
                    relayVlessUuid = "old-credential",
                )
            profiles.save(previousDraft.toRelayProfileRecord(profileId))
            credentials.save(previousDraft.toRelayCredentialRecord(profileId))
            credentials.failNextSaveAfterWrite = true

            val error =
                runCatching {
                    repository.persist(
                        previousDraft.copy(relayServer = "new.example", relayVlessUuid = "new-credential"),
                    )
                }.exceptionOrNull()

            assertNotNull(error)
            assertEquals("old.example", profiles.load(profileId)?.server)
            assertEquals("old-credential", credentials.load(profileId)?.vlessUuid)
            assertEquals(AppSettingsSerializer.defaultValue, settings.snapshot())
        }
}

private class RecordingRelayMutationCoordinator(
    private val settings: AppSettingsRepository,
    private val profiles: RelayProfileStore,
    private val credentials: RelayCredentialRepository,
) : ProfileMutationCoordinator by NoOpProfileMutationCoordinator {
    override suspend fun upsertRelay(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
    ) {
        profiles.save(profile)
        this.credentials.save(credentials)
        settings.replace(requireNotNull(settingsAfterImage))
    }
}

private class FailingSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)
    var failNextUpdateAfterWrite = false

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
        if (failNextUpdateAfterWrite) {
            failNextUpdateAfterWrite = false
            error("settings update failed after write")
        }
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class FailingRelayProfileStore : RelayProfileStore {
    private val records = linkedMapOf<String, RelayProfileRecord>()
    var failNextSaveAfterWrite = false

    override suspend fun load(profileId: String): RelayProfileRecord? = records[profileId]

    override suspend fun list(): List<RelayProfileRecord> = records.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        records[profile.id] = profile
        if (failNextSaveAfterWrite) {
            failNextSaveAfterWrite = false
            error("profile save failed after write")
        }
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}

private class FailingRelayCredentialRepository : RelayCredentialRepository {
    private val records = linkedMapOf<String, RelayCredentialRecord>()
    var failNextSaveAfterWrite = false

    override suspend fun load(profileId: String): RelayCredentialRecord? = records[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        records[credentials.profileId] = credentials
        if (failNextSaveAfterWrite) {
            failNextSaveAfterWrite = false
            error("credential save failed after write")
        }
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}
