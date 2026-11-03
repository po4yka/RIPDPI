package com.poyka.ripdpi.data.fakes

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore

/** In-memory [RelayProfileStore] shared by unit tests. */
class FakeRelayProfileStore : RelayProfileStore {
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

/** In-memory [RelayCredentialStore] shared by unit tests. */
class FakeRelayCredentialStore : RelayCredentialStore {
    private val credentials = mutableMapOf<String, RelayCredentialRecord>()

    fun snapshot(): Map<String, RelayCredentialRecord> = credentials.toMap()

    override suspend fun load(profileId: String): RelayCredentialRecord? = credentials[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        this.credentials[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        credentials.remove(profileId)
    }
}
