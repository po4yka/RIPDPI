package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgSecrets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomAwgCredentialStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : AwgCredentialStore {
        override suspend fun load(profileId: String): AwgSecrets? = database.read { it.awgCredentials.load(profileId) }

        override suspend fun save(
            profileId: String,
            secrets: AwgSecrets,
        ) = database.write { it.awgCredentials.save(profileId, secrets) }

        override suspend fun clear(profileId: String) = database.write { it.awgCredentials.clear(profileId) }

        override suspend fun clearAll() = database.write { it.awgCredentials.clearAll() }
    }
