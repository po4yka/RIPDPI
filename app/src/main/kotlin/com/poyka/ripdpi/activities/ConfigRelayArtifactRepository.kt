package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileStore
import javax.inject.Inject

class ConfigRelayArtifactRepository
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialRepository,
    ) {
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

        suspend fun persist(draft: ConfigDraft) {
            val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
            relayProfileStore.save(draft.toRelayProfileRecord(profileId))
            relayCredentialStore.save(draft.toRelayCredentialRecord(profileId))
        }
    }
