package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.serialization.RipDpiContractJson
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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
                profileMutations.recover()
                val id = existingId ?: generateProfileId()
                val origin = dao.getProfile(id)?.subscriptionOrigin()
                saveLocked(name, request, id, origin, ProfileMutationOrigin.SavedEdit)
            }

        /**
         * Updates subscription-owned rows without matching manual or legacy profiles by name.
         * Empty imported private keys are templates; they cannot erase a locally supplied key.
         * Each row uses the existing recoverable row/credential mutation. Batch retries are idempotent.
         */
        suspend fun saveSubscriptionProfiles(profiles: List<AwgSubscriptionProfile>): List<String> =
            mutationMutex.withLock {
                if (profiles.isEmpty()) return@withLock emptyList()
                require(profiles.all { it.groupId.isNotBlank() && it.memberId.isNotBlank() }) {
                    "AWG subscription identity is missing"
                }
                require(profiles.map { it.origin }.distinct().size == profiles.size) {
                    "AWG subscription member identity is ambiguous"
                }
                profiles.forEach { it.request.obfuscation.requireArm64Safe() }
                profileMutations.recover()
                val origins = profiles.map { it.origin }.toSet()
                val existingMembers =
                    dao.allProfiles().mapNotNull { row ->
                        row.subscriptionOrigin()?.takeIf { it in origins }?.let { it to row }
                    }
                require(existingMembers.map { it.first }.distinct().size == existingMembers.size) {
                    "Stored AWG subscription member identity is ambiguous"
                }
                val existingByOrigin = existingMembers.toMap()
                profiles.map { profile ->
                    val existing = existingByOrigin[profile.origin]?.toSavedProfile()
                    val id = existing?.id ?: generateProfileId()
                    val request = mergeSubscriptionRequest(profile, existing?.request)
                    saveLocked(profile.name, request, id, profile.origin, ProfileMutationOrigin.SubscriptionRefresh)
                }
            }

        private fun mergeSubscriptionRequest(
            incoming: AwgSubscriptionProfile,
            existing: AwgActivationRequest?,
        ): AwgActivationRequest =
            if (existing == null) {
                incoming.request
            } else {
                incoming.request.copy(
                    privateKey = incoming.request.privateKey.ifEmpty { existing.privateKey },
                    dnsServers = if (incoming.dnsSpecified) incoming.request.dnsServers else existing.dnsServers,
                    allowedIps = if (incoming.allowedIpsSpecified) incoming.request.allowedIps else existing.allowedIps,
                    // Subscription formats do not import the editor-owned carrier settings.
                    carrier = existing.carrier,
                    carrierWsUrl = existing.carrierWsUrl,
                )
            }

        private fun AwgProfileEntity.subscriptionOrigin(): JsonElement? =
            decodeJson.parseToJsonElement(requestJson).jsonObject[SubscriptionOriginKey]

        private suspend fun saveLocked(
            name: String,
            request: AwgActivationRequest,
            id: String,
            origin: JsonElement?,
            mutationOrigin: ProfileMutationOrigin,
        ): String {
            request.obfuscation.requireArm64Safe()
            val sanitized = request.copy(profileId = "", privateKey = "", presharedKey = "")
            val fields = encodeJson.encodeToJsonElement(sanitized).jsonObject.toMutableMap()
            if (origin != null) fields[SubscriptionOriginKey] = origin
            val updatedProfile =
                AwgProfileEntity(
                    id = id,
                    name = name,
                    requestJson = JsonObject(fields).toString(),
                    updatedAt = System.currentTimeMillis(),
                )
            profileMutations.upsertAwg(
                profileMutations.captureMutation(mutationOrigin),
                profile = updatedProfile,
                secrets = AwgSecrets(privateKey = request.privateKey, presharedKey = request.presharedKey),
            )
            return id
        }

        /** Deletes the saved profile identified by [id]; a no-op when it does not exist. */
        suspend fun delete(id: String) {
            val preparation =
                profileMutations.captureMutation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitDeletion,
                )
            mutationMutex.withLock {
                profileMutations.recover()
                val existing = dao.getProfile(id) ?: return@withLock
                profileMutations.deleteAwg(
                    preparation,
                    existing.id,
                )
                Unit
            }
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
            private const val SubscriptionOriginKey = "subscriptionOrigin"

            /**
             * Mints an opaque, non-secret profile id. A random UUID, NOT derived from the
             * endpoint host/port: the id flows into native runtime telemetry, and an
             * endpoint-derived id would leak the peer host into a persisted/telemetry artifact
             * (network-fingerprint-privacy.md).
             */
            fun generateProfileId(): String = "awg-${UUID.randomUUID()}"
        }
    }
