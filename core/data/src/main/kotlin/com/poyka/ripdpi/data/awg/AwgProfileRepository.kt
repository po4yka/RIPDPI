package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * The serialized blob is the [AwgActivationRequest] with its `profileId` blanked
 * to the empty string, so the persisted bytes never pin a specific activation id
 * -- the id is authoritative on the row, not in the blob.
 */
@Singleton
class AwgProfileRepository
    @Inject
    constructor(
        private val dao: AwgProfileDao,
    ) {
        private val json = RipDpiEncodeDefaultsJson

        /** Observes every saved profile, newest-updated first, each stamped with its stable id. */
        fun observeProfiles(): Flow<List<SavedAwgProfile>> =
            dao.observeProfiles().map { rows -> rows.map { it.toSavedProfile() } }

        /** Loads a single saved profile by its stable [id], or `null` when none exists. */
        suspend fun load(id: String): SavedAwgProfile? = dao.getProfile(id)?.toSavedProfile()

        /**
         * Persists [request] under [name], returning the stable profile id used for the row.
         *
         * When [existingId] is `null` a fresh `"awg-<UUID>"` id is minted; otherwise the row
         * with [existingId] is updated in place and its id is reused. The stored blob blanks
         * the request's `profileId` -- the row id is the single source of truth.
         */
        suspend fun save(
            name: String,
            request: AwgActivationRequest,
            existingId: String? = null,
        ): String {
            val id = existingId ?: generateProfileId()
            val blob = json.encodeToString(request.copy(profileId = ""))
            dao.upsertProfile(
                AwgProfileEntity(
                    id = id,
                    name = name,
                    requestJson = blob,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return id
        }

        /** Deletes the saved profile identified by [id]; a no-op when it does not exist. */
        suspend fun delete(id: String) {
            val existing = dao.getProfile(id) ?: return
            dao.deleteProfile(existing)
        }

        private fun AwgProfileEntity.toSavedProfile(): SavedAwgProfile {
            val decoded = json.decodeFromString<AwgActivationRequest>(requestJson)
            // Stamp the stable row id as the profileId so the activation request is
            // identical across re-connects and carries the opaque (non-endpoint) id.
            return SavedAwgProfile(id = id, name = name, request = decoded.copy(profileId = id))
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
