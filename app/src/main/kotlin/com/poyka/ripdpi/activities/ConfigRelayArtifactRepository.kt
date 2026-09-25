package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ExpectedRelayProfileState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.applyRelayAfterImage
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
        requireWritableProfileId(draft)
        return prepareRelayDraftForPersistence(
            draft = draft,
            relayProfileStore = relayProfileStore,
            relayCredentialStore = relayCredentialStore,
            profileMutations = profileMutations,
        )
    }

    suspend fun hydrate(draft: ConfigDraft): ConfigDraft =
        readRecovered {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            draft.withRelayArtifacts(relayProfileStore.load(profileId), relayCredentialStore.load(profileId))
        }

    suspend fun hydrateProfile(profileId: String): ConfigDraft =
        readRecovered {
            val profile = requireNotNull(relayProfileStore.load(profileId)) { "Relay profile no longer exists" }
            val draft =
                appSettingsRepository
                    .snapshot()
                    .toBuilder()
                    .apply { applyRelayAfterImage(profile, enabled = true) }
                    .build()
                    .toConfigDraft()
            draft.withRelayArtifacts(profile, relayCredentialStore.load(profileId))
        }

    suspend fun selectProfile(profileId: String): ConfigDraft =
        readRecovered {
            val profile = requireNotNull(relayProfileStore.load(profileId)) { "Relay profile no longer exists" }
            appSettingsRepository.update {
                applyRelayAfterImage(profile, enabled = true)
                setRipdpiMode(Mode.VPN.preferenceValue)
            }
            appSettingsRepository.snapshot().toConfigDraft()
        }

    suspend fun listProfiles(): List<RelayProfileRecord> = readRecovered { relayProfileStore.list() }

    suspend fun persist(draft: ConfigDraft) =
        mutationMutex.withLock {
            requireWritableProfileId(draft)
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
                    expectedState =
                        ExpectedRelayProfileState(
                            draft.editingRelayProfileAtOpen,
                            draft.editingRelayCredentialsAtOpen,
                        ),
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

    private suspend fun requireWritableProfileId(draft: ConfigDraft) {
        readRecovered {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            val profile = relayProfileStore.load(profileId)
            val credentials = relayCredentialStore.load(profileId)
            require(draft.editingRelayProfileId.isBlank() || draft.editingRelayProfileId == profileId) {
                "A saved relay profile ID cannot be changed"
            }
            require(
                draft.editingRelayProfileId.isBlank() ||
                    draft.editingRelayProfileAtOpen?.kind == draft.relayKind,
            ) { "A saved relay profile kind cannot be changed" }
            require(
                if (draft.editingRelayProfileId.isBlank()) {
                    profile == null && credentials == null
                } else {
                    profile == draft.editingRelayProfileAtOpen &&
                        credentials == draft.editingRelayCredentialsAtOpen
                },
            ) {
                "Relay profile changed since editing began"
            }
        }
    }

    private suspend fun <T> readRecovered(block: suspend () -> T): T =
        if (profileMutations != null) profileMutations.readRecovered(block) else block()
}
