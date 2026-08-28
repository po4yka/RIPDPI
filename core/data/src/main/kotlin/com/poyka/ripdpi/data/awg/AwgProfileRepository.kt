package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.rollbackStoreMutation
import com.poyka.ripdpi.serialization.RipDpiContractJson
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A standalone AmneziaWG profile loaded from durable storage.
 *
 * [id] is the stable opaque profile id (reused on every re-connect). [name] is
 * the user-facing label. [request] is the fully-rehydrated activation request,
 * already stamped with [id] as its `profileId`, ready to hand to the service
 * layer's `StandaloneAmneziaWgActivator.activate`.
 */
data class SavedAwgProfile(
    val id: String,
    val name: String,
    val request: AwgActivationRequest,
)

/**
 * Repository over the durable standalone-AmneziaWG profile store.
 *
 * Owns the **stable-id discipline** that closes the per-activation-UUID deferral
 * in `AmneziaWgProfileViewModel`:
 * - [save] mints an opaque `"awg-<UUID>"` id exactly once (when [existingId] is
 *   `null`); a re-save under an [existingId] updates that row in place, so the
 *   profile keeps its id across edits and re-connects.
 * - [load] / [observeProfiles] rehydrate the persisted [AwgActivationRequest]
 *   and stamp it with the row's stable [AwgProfileEntity.id] as `profileId`, so
 *   the activation request the service layer sees is identical across
 *   re-connects.
 *
 * **Privacy invariant:** the minted id is the opaque UUID form, never derived
 * from the endpoint -- it is the value that reaches native runtime telemetry
 * (network-fingerprint-privacy.md). The endpoint host/port are serialized inside
 * the blob as user config but never logged or exported.
 *
 * **At-rest secrets:** the WireGuard `privateKey` and `presharedKey` are NEVER
 * persisted in the Room blob. They are sealed in [AwgCredentialStore] (AES-256-GCM
 * via AndroidKeyStore, mirroring the WARP credential split) keyed by the stable
 * profile id, then re-injected into the rehydrated request on load. The serialized
 * blob therefore carries no `privateKey`, `presharedKey`, or `profileId` -- the id
 * is authoritative on the row, and the secrets are authoritative in the keystore.
 *
 * The persisted blob is decoded with the tolerant [RipDpiContractJson] so a future
 * additive [AwgActivationRequest] field does not make old rows throw on decode;
 * encoding keeps [RipDpiEncodeDefaultsJson] (defaults written) for a stable blob.
 */
@Singleton
class AwgProfileRepository
    @Inject
    constructor(
        private val dao: AwgProfileDao,
        private val credentialStore: AwgCredentialStore,
        private val profileMutations: ProfileMutationCoordinator,
    ) {
        constructor(
            dao: AwgProfileDao,
            credentialStore: AwgCredentialStore,
        ) : this(dao, credentialStore, DirectAwgProfileMutationCoordinator(dao, credentialStore))

        private val encodeJson = RipDpiEncodeDefaultsJson
        private val decodeJson = RipDpiContractJson
        private val mutationMutex = Mutex()

        /** Observes every saved profile, newest-updated first, each stamped with its stable id. */
        fun observeProfiles(): Flow<List<SavedAwgProfile>> =
            flow {
                profileMutations.recover()
                emitAll(dao.observeProfiles().map { rows -> rows.toSavedProfiles() })
            }

        // List.map cannot call the suspend secret-rehydration, so build explicitly.
        private suspend fun List<AwgProfileEntity>.toSavedProfiles(): List<SavedAwgProfile> {
            val profiles = ArrayList<SavedAwgProfile>(size)
            for (row in this) {
                profiles.add(row.toSavedProfile())
            }
            return profiles
        }

        /** Loads a single saved profile by its stable [id], or `null` when none exists. */
        suspend fun load(id: String): SavedAwgProfile? =
            mutationMutex.withLock {
                profileMutations.recover()
                dao.getProfile(id)?.toSavedProfile()
            }

        private suspend fun secretsFor(id: String): AwgSecrets = credentialStore.load(id) ?: AwgSecrets()

        /**
         * Persists [request] under [name], returning the stable profile id used for the row.
         *
         * When [existingId] is `null` a fresh `"awg-<UUID>"` id is minted; otherwise the row
         * with [existingId] is updated in place and its id is reused. The stored blob blanks
         * the request's `profileId` AND both secret fields -- the row id is the single source
         * of truth for the id, and the secrets are sealed in [AwgCredentialStore].
         */
        suspend fun save(
            name: String,
            request: AwgActivationRequest,
            existingId: String? = null,
        ): String =
            mutationMutex.withLock {
                request.obfuscation.requireArm64Safe()
                val id = existingId ?: generateProfileId()
                // Strip the id and the two secrets from the Room blob; secrets go to the keystore.
                val sanitized = request.copy(profileId = "", privateKey = "", presharedKey = "")
                val blob = encodeJson.encodeToString(sanitized)
                val updatedProfile =
                    AwgProfileEntity(
                        id = id,
                        name = name,
                        requestJson = blob,
                        updatedAt = System.currentTimeMillis(),
                    )
                profileMutations.upsertAwg(
                    profile = updatedProfile,
                    secrets = AwgSecrets(privateKey = request.privateKey, presharedKey = request.presharedKey),
                )
                id
            }

        /** Deletes the saved profile identified by [id]; a no-op when it does not exist. */
        suspend fun delete(id: String) =
            mutationMutex.withLock {
                profileMutations.recover()
                val existing = dao.getProfile(id) ?: return@withLock
                profileMutations.deleteAwg(existing.id)
                Unit
            }

        private suspend fun AwgProfileEntity.toSavedProfile(): SavedAwgProfile {
            // Decode tolerantly so an older blob with an additive field does not throw.
            val decoded = decodeJson.decodeFromString<AwgActivationRequest>(requestJson)
            val secrets = secretsFor(id)
            // Stamp the stable row id as the profileId and re-inject the sealed secrets so the
            // activation request is identical across re-connects and carries the opaque id.
            return SavedAwgProfile(
                id = id,
                name = name,
                request =
                    decoded.copy(
                        profileId = id,
                        privateKey = secrets.privateKey,
                        presharedKey = secrets.presharedKey,
                    ),
            )
        }

        companion object {
            /**
             * Mints an opaque, non-secret profile id. A random UUID, NOT derived from the
             * endpoint host/port: the id flows into native runtime telemetry, and an
             * endpoint-derived id would leak the peer host into a persisted/telemetry artifact
             * (network-fingerprint-privacy.md).
             */
            fun generateProfileId(): String = "awg-${UUID.randomUUID()}"
        }
    }

private class DirectAwgProfileMutationCoordinator(
    private val dao: AwgProfileDao,
    private val credentials: AwgCredentialStore,
) : ProfileMutationCoordinator {
    override suspend fun recover() = Unit

    override suspend fun <T> readRecovered(block: suspend () -> T): T = block()

    override suspend fun runReset(block: suspend () -> Unit) = block()

    override suspend fun upsertAwg(
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ) {
        val previousProfile = dao.getProfile(profile.id)
        val previousSecrets = credentials.load(profile.id)
        runCatching {
            credentials.save(profile.id, secrets)
            dao.upsertProfile(profile)
        }.exceptionOrNull()?.rollbackStoreMutation(
            {
                if (previousSecrets ==
                    null
                ) {
                    credentials.clear(profile.id)
                } else {
                    credentials.save(profile.id, previousSecrets)
                }
            },
            {
                if (previousProfile == null) {
                    dao.getProfile(profile.id)?.let { dao.deleteProfile(it) }
                } else {
                    dao.upsertProfile(previousProfile)
                }
            },
        )
    }

    override suspend fun deleteAwg(profileId: String) {
        credentials.clear(profileId)
        dao.getProfile(profileId)?.let { dao.deleteProfile(it) }
    }

    override suspend fun upsertRelay(
        profile: com.poyka.ripdpi.data.RelayProfileRecord,
        credentials: com.poyka.ripdpi.data.RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: com.poyka.ripdpi.proto.AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord?,
    ) = unsupported()

    override suspend fun upsertWarp(
        profile: com.poyka.ripdpi.data.WarpProfile,
        credentials: com.poyka.ripdpi.data.WarpCredentials,
        endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ) = unsupported()

    override suspend fun deleteWarp(
        profileId: String,
        clearActive: Boolean,
    ) = unsupported()

    override suspend fun deactivateWarp(profileId: String) = unsupported()

    override suspend fun replacePrivateBackup(
        data: com.poyka.ripdpi.data.backup.BackupPrivateDataV1,
        rollbackData: com.poyka.ripdpi.data.backup.BackupPrivateDataV1?,
    ) = unsupported()

    private fun unsupported(): Nothing = error("Only AWG mutations are supported")
}
