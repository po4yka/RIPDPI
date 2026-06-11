package com.poyka.ripdpi.data

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    val expiryDate: Long = 0L,
    val kind: SubscriptionKind = SubscriptionKind.LONG_LIVED,
    val consumedAt: Long? = null,
) {
    /** `true` once a [SubscriptionKind.BOOTSTRAP] token has been consumed. */
    val isConsumed: Boolean
        get() = consumedAt != null
}

/**
 * A user-owned group that organizes [ProxyProfile] records. Replaces NekoBox's
 * `@Entity ProxyGroup`; chaining fields (`frontProxy` / `landingProxy`) are
 * intentionally omitted per project scope.
 */
@Serializable
data class ProxyGroup(
    val id: String,
    val name: String,
    val type: ProxyGroupType,
    val order: Int,
    val isSelector: Boolean,
    val subscription: Subscription? = null,
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
    ) : ProxyProfile

    /**
     * VLESS outbound secured with REALITY (XTLS/Vision). Carries the full set of
     * connection-critical parameters that [Vless] intentionally omits for plain
     * proxies. A parser emits this variant IFF reality material is present (sing-box
     * `tls.reality.enabled == true` or a non-empty `public_key`; URI
     * `security=reality` or a `pbk` query param).
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
    ) : ProxyProfile

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
    /** Current persisted-schema version. Bump on any breaking shape change. */
    const val SCHEMA_VERSION: Int = 1

    /**
     * Forward-migrates a raw JSON [json] payload written by [fromVersion] up to
     * [SCHEMA_VERSION]. The current version is the identity transform; add
     * `when` branches here as new versions are introduced.
     */
    fun migrate(
        json: String,
        fromVersion: Int,
    ): String {
        var payload = json
        var version = fromVersion
        while (version < SCHEMA_VERSION) {
            payload =
                when (version) {
                    // Example for a future v1 -> v2 migration:
                    // 1 -> migrateV1ToV2(payload)
                    else -> payload
                }
            version += 1
        }
        return payload
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

    /** Removes the group identified by [id]. No-op when absent. */
    suspend fun delete(id: String)

    /** Returns all stored groups ordered by [ProxyGroup.order]. */
    suspend fun list(): List<ProxyGroup>

    /**
     * Replaces the entire group collection with [groups]. Used by the
     * backup-restore swap so a restore never leaves a partially overwritten group
     * set.
     *
     * The default implementation deletes the current groups and re-adds [groups]
     * through the existing mutators; the SharedPreferences-backed repository
     * overrides it with a single atomic persisted write. The default keeps existing
     * test fakes source-compatible without forcing each to reimplement the swap.
     */
    suspend fun replaceAll(groups: List<ProxyGroup>) {
        list().forEach { delete(it.id) }
        groups.forEach { add(it) }
    }

    /** Hot stream of the group collection; re-emits after every mutation. */
    fun groups(): Flow<List<ProxyGroup>>
}

@Singleton
class SharedPreferencesProxyGroupRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ProxyGroupRepository {
        private val preferences = context.getSharedPreferences(GroupsPrefsName, Context.MODE_PRIVATE)
        private val json = RipDpiJson
        private val listSerializer = ListSerializer(ProxyGroup.serializer())
        private val state = MutableStateFlow(readGroups())

        override suspend fun add(group: ProxyGroup) {
            val next = readGroups().filterNot { it.id == group.id } + group
            writeGroups(next)
        }

        override suspend fun update(group: ProxyGroup) {
            val next = readGroups().map { if (it.id == group.id) group else it }
            writeGroups(next)
        }

        override suspend fun delete(id: String) {
            val next = readGroups().filterNot { it.id == id }
            writeGroups(next)
        }

        override suspend fun list(): List<ProxyGroup> = readGroups()

        override suspend fun replaceAll(groups: List<ProxyGroup>) {
            writeGroups(groups)
        }

        override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()

        /** Clears all persisted groups. Intended for tests and reset flows. */
        fun clearAll() {
            preferences.edit().clear().apply()
            state.value = emptyList()
        }

        private fun readGroups(): List<ProxyGroup> {
            val version = preferences.getInt(SchemaVersionKey, ProxyGroupSchema.SCHEMA_VERSION)
            val raw = preferences.getString(GroupsKey, null) ?: return emptyList()
            val migrated = ProxyGroupSchema.migrate(raw, version)
            return json.decodeFromString(listSerializer, migrated)
        }

        private fun writeGroups(groups: List<ProxyGroup>) {
            val ordered = groups.sortedBy(ProxyGroup::order)
            preferences
                .edit()
                .putString(GroupsKey, json.encodeToString(listSerializer, ordered))
                .putInt(SchemaVersionKey, ProxyGroupSchema.SCHEMA_VERSION)
                .apply()
            state.value = ordered
        }

        private companion object {
            const val GroupsPrefsName = "proxy_group_cache"
            const val GroupsKey = "proxy-groups"
            const val SchemaVersionKey = "proxy-groups-schema-version"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ProxyGroupStoreModule {
    @Binds
    @Singleton
    abstract fun bindProxyGroupRepository(repository: SharedPreferencesProxyGroupRepository): ProxyGroupRepository
}
