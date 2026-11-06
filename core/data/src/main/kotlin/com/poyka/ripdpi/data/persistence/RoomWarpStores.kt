package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.PauseAuthorityRef
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomWarpProfileStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : WarpProfileStore {
        override suspend fun load(profileId: String): WarpProfile? = database.read { it.warpProfiles.load(profileId) }

        override suspend fun loadAll(): List<WarpProfile> = database.read { it.warpProfiles.loadAll() }

        override suspend fun save(profile: WarpProfile) = database.write { it.warpProfiles.save(profile) }

        override suspend fun remove(profileId: String) = database.write { it.warpProfiles.remove(profileId) }

        override suspend fun activeProfileId(): String? = database.read { it.warpProfiles.activeProfileId() }

        override suspend fun setActiveProfileId(profileId: String?) =
            database.write {
                it.warpProfiles.setActiveProfileId(profileId)
            }

        override suspend fun setActiveProfileIdOwned(
            profileId: String?,
            authority: PauseIntentAuthority,
            reference: PauseAuthorityRef,
            commandId: String,
        ): Boolean =
            database.writeOwned(authority, reference, commandId) { it.warpProfiles.setActiveProfileId(profileId) }

        override suspend fun clearAll() = database.write { it.warpProfiles.clearAll() }
    }

@Singleton
internal class RoomWarpCredentialStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : WarpCredentialStore {
        override suspend fun load(profileId: String): WarpCredentials? =
            database.read { it.warpCredentials.load(profileId) }

        override suspend fun loadAll(): List<WarpCredentials> = database.read { it.warpCredentials.loadAll() }

        override suspend fun save(
            profileId: String,
            credentials: WarpCredentials,
        ) = database.write { it.warpCredentials.save(profileId, credentials) }

        override suspend fun clear(profileId: String) = database.write { it.warpCredentials.clear(profileId) }

        override suspend fun clearAll() = database.write { it.warpCredentials.clearAll() }
    }

@Singleton
internal class RoomWarpEndpointStore
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : WarpEndpointStore {
        override suspend fun load(
            profileId: String,
            networkScopeKey: String,
        ): WarpEndpointCacheEntry? = database.read { it.warpEndpoints.load(profileId, networkScopeKey) }

        override suspend fun loadAll(profileId: String): List<WarpEndpointCacheEntry> =
            database.read {
                it.warpEndpoints.loadAll(profileId)
            }

        override suspend fun save(entry: WarpEndpointCacheEntry) = database.write { it.warpEndpoints.save(entry) }

        override suspend fun clear(
            profileId: String,
            networkScopeKey: String,
        ) = database.write { it.warpEndpoints.clear(profileId, networkScopeKey) }

        override suspend fun clearProfile(profileId: String) =
            database.write { it.warpEndpoints.clearProfile(profileId) }

        override suspend fun clearAll() = database.write { it.warpEndpoints.clearAll() }
    }
