package com.poyka.ripdpi.data

import android.content.Context
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Type of a [ProxyGroup]. A [BASIC] group is a static, user-owned collection of
 * profiles; a [SUBSCRIPTION] group is populated and refreshed from a remote
 * subscription URL (see [Subscription]).
 */
@Serializable
enum class ProxyGroupType {
    BASIC,
    SUBSCRIPTION,
}

/**
 * Structural flavor of a remote-subscription [link].
 *
 * The deployer issues two structurally different subscription URLs:
 * - [LONG_LIVED] — `/sub/<hash>`: refetchable, supports periodic refresh and
 *   `Subscription-Userinfo` accounting.
 * - [BOOTSTRAP] — `/bootstrap/<hash>`: single-use first-boot provisioning. The
 *   server deletes the on-disk hash file after the first successful GET, so the
 *   client must consume it exactly once and never re-fetch (subsequent GETs
 *   answer HTTP 410 Gone). The auto-update worker skips this kind entirely.
 */
@Serializable
enum class SubscriptionKind {
    LONG_LIVED,
    BOOTSTRAP,
}

/**
 * Remote-subscription metadata attached to a [ProxyGroup] whose
 * [ProxyGroup.type] is [ProxyGroupType.SUBSCRIPTION]. Field set is ported from
 * NekoBox's `SubscriptionBean` (link, token, traffic accounting, expiry, etc.).
 *
 * [kind] discriminates a refetchable long-lived subscription from a single-use
 * bootstrap token; [consumedAt] is the epoch-millis stamp set when a
 * [SubscriptionKind.BOOTSTRAP] token was consumed (it is `null` for an
 * unconsumed bootstrap and for every long-lived subscription). Epoch-millis is
 * used rather than `java.time.Instant` to match every other timestamp field on
 * this `@Serializable` entity.
 */
@Serializable
data class Subscription(
    val link: String = "",
    val token: String = "",
    val customUserAgent: String = "",
    val autoUpdate: Boolean = false,
    val autoUpdateDelay: Long = 0L,
    val lastUpdated: Long = 0L,
    val updateWhenConnectedOnly: Boolean = false,
    val forceResolve: Boolean = false,
    val deduplication: Boolean = false,
    val subscriptionUserinfo: String = "",
    val bytesUsed: Long = 0L,
    val bytesRemaining: Long = 0L,
    /** Credential/account expiry from `Subscription-Userinfo`, in Unix epoch seconds. */
    val expiryDate: Long = 0L,
    /** Delivery-token expiry from `ripdpi.expires`, in Unix epoch milliseconds. */
    val tokenExpiresAtEpochMillis: Long? = null,
    val lifecycleState: SubscriptionLifecycleState = SubscriptionLifecycleState.UNKNOWN,
    val lastRefreshAttemptAtEpochMillis: Long = 0L,
    val lastRefreshFailure: SubscriptionRefreshFailure? = null,
    val kind: SubscriptionKind = SubscriptionKind.LONG_LIVED,
    val consumedAt: Long? = null,
    /** Per-endpoint credentials, sealed together with the subscription blob. */
    val mirrors: SubscriptionMirrorSet = SubscriptionMirrorSet(),
    val migration: com.poyka.ripdpi.data.subscription.MigrationDelivery? = null,
) {
    /** `true` once a [SubscriptionKind.BOOTSTRAP] token has been consumed. */
    val isConsumed: Boolean
        get() = consumedAt != null
}

/**
 * A user-owned group that organizes [ProxyProfile] records. Replaces NekoBox's
 * `@Entity ProxyGroup`; chaining fields (`frontProxy` / `landingProxy`) are
 * intentionally omitted per project scope.
 *
 * [members] are the candidate profiles a selector / subscription group switches
 * between. A subscription refresh (see `SubscriptionAutoUpdateWorker`) and a
 * selector/urltest import both write the parsed profiles here so they are durably
 * stored and selectable rather than discarded. [failover] carries a selector
 * group's latency-driven failover policy (`null` == manual switching only) so the
 * urltest prober can run without re-parsing the source bundle. Both fields are
 * additive with empty/`null` defaults: an older persisted payload that omits them
 * deserializes unchanged, and `RipDpiJson` (no `encodeDefaults`) omits them when
 * empty, so existing serialization and backup output are unaffected.
 */
@Serializable
data class ProxyGroup(
    val id: String,
    val name: String,
    val type: ProxyGroupType,
    val order: Int,
    val isSelector: Boolean,
    val subscription: Subscription? = null,
    val members: List<ProxyProfile> = emptyList(),
    val failover: SelectorFailover? = null,
    /** Subscription-owned Android package routes from the last valid bundle. */
    val packageRoutingRules: List<PackageRoutingRule> = emptyList(),
    /** Explicit operator classification: these members cannot become new automatic probe winners. */
    val cloudflareMemberIds: Set<String> = emptySet(),
    /** Local persisted ownership; only the catalogue assigns a new generation. */
    val ownershipGeneration: String = "",
)

/**
 * Persisted, serializable mirror of a selector group's latency-driven failover
 * policy. The import layer's `FailoverPolicy.Urltest` is mapped onto this when a
 * group is stored; `null` on a [ProxyGroup] means manual switching only
 * (`FailoverPolicy.Manual`). Kept as a dedicated `@Serializable` type (rather than
 * making the import-layer sealed interface serializable) so the persistence schema
 * is decoupled from the parser model.
 *
 * @param probeUrl the URL probed for reachability / latency.
 * @param intervalSeconds probe cadence, in seconds.
 * @param toleranceMs latency tolerance band, in milliseconds: a candidate must beat
 *   the current selection's latency by more than this before a switch is made.
 */
@Serializable
data class SelectorFailover(
    val probeUrl: String,
    val intervalSeconds: Int,
    val toleranceMs: Int,
    val payloadPolicy: PayloadFailoverPolicy? = null,
)

/**
 * Discriminated union of proxy profiles. Unlike NekoBox's
 * bean-per-nullable-column `ProxyEntity`, RIPDPI models each protocol as a
 * dedicated [ProxyProfile] subtype. [RawConfig] is the fallback variant for an
 * opaque, already-rendered config string (e.g. an imported sing-box outbound).
 */
@Serializable
sealed interface ProxyProfile {
    val id: String
    val displayName: String
    val groupId: String

    @Serializable
    @SerialName("vless")
    data class Vless(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val server: String,
        val serverPort: Int,
        val uuid: String,
        val serverName: String? = null,
        val flow: String = "",
        val fingerprint: String? = null,
        val xhttpPath: String? = null,
        val xhttpHost: String? = null,
        val xhttpMode: String = RelayXhttpModeAuto,
    ) : ProxyProfile

    /**
     * VLESS outbound secured with REALITY (XTLS/Vision). Carries the full set of
     * connection-critical parameters that [Vless] intentionally omits for plain
     * proxies. A parser emits this variant IFF reality material is present (sing-box
     * `tls.reality.enabled == true` with a non-empty `public_key`; URI
     * import requires a non-empty `pbk` query param.
     */
    @Serializable
    @SerialName("vless-reality")
    data class VlessReality(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val server: String,
        val serverPort: Int,
        val uuid: String,
        val realityPublicKey: String,
        val realityShortId: String,
        val serverName: String,
        /** Defaults to `xtls-rprx-vision` when omitted from the source. */
        val flow: String = "xtls-rprx-vision",
        /** Optional uTLS fingerprint (e.g. `chrome`). `null` means use library default. */
        val fingerprint: String? = null,
        /** xhttp transport path; `null` when transport is plain TCP. */
        val xhttpPath: String? = null,
        /** xhttp transport host override; `null` when transport is plain TCP. */
        val xhttpHost: String? = null,
        /** xhttp mode (`auto`, `stream-up`, or `stream-one`); used only when xhttp transport is active. */
        val xhttpMode: String = RelayXhttpModeAuto,
    ) : ProxyProfile

    @Serializable
    @SerialName("shadowsocks")
    data class Shadowsocks(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val server: String,
        val serverPort: Int,
        val method: String,
        val password: String,
    ) : ProxyProfile

    @Serializable
    @SerialName("trojan")
    data class Trojan(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val server: String,
        val serverPort: Int,
        val password: String,
        /**
         * TLS SNI / server name. `null` falls back to [server] at activation.
         * A trojan node that fronts a masquerade domain different from its connect
         * host needs this; mirrors [AnyTls.serverName]. Additive with a `null`
         * default, so older persisted payloads and backups are byte-stable.
         */
        val serverName: String? = null,
    ) : ProxyProfile

    @Serializable
    @SerialName("hysteria2")
    data class Hysteria2(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val server: String,
        val serverPort: Int,
        val password: String,
        /** TLS SNI. `null` falls back to [server] for legacy persisted profiles. */
        val serverName: String? = null,
        /**
         * Salamander obfuscation password. `null` means no obfuscation is
         * configured. When delivered via the RIPDPI extended bundle
         * (`ripdpi.hysteria_extras`) this is populated from the `obfs.password`
         * field; otherwise it is settings-only and must be configured in the
         * relay editor after import.
         */
        val obfsPassword: String? = null,
        /**
         * Whether to skip TLS certificate verification. Carried from the RIPDPI
         * extended bundle when present; wire-to-runtime mapping is best-effort /
         * follow-up if the relay DTO does not expose it.
         */
        val insecure: Boolean? = null,
        /**
         * Certificate pin: SHA-256 of the server leaf certificate DER, as 64
         * lowercase hex characters (see [normalizeHysteria2PinSha256]). Comes
         * from the share link `pinSHA256` parameter or the Clash `fingerprint`
         * key. `null` means no pin.
         *
         * With [insecure] = `true`, the pin replaces CA validation. With
         * [insecure] unset or `false`, the native client requires both a valid
         * CA chain and a matching pin, as upstream Hysteria2 does.
         */
        val pinSha256: String? = null,
        /**
         * Port-hopping range string (e.g. `"20000-40000"`). Carried from the
         * RIPDPI extended bundle when present; wire-to-runtime mapping is
         * best-effort / follow-up.
         */
        val portHopPorts: String? = null,
        /**
         * Port-hopping interval string (e.g. `"30s"`). Carried from the RIPDPI
         * extended bundle when present; wire-to-runtime mapping is best-effort /
         * follow-up.
         */
        val portHopInterval: String? = null,
        /**
         * The upstream Hysteria2 release tag whose Salamander algorithm the
         * server runs (`ripdpi.hysteria_extras.<tag>.salamander_upstream_tag`,
         * e.g. `"v2.9.0"`). `null` when the bundle omits it. Salamander can
         * change between Hysteria2 releases; comparing this against the version
         * the bundled obfuscator implements lets the client warn the user on a
         * skew instead of failing the handshake opaquely.
         */
        val salamanderUpstreamTag: String? = null,
    ) : ProxyProfile

    @Serializable
    @SerialName("anytls")
    data class AnyTls(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val server: String,
        val serverPort: Int,
        val serverName: String,
        val password: String,
    ) : ProxyProfile {
        /** Masks the password so it never reaches a log or diagnostics surface. */
        override fun toString(): String =
            "AnyTls(id=$id, displayName=$displayName, groupId=$groupId, server=$server, " +
                "serverPort=$serverPort, serverName=$serverName, password=<redacted>)"
    }

    /**
     * Mieru outbound. Unlike Trojan-Go, Mieru is actively developed, so it is
     * **not** a legacy protocol. Carries the endpoint, the username/password
     * credentials, the transport [protocol] (`tcp` | `udp`), the [multiplexing]
     * level (`off` | `low` | `middle` | `high`), and the [mtu] (`1280..1500`).
     */
    @Serializable
    @SerialName("mieru")
    data class Mieru(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val server: String,
        val serverPort: Int,
        val username: String,
        val password: String,
        val protocol: String = "tcp",
        val multiplexing: String = "middle",
        val mtu: Int = 1400,
    ) : ProxyProfile {
        override fun toString(): String =
            "Mieru(id=$id, displayName=$displayName, groupId=$groupId, server=$server, " +
                "serverPort=$serverPort, username=<redacted>, password=<redacted>, " +
                "protocol=$protocol, multiplexing=$multiplexing, mtu=$mtu)"
    }

    /**
     * SSH outbound (direct-tcpip forwarding). Carries the endpoint, the
     * [username], the [authType] selector (`password` | `private_key`), and the
     * auth-type-appropriate secret. [hostKeyFingerprint] pins the expected server
     * host key (`SHA256:...`); [strictHostKey] disables trust-on-first-use.
     *
     * The password, private key, and passphrase are masked in [toString] so they
     * never reach a log or diagnostics surface; [equals]/[copy] still expose them
     * for URI round-trip tests. SSH is editor-first, but a synthetic `ssh://`
     * scheme (RIPDPI-invented) round-trips the full profile for subscription
     * import — see `ProxyUriCodec`.
     */
    @Serializable
    @SerialName("ssh")
    data class Ssh(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val server: String,
        val serverPort: Int,
        val username: String,
        /**
         * `"password"` or `"private_key"` — the native ripdpi-ssh auth
         * selectors (RelaySshAuthType* in core:data:settings).
         */
        val authType: String = "password",
        val password: String? = null,
        val privateKey: String? = null,
        val privateKeyPassphrase: String? = null,
        val hostKeyFingerprint: String? = null,
        val strictHostKey: Boolean = false,
    ) : ProxyProfile {
        override fun toString(): String =
            "Ssh(id=$id, displayName=$displayName, groupId=$groupId, server=$server, " +
                "serverPort=$serverPort, username=$username, authType=$authType, " +
                "password=${redactedPresence(password)}, privateKey=${redactedPresence(privateKey)}, " +
                "privateKeyPassphrase=${redactedPresence(privateKeyPassphrase)}, " +
                "hostKeyFingerprint=$hostKeyFingerprint, strictHostKey=$strictHostKey)"
    }

    @Serializable
    @SerialName("raw-config")
    data class RawConfig(
        override val id: String,
        override val displayName: String,
        override val groupId: String,
        val config: String,
    ) : ProxyProfile
}

/**
 * Renders a secret's presence without disclosing it: `null` when absent,
 * `<redacted>` when set. Used by [ProxyProfile.Ssh.toString] so a diagnostics
 * render still distinguishes a password-auth from a key-auth profile without
 * leaking the material itself.
 */
private fun redactedPresence(secret: String?): String = if (secret == null) "null" else "<redacted>"

/**
 * Schema versioning for persisted [ProxyGroup] payloads.
 *
 * When the on-disk shape changes, bump [SCHEMA_VERSION] and add a branch to
 * [migrate] that upgrades a payload from the previous version. Each persisted
 * blob is stored alongside its writer version (see
 * [SharedPreferencesProxyGroupRepository]) so reads can replay forward
 * migrations before deserialization.
 */
object ProxyGroupSchema {
    const val SCHEMA_VERSION: Int = 2

    /** Persist a one-way upgrade before any caller can capture its ownership generation. */
    fun read(
        json: String,
        fromVersion: Int,
        persist: (String) -> Unit,
    ): String {
        val migrated = migrate(json, fromVersion)
        if (fromVersion != SCHEMA_VERSION) persist(migrated)
        return migrated
    }

    fun migrate(
        json: String,
        fromVersion: Int,
    ): String {
        require(fromVersion in 1..SCHEMA_VERSION) { "Unsupported group schema" }
        val serializer = ListSerializer(ProxyGroup.serializer())
        val groups = RipDpiJson.decodeFromString(serializer, json)
        if (fromVersion == SCHEMA_VERSION) {
            require(groups.all { it.ownershipGeneration.isNotBlank() }) { "Missing group ownership generation" }
            return json
        }
        return RipDpiJson.encodeToString(
            serializer,
            groups.map { it.withNewOwnershipGeneration() },
        )
    }
}

/**
 * Repository over the user's [ProxyGroup] collection. Exposes imperative
 * mutators plus a reactive [groups] stream for UI binding.
 */
interface ProxyGroupRepository {
    /** Inserts [group]. Replaces any existing group with the same id. */
    suspend fun add(group: ProxyGroup)

    /** Replaces the stored group sharing [ProxyGroup.id] with [group]. */
    suspend fun update(group: ProxyGroup)

    /** Atomically transforms [id] in production storage and returns the stored result, or `null` when absent. */
    suspend fun updateGroup(
        id: String,
        transform: (ProxyGroup) -> ProxyGroup,
    ): ProxyGroup? {
        val current = list().firstOrNull { it.id == id } ?: return null
        val updated = transform(current)
        update(updated)
        return updated
    }

    /** Atomically updates only the subscription metadata attached to [id]. */
    suspend fun updateSubscription(
        id: String,
        transform: (Subscription) -> Subscription,
    ): ProxyGroup? =
        updateGroup(id) { group ->
            val subscription = group.subscription ?: return@updateGroup group
            group.copy(subscription = transform(subscription))
        }

    /** Removes the group identified by [id]. No-op when absent. */
    suspend fun delete(
        preparation: ProfileMutationPreparation,
        id: String,
    )

    /** Loads stored groups and publishes the validated snapshot; read failures propagate to the caller. */
    suspend fun list(): List<ProxyGroup>

    /** Replaces the group collection after the caller durably reserves its explicit restore/reset receipt. */
    suspend fun replaceAll(
        receipt: DurableCommandReceipt,
        groups: List<ProxyGroup>,
    )

    suspend fun compensateReplacement(groups: List<ProxyGroup>)

    /**
     * Observes validated snapshots without reading storage. Waits for a successful [list] or mutation
     * before its first emission, so early collectors cannot bypass guarded startup loading.
     */
    fun groups(): Flow<List<ProxyGroup>>
}

@Singleton
class SharedPreferencesProxyGroupRepository
    @Inject
    constructor(
        private val catalog: ProxyGroupCatalog,
        private val intentPreparation: PauseMutationPreparationSource,
    ) : ProxyGroupRepository {
        override suspend fun add(group: ProxyGroup) {
            val preparation = intentPreparation.captureMutation(ProfileMutationOrigin.ImportOnly)
            intentPreparation.mutateCatalog(preparation) {
                catalog.add(group)
            }
        }

        override suspend fun update(group: ProxyGroup) {
            val preparation = intentPreparation.captureMutation(ProfileMutationOrigin.SavedEdit)
            intentPreparation.mutateCatalog(preparation) {
                catalog.update { groups -> groups.map { if (it.id == group.id) group else it } }
            }
        }

        override suspend fun updateGroup(
            id: String,
            transform: (ProxyGroup) -> ProxyGroup,
        ): ProxyGroup? {
            val preparation = intentPreparation.captureMutation(ProfileMutationOrigin.SavedEdit)
            return intentPreparation.mutateCatalog(preparation) { catalog.updateGroup(id, transform) }
        }

        override suspend fun delete(
            preparation: ProfileMutationPreparation,
            id: String,
        ) {
            intentPreparation.mutateCatalog(preparation) { catalog.update { it.filterNot { group -> group.id == id } } }
        }

        override suspend fun list(): List<ProxyGroup> = catalog.load()

        override suspend fun replaceAll(
            receipt: DurableCommandReceipt,
            groups: List<ProxyGroup>,
        ) {
            intentPreparation.mutateReservedCatalog(receipt) { catalog.replace(groups) }
        }

        override suspend fun compensateReplacement(groups: List<ProxyGroup>) {
            val preparation = intentPreparation.captureMutation(ProfileMutationOrigin.Compensation)
            intentPreparation.mutateCatalog(preparation) { catalog.replace(groups) }
        }

        override fun groups(): Flow<List<ProxyGroup>> = catalog.groups()
    }

/**
 * Persistence port for the serialized [ProxyGroup] collection. The blob embeds
 * member credentials (passwords, VLESS UUIDs, SSH private keys + passphrases) and
 * the subscription token, so the production binding seals it AES-256-GCM at rest.
 * Tests bind an in-memory fake, mirroring the `AwgCredentialStore` /
 * `WarpCredentialStore` split: AndroidKeyStore is unavailable under Robolectric, so
 * the seal itself is exercised only by instrumented tests while the repository
 * wiring is unit-tested against the fake.
 */
interface ProxyGroupBlobStore {
    /** The persisted group-list JSON (decrypted + schema-migrated), or `null` when empty. */
    fun read(): String?

    /** Seals and persists [json] at the current schema version. */
    fun write(json: String)

    /** Removes all persisted group state. */
    fun clear()
}

/**
 * AndroidKeyStore-backed [ProxyGroupBlobStore]. The group blob is AES-256-GCM
 * sealed via [KeystoreEncryptedPreferences] (the same primitive the WARP /
 * AmneziaWG credential stores use) under [SealedGroupsKey] in `proxy_group_cache`.
 * A pre-encryption install that still holds the legacy plaintext blob under
 * [GroupsKey] is migrated forward on first read and the plaintext erased.
 */
@Singleton
class KeystoreProxyGroupBlobStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ProxyGroupBlobStore {
        private val preferences = context.getSharedPreferences(GroupsPrefsName, Context.MODE_PRIVATE)
        private val secure =
            KeystoreEncryptedPreferences(
                preferences = preferences,
                keyAlias = GroupsKeyAlias,
                durableReads = true,
            )

        override fun read(): String? =
            secure.withDurableState {
                val version = preferences.getInt(SchemaVersionKey, 1)
                // Unreadable sealed reserves are not an empty catalogue. In particular,
                // never revive obsolete plaintext after authentication or decoding fails.
                val raw =
                    secure.getStringStrict(SealedGroupsKey) ?: migrateLegacyPlaintext() ?: return@withDurableState null
                ProxyGroupSchema.read(raw, version, ::write)
            }

        override fun write(json: String) {
            secure.putString(SealedGroupsKey, json) {
                remove(GroupsKey)
                putInt(SchemaVersionKey, ProxyGroupSchema.SCHEMA_VERSION)
            }
        }

        override fun clear() = secure.clear()

        /**
         * One-time upgrade for a pre-encryption install: lifts the legacy plaintext
         * group blob into the AES-256-GCM [secure] store and deletes the plaintext
         * key, so credentials never linger unencrypted past the first read. Returns
         * the raw JSON for [read] to decode, or `null` when no legacy blob exists.
         */
        private fun migrateLegacyPlaintext(): String? {
            val legacy = preferences.getString(GroupsKey, null) ?: return null
            secure.putString(SealedGroupsKey, legacy) { remove(GroupsKey) }
            return legacy
        }

        private companion object {
            const val GroupsPrefsName = "proxy_group_cache"

            /** Legacy plaintext key; read-and-migrate only, never written anew. */
            const val GroupsKey = "proxy-groups"

            /** AES-256-GCM ciphertext of the group blob (members + subscription token). */
            const val SealedGroupsKey = "proxy-groups-sealed"
            const val GroupsKeyAlias = "ripdpi_proxy_groups"
            const val SchemaVersionKey = "proxy-groups-schema-version"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ProxyGroupStoreModule {
    @Binds
    @Singleton
    abstract fun bindProxyGroupRepository(repository: SharedPreferencesProxyGroupRepository): ProxyGroupRepository

    @Binds
    @Singleton
    abstract fun bindProxyGroupBlobStore(store: KeystoreProxyGroupBlobStore): ProxyGroupBlobStore
}
