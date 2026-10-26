package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Runs dispatcher-confined work inline so virtual-time tests stay deterministic. */
internal val InlineDispatchers =
    AppCoroutineDispatchers(
        kotlinx.coroutines.Dispatchers.Unconfined,
        kotlinx.coroutines.Dispatchers.Unconfined,
        kotlinx.coroutines.Dispatchers.Unconfined,
    )

internal class ProfileMutationTestFixture(
    initialAuthority: PauseAuthorityState? = null,
) {
    val persistence = TestPauseAuthorityPersistence(initialAuthority)
    val pauseAuthority = testPauseAuthority(persistence, initialize = false)
    val mutationGeneration = ProfileMutationGenerationPublisher()
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
    val selectorChoice = TestSelectorChoicePersistence()
    var storeWriteAttempts = 0
        private set
    var beforeCredentialSave: (suspend () -> Unit)? = null
    var beforeSettingsUpdate: (suspend () -> Unit)? = null
    var beforePrepare: (suspend (PendingProfileMutation) -> Unit)? = null
    var afterPrepare: (suspend (PendingProfileMutation) -> Unit)? = null
    var prepareAttempts = 0
    private val transactionJournal =
        object : ProfileMutationJournal by journal {
            override suspend fun prepare(mutation: PendingProfileMutation) {
                prepareAttempts += 1
                beforePrepare?.invoke(mutation)
                journal.prepare(mutation)
                afterPrepare?.invoke(mutation)
            }
        }
    val bootSession = InMemoryBootSessionStateStore()
    var failGroupWrite = false
    var failGroupRead = false
    var groupWriteAttempts = 0
    var groupClearAttempts = 0
    var beforeGroupWrite: (() -> Unit)? = null
    var afterGroupWrite: (() -> Unit)? = null
    val groupBlob =
        object : ProxyGroupBlobStore {
            private var encoded: String? = null

            override fun read(): String? {
                check(!failGroupRead) { "Unreadable group catalogue" }
                return encoded
            }

            override fun write(json: String) {
                beforeGroupWrite?.invoke()
                groupWriteAttempts++
                check(!failGroupWrite)
                encoded = json
                afterGroupWrite?.invoke()
            }

            override fun clear() {
                groupClearAttempts++
                encoded = null
            }
        }

    val groupCatalog = ProxyGroupCatalog(groupBlob)

    val stores =
        ProfileMutationStores(
            settings =
                object : AppSettingsRepository by settings {
                    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
                        storeWriteAttempts += 1
                        beforeSettingsUpdate?.invoke()
                        this@ProfileMutationTestFixture.settings.update(transform)
                    }

                    override suspend fun replace(settings: AppSettings) {
                        storeWriteAttempts += 1
                        this@ProfileMutationTestFixture.settings.replace(settings)
                    }
                },
            relayProfiles = relayProfiles,
            relayCredentials =
                object : RelayCredentialStore by relayCredentials {
                    override suspend fun save(credentials: RelayCredentialRecord) {
                        storeWriteAttempts += 1
                        beforeCredentialSave?.invoke()
                        relayCredentials.save(credentials)
                    }
                },
            warpProfiles = warpProfiles,
            warpCredentials = warpCredentials,
            warpEndpoints = warpEndpoints,
            xrayMetadata =
                object : XrayProfileMetadataStore by xrayMetadata {
                    override suspend fun save(record: XrayProfileMetadataRecord) {
                        storeWriteAttempts += 1
                        xrayMetadata.save(record)
                    }
                },
            xraySecrets =
                object : XrayProfileSecretStore by xraySecrets {
                    override suspend fun save(record: XrayProfileSecretRecord) {
                        storeWriteAttempts += 1
                        xraySecrets.save(record)
                    }
                },
            xraySelection =
                object : XrayProviderSelectionStore by xraySelection {
                    override fun update(record: XrayProviderSelectionRecord) {
                        storeWriteAttempts += 1
                        xraySelection.update(record)
                    }
                },
            bootSession = bootSession,
            groupCatalog = groupCatalog,
            selectorChoice = selectorChoice,
        )

    fun coordinator(
        catalog: ProxyGroupCatalog = groupCatalog,
        authority: PauseIntentAuthority = pauseAuthority,
    ) = ProfileMutationRecoveryCoordinator(
        pauseAuthority = authority,
        mutationGeneration = mutationGeneration,
        stores =
            ProfileMutationStores(
                stores.settings,
                stores.relayProfiles,
                stores.relayCredentials,
                stores.warpProfiles,
                stores.warpCredentials,
                stores.warpEndpoints,
                stores.xrayMetadata,
                stores.xraySecrets,
                stores.xraySelection,
                stores.bootSession,
                catalog,
                stores.selectorChoice,
            ),
        awgProfiles = awgProfiles,
        awgCredentials = awgCredentials,
        journal = transactionJournal,
    )
}

internal class InMemoryBootSessionStateStore : BootSessionStateStore {
    private var activeAwgProfileId: String? = null

    override fun lastSession(): BootSessionPointer? = null

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) = Unit

    override fun activeAwgProfileId(): String? = activeAwgProfileId

    override fun setActiveAwgProfileId(profileId: String?) {
        activeAwgProfileId = profileId
    }

    override fun clear() = Unit

    override fun wasRunningAtUpdate(): Boolean = false

    override fun setWasRunningAtUpdate(value: Boolean) = Unit
}

internal class InMemoryProfileMutationJournal : ProfileMutationJournal {
    private var value: PendingProfileMutation? = null
    var pendingCorruptionFailuresRemaining = 0
    var beforeComplete: (() -> Unit)? = null
    var afterComplete: (() -> Unit)? = null
    var beforeReplace: (() -> Unit)? = null
    var beforeClear: (() -> Unit)? = null
    var clearFailure: Throwable? = null

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
        beforeReplace?.invoke()
        check(value?.mutationId == expectedMutationId)
        value = mutation
    }

    override suspend fun complete(mutationId: String) {
        beforeComplete?.invoke()
        check(value?.mutationId == mutationId)
        value = null
        afterComplete?.invoke()
    }

    override suspend fun clearForReset() {
        beforeClear?.invoke()
        clearFailure?.let { throw it }
        value = null
    }
}

internal class InMemorySettingsRepository : AppSettingsRepository {
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

internal class InMemoryRelayProfileStore : RelayProfileStore {
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

internal class InMemoryRelayCredentialStore : RelayCredentialStore {
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

internal class InMemoryWarpProfileStore : WarpProfileStore {
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

    override suspend fun setActiveProfileIdOwned(
        profileId: String?,
        authority: com.poyka.ripdpi.data.PauseIntentAuthority,
        reference: com.poyka.ripdpi.data.PauseAuthorityRef,
        commandId: String,
    ): Boolean =
        authority.intentLinearizer.serialize {
            if (!(
                    authority.reference() == reference &&
                        authority.snapshotAuthority().command?.commandId == commandId
                )
            ) {
                false
            } else {
                activeId = profileId
                true
            }
        }

    override suspend fun clearAll() {
        values.clear()
        activeId = null
    }
}

internal class InMemoryWarpCredentialStore : WarpCredentialStore {
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

internal class InMemoryWarpEndpointStore : WarpEndpointStore {
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

internal class InMemoryAwgProfileDao : AwgProfileDao {
    var upsertAttempts = 0

    var beforeUpsert: (suspend () -> Unit)? = null
    var afterUpsert: (suspend () -> Unit)? = null

    private val values = mutableMapOf<String, AwgProfileEntity>()
    private val state = MutableStateFlow<List<AwgProfileEntity>>(emptyList())
    var failNextUpsert = false

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = state

    override suspend fun allProfiles() = values.values.toList()

    override suspend fun getProfile(id: String) = values[id]

    override suspend fun upsertProfile(profile: AwgProfileEntity) {
        upsertAttempts++
        beforeUpsert?.invoke()
        if (failNextUpsert) {
            failNextUpsert = false
            error("simulated room failure")
        }
        values[profile.id] = profile
        state.value = values.values.toList()
        afterUpsert?.invoke()
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

internal class InMemoryAwgCredentialStore : AwgCredentialStore {
    var saveAttempts = 0
    var afterSave: (suspend () -> Unit)? = null

    private val values = mutableMapOf<String, AwgSecrets>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        saveAttempts++
        values[profileId] = secrets
        afterSave?.invoke()
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

internal class InMemoryXrayMetadataStore : XrayProfileMetadataStore {
    private val values = mutableMapOf<String, XrayProfileMetadataRecord>()
    var failNextSave = false
    val listThreads: MutableList<Thread> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun list(): List<XrayProfileMetadataRecord> {
        listThreads += Thread.currentThread()
        return values.values.toList()
    }

    override suspend fun save(record: XrayProfileMetadataRecord) {
        if (failNextSave) {
            failNextSave = false
            error("simulated metadata failure")
        }
        values[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

internal class InMemoryXraySecretStore : XrayProfileSecretStore {
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

internal class InMemoryXraySelectionStore : XrayProviderSelectionStore {
    private var value = XrayProviderSelectionRecord()
    var failNextUpdate = false

    override fun current() = value

    override fun update(record: XrayProviderSelectionRecord) {
        if (failNextUpdate) {
            failNextUpdate = false
            error("simulated xray selection failure")
        }
        value = record
    }
}
