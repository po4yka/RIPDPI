package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomRelayProfileStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : RelayProfileStore {
        override suspend fun load(profileId: String): RelayProfileRecord? =
            database.read { it.relayProfiles.load(profileId) }

        override suspend fun list(): List<RelayProfileRecord> = database.read { it.relayProfiles.list() }

        override suspend fun save(profile: RelayProfileRecord) = database.write { it.relayProfiles.save(profile) }

        override suspend fun clear(profileId: String) = database.write { it.relayProfiles.clear(profileId) }

        override suspend fun clearAll() = database.write { it.relayProfiles.clearAll() }
    }

@Singleton
internal class RoomRelayCredentialStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : RelayCredentialStore {
        override suspend fun load(profileId: String): RelayCredentialRecord? =
            database.read { it.relayCredentials.load(profileId) }

        override suspend fun save(credentials: RelayCredentialRecord) =
            database.write { it.relayCredentials.save(credentials) }

        override suspend fun clear(profileId: String) = database.write { it.relayCredentials.clear(profileId) }

        override suspend fun clearAll() = database.write { it.relayCredentials.clearAll() }
    }
