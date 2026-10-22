package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.PreparedSubscriptionRefresh
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.SubscriptionAwgWrite
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

        /**
         * Commit short cross-store work only while this exact rehydrated request is current.
         * The action must not re-enter this repository. Network work belongs outside this guard.
         * Lock order is AWG row, then subscription group; group transforms cannot suspend.
         */
        suspend fun withCurrentRequest(
            expected: AwgActivationRequest,
            action: suspend () -> Boolean,
        ): Boolean =
            mutationMutex.withLock {
                profileMutations.recover()
                val current = dao.getProfile(expected.profileId)?.toSavedProfile()?.request
                current == expected && action()
            }

        /** Consistent read across journal-owned stores; the action must only read and never re-enter mutation APIs. */
        suspend fun readCurrentRequest(
            expected: AwgActivationRequest,
            action: suspend () -> Boolean,
        ): Boolean =
            mutationMutex.withLock {
                profileMutations.readRecovered {
                    val current = dao.getProfile(expected.profileId)?.toSavedProfile()?.request
                    current == expected && action()
                }
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
        suspend fun saveSubscriptionProfiles(profiles: List<AwgSubscriptionProfile>): List<SavedAwgProfile> =
            mutationMutex.withLock {
                if (profiles.isEmpty()) return@withLock emptyList()
                profileMutations.recover()
                prepareSubscriptionWrites(profiles).map { write ->
                    profileMutations.upsertAwg(
                        profileMutations.captureMutation(ProfileMutationOrigin.SubscriptionRefresh),
                        write.profile,
                        write.secrets,
                    )
                    profileMutations.readRecovered {
                        val saved = checkNotNull(dao.getProfile(write.profile.id)).toSavedProfile()
                        check(saved == write.profile.toSavedProfile(write.secrets)) {
                            "Imported AWG profile changed before receipt capture"
                        }
                        saved
                    }
                }
            }

        /** Lock order is AWG then recovery; the preparation callback never re-enters either owner. */
        suspend fun commitSubscriptionRefresh(
            expected: ProxyGroup,
            profiles: List<AwgSubscriptionProfile>,
            prepareGroup: (List<SavedAwgProfile>) -> ProxyGroup,
        ): Boolean =
            mutationMutex.withLock {
                require(profiles.all { it.groupId == expected.id })
                profileMutations.commitSubscriptionRefresh(expected) {
                    val writes = prepareSubscriptionWrites(profiles)
                    PreparedSubscriptionRefresh(
                        prepareGroup(writes.map { it.profile.toSavedProfile(it.secrets) }),
                        writes,
                    )
                }
            }

        private suspend fun prepareSubscriptionWrites(
            profiles: List<AwgSubscriptionProfile>,
        ): List<SubscriptionAwgWrite> {
            if (profiles.isEmpty()) return emptyList()
            profiles.requireValidSubscriptionProfiles()
            val previousOrigins =
                profiles.associate { profile ->
                    profile.origin to profile.previousMemberId?.let { profile.copy(memberId = it).origin }
                }
            val origins = profiles.map { it.origin }.toSet() + previousOrigins.values.filterNotNull()
            val existingMembers =
                dao.allProfiles().mapNotNull { row ->
                    row.subscriptionOrigin()?.takeIf { it in origins }?.let { it to row }
                }
            require(existingMembers.map { it.first }.distinct().size == existingMembers.size) {
                "Stored AWG subscription member identity is ambiguous"
            }
            val existingByOrigin = existingMembers.toMap()
            return profiles.map { profile ->
                val existing = existingByOrigin[profile.origin]?.toSavedProfile()
                val id = existing?.id ?: generateProfileId()
                val previous = previousOrigins[profile.origin]?.let { existingByOrigin[it]?.toSavedProfile() }
                val request = mergeSubscriptionRequest(profile, existing?.request ?: previous?.request)
                profileWrite(profile.name, request, id, profile.origin)
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
            val write = profileWrite(name, request, id, origin)
            profileMutations.upsertAwg(
                profileMutations.captureMutation(mutationOrigin),
                profile = write.profile,
                secrets = write.secrets,
            )
            return id
        }

        private fun profileWrite(
            name: String,
            request: AwgActivationRequest,
            id: String,
            origin: JsonElement?,
        ): SubscriptionAwgWrite {
            request.obfuscation.requireArm64Safe()
            val sanitized = request.copy(profileId = "", privateKey = "", presharedKey = "")
            val fields = encodeJson.encodeToJsonElement(sanitized).jsonObject.toMutableMap()
            if (origin != null) fields[SubscriptionOriginKey] = origin
            return SubscriptionAwgWrite(
                AwgProfileEntity(
                    id = id,
                    name = name,
                    requestJson = JsonObject(fields).toString(),
                    updatedAt = System.currentTimeMillis(),
                ),
                AwgSecrets(privateKey = request.privateKey, presharedKey = request.presharedKey),
            )
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

        private suspend fun AwgProfileEntity.toSavedProfile(): SavedAwgProfile = toSavedProfile(secretsFor(id))

        private fun AwgProfileEntity.toSavedProfile(secrets: AwgSecrets): SavedAwgProfile {
            // Decode tolerantly so an older blob with an additive field does not throw.
            val decoded = decodeJson.decodeFromString<AwgActivationRequest>(requestJson)
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
