package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomXrayProfileMetadataStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : XrayProfileMetadataStore {
        override suspend fun load(profileId: String): XrayProfileMetadataRecord? =
            database.read { it.xrayMetadata.load(profileId) }

        override suspend fun list(): List<XrayProfileMetadataRecord> = database.read { it.xrayMetadata.list() }

        override suspend fun save(record: XrayProfileMetadataRecord) = database.write { it.xrayMetadata.save(record) }

        override suspend fun clear(profileId: String) = database.write { it.xrayMetadata.clear(profileId) }

        override suspend fun clearAll() = database.write { it.xrayMetadata.clearAll() }
    }

@Singleton
internal class RoomXrayProfileSecretStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : XrayProfileSecretStore {
        override suspend fun load(profileId: String): XrayProfileSecretRecord? =
            database.read { it.xraySecrets.load(profileId) }

        override suspend fun save(record: XrayProfileSecretRecord) = database.write { it.xraySecrets.save(record) }

        override suspend fun clear(profileId: String) = database.write { it.xraySecrets.clear(profileId) }

        override suspend fun clearAll() = database.write { it.xraySecrets.clearAll() }
    }
