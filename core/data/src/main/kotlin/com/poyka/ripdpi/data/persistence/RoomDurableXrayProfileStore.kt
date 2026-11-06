package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileRecordPair
import com.poyka.ripdpi.data.xray.hasMatchingRevision
import com.poyka.ripdpi.data.xray.toXrayProfile
import com.poyka.ripdpi.data.xray.toXrayProfileRecordPair
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomDurableXrayProfileStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : DurableXrayProfileStore {
        override suspend fun load(profileId: String): XrayProfile? =
            database.read { rows ->
                val metadata = rows.xrayMetadata.load(profileId) ?: return@read null
                val secret = rows.xraySecrets.load(profileId) ?: return@read null
                XrayProfileRecordPair(metadata, secret).toXrayProfile()
            }

        override suspend fun save(
            profileId: String,
            profile: XrayProfile,
        ) = database.write { rows ->
            val pair = profile.toXrayProfileRecordPair(profileId)
            rows.xrayMetadata.save(pair.metadata)
            rows.xraySecrets.save(pair.secret)
        }

        override suspend fun clear(profileId: String) =
            database.write { rows ->
                rows.xrayMetadata.clear(profileId)
                rows.xraySecrets.clear(profileId)
            }

        override suspend fun listProfileIds(): List<String> =
            database.read { rows ->
                rows.xrayMetadata.list().mapNotNull { metadata ->
                    rows.xraySecrets.load(metadata.profileId)?.let { secret ->
                        metadata.profileId.takeIf { XrayProfileRecordPair(metadata, secret).hasMatchingRevision() }
                    }
                }
            }
    }
