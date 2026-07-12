package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.rollbackStoreMutation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class ConfigRelayArtifactRepository
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialRepository,
    ) {
        private val mutationMutex = Mutex()

        suspend fun prepareForPersistence(draft: ConfigDraft): ConfigDraft =
            prepareRelayDraftForPersistence(
                draft = draft,
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
            )

        suspend fun hydrate(draft: ConfigDraft): ConfigDraft {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            val profile = relayProfileStore.load(profileId)
            val credentials = relayCredentialStore.load(profileId)
            return draft.withRelayArtifacts(profile, credentials)
        }

        suspend fun listProfiles(): List<RelayProfileRecord> = relayProfileStore.list()

        suspend fun persist(draft: ConfigDraft) =
            mutationMutex.withLock {
                val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
                val previousSettings = appSettingsRepository.snapshot()
                val previousProfile = relayProfileStore.load(profileId)
                val previousCredentials = relayCredentialStore.load(profileId)
                runCatching {
                    relayProfileStore.save(draft.toRelayProfileRecord(profileId))
                    relayCredentialStore.save(draft.toRelayCredentialRecord(profileId))
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
