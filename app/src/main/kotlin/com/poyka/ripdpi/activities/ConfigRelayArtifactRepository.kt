package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.rollbackStoreMutation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class ConfigRelayArtifactRepository private constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val relayProfileStore: RelayProfileStore,
    private val relayCredentialStore: RelayCredentialRepository,
    private val profileMutations: ProfileMutationCoordinator?,
    legacyConstructorMarker: Unit?,
) {
    init {
        check(legacyConstructorMarker == null || profileMutations == null)
    }

    @Inject
    constructor(
        appSettingsRepository: AppSettingsRepository,
        relayProfileStore: RelayProfileStore,
        relayCredentialStore: RelayCredentialRepository,
        profileMutations: ProfileMutationCoordinator,
    ) : this(
        appSettingsRepository,
        relayProfileStore,
        relayCredentialStore,
        profileMutations,
        null,
    )

    internal constructor(
        appSettingsRepository: AppSettingsRepository,
        relayProfileStore: RelayProfileStore,
        relayCredentialStore: RelayCredentialRepository,
    ) : this(appSettingsRepository, relayProfileStore, relayCredentialStore, null, Unit)

    private val mutationMutex = Mutex()

    suspend fun prepareForPersistence(draft: ConfigDraft): ConfigDraft {
        profileMutations?.recover()
        return prepareRelayDraftForPersistence(
            draft = draft,
            relayProfileStore = relayProfileStore,
            relayCredentialStore = relayCredentialStore,
            profileMutations = profileMutations,
        )
    }

    suspend fun hydrate(draft: ConfigDraft): ConfigDraft {
        profileMutations?.recover()
        val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
        val profile = relayProfileStore.load(profileId)
        val credentials = relayCredentialStore.load(profileId)
        return draft.withRelayArtifacts(profile, credentials)
    }

    suspend fun listProfiles(): List<RelayProfileRecord> {
        profileMutations?.recover()
        return relayProfileStore.list()
    }

    suspend fun persist(draft: ConfigDraft) =
        mutationMutex.withLock {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            val profile = draft.toRelayProfileRecord(profileId)
            val credentials = draft.toRelayCredentialRecord(profileId)
            if (profileMutations != null) {
                val settingsAfterImage =
                    appSettingsRepository
                        .snapshot()
                        .toBuilder()
                        .apply { applyConfigDraft(draft) }
                        .build()
                profileMutations.upsertRelay(
                    profile = profile,
                    credentials = credentials,
                    enabled = draft.relayEnabled,
                    select = true,
                    settingsAfterImage = settingsAfterImage,
                )
                return@withLock
            }
            val previousSettings = appSettingsRepository.snapshot()
            val previousProfile = relayProfileStore.load(profileId)
            val previousCredentials = relayCredentialStore.load(profileId)
            runCatching {
                relayProfileStore.save(profile)
                relayCredentialStore.save(credentials)
                appSettingsRepository.update { applyConfigDraft(draft) }
            }.exceptionOrNull()
                ?.rollbackStoreMutation(
                    {
                        if (previousProfile == null) {
                            relayProfileStore.clear(profileId)
                        } else {
                            relayProfileStore.save(previousProfile)
                        }
                    },
                    {
                        if (previousCredentials == null) {
                            relayCredentialStore.clear(profileId)
                        } else {
                            relayCredentialStore.save(previousCredentials)
                        }
                    },
                    { appSettingsRepository.replace(previousSettings) },
                )
            Unit
        }
}
