package com.poyka.ripdpi.data

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

const val RelayVlessFlowVision = "xtls-rprx-vision"
const val RelayVlessFlowVisionUdp443 = "xtls-rprx-vision-udp443"
const val RelayXhttpModeAuto = "auto"
const val RelayXhttpModeStreamUp = "stream-up"
const val RelayXhttpModeStreamOne = "stream-one"

@Serializable
data class RelayProfileRecord(
    val id: String = DefaultRelayProfileId,
    val kind: String = RelayKindOff,
    val presetId: String = "",
    val outboundBindIp: String = "",
    val jurisdiction: String = "",
    val operatorName: String = "",
    val server: String = "",
    val serverPort: Int = 443,
    val serverName: String = "",
    val securityLayer: String = RelaySecurityLayerReality,
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val vlessFlow: String = RelayVlessFlowVision,
    /** Source uTLS fingerprint alias retained for per-profile native TLS selection and export. */
    val vlessFingerprint: String = "",
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
    val xhttpMode: String = RelayXhttpModeAuto,
    val mieruProtocol: String = "tcp",
    val mieruMultiplexing: String = "middle",
    val mieruMtu: Int = 1400,
    val cloudflareTunnelMode: String = RelayCloudflareTunnelModeConsumeExisting,
    val cloudflarePublishLocalOriginUrl: String = "",
    val cloudflareCredentialsRef: String = "",
    val chainEntryServer: String = "",
    val chainEntryPort: Int = 443,
    val chainEntryServerName: String = "",
    val chainEntryPublicKey: String = "",
    val chainEntryShortId: String = "",
    val chainEntryProfileId: String = "",
    val chainExitServer: String = "",
    val chainExitPort: Int = 443,
    val chainExitServerName: String = "",
    val chainExitPublicKey: String = "",
    val chainExitShortId: String = "",
    val chainExitProfileId: String = "",
    // Ordered intermediate chain-relay hop profile IDs (positions strictly
    // between entry and exit) for N-hop (3..4) chains. Empty for plain two-hop
    // chains; the entry/exit profile IDs above are hop 0 / hop last.
    val chainMiddleProfileIds: List<String> = emptyList(),
    val masqueUrl: String = "",
    val masqueTcpProtocol: String = "http2",
    val masqueUseHttp2Fallback: Boolean = true,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val shadowTlsInnerProfileId: String = "",
    val naivePath: String = "",
    val appsScriptScriptIds: List<String> = emptyList(),
    val appsScriptGoogleIp: String = "",
    val appsScriptFrontDomain: String = "",
    val appsScriptSniHosts: List<String> = emptyList(),
    val appsScriptVerifySsl: Boolean = DefaultRelayAppsScriptVerifySsl,
    val appsScriptParallelRelay: Boolean = false,
    val appsScriptDirectHosts: List<String> = emptyList(),
    val ptBridgeLine: String = "",
    val ptWebTunnelUrl: String = "",
    val ptSnowflakeBrokerUrl: String = DefaultSnowflakeBrokerUrl,
    val ptSnowflakeFrontDomain: String = DefaultSnowflakeFrontDomain,
    // SSH non-secret config. The endpoint reuses [server]/[serverPort]; the
    // username/password/private-key/passphrase live in [RelayCredentialRecord].
    val sshAuthType: String = RelaySshAuthTypePassword,
    val sshHostKeyFingerprint: String = "",
    val sshStrictHostKey: Boolean = false,
    val localSocksHost: String = DefaultRelayLocalSocksHost,
    val localSocksPort: Int = DefaultRelayLocalSocksPort,
    val udpEnabled: Boolean = false,
    val tcpFallbackEnabled: Boolean = true,
    val finalmaskType: String = RelayFinalmaskTypeOff,
    val finalmaskHeaderHex: String = "",
    val finalmaskTrailerHex: String = "",
    val finalmaskRandRange: String = "",
    val finalmaskSudokuSeed: String = "",
    val finalmaskFragmentPackets: Int = 0,
    val finalmaskFragmentMinBytes: Int = 0,
    val finalmaskFragmentMaxBytes: Int = 0,
)

fun RelayProfileRecord.isSupportedChainEntryHop(): Boolean =
    when (kind) {
        RelayKindVlessReality -> normalizeRelayVlessTransport(vlessTransport, kind) == RelayVlessTransportRealityTcp

        RelayKindMasque,
        RelayKindTrojan,
        RelayKindAnyTls,
        RelayKindShadowsocks,
        RelayKindShadowTlsV3,
        RelayKindHysteria2,
        RelayKindTuicV5,
        -> true

        else -> false
    }

fun RelayProfileRecord.isSupportedChainNonEntryHop(): Boolean =
    when (kind) {
        RelayKindVlessReality -> normalizeRelayVlessTransport(vlessTransport, kind) == RelayVlessTransportRealityTcp

        RelayKindMasque,
        RelayKindTrojan,
        RelayKindAnyTls,
        RelayKindShadowsocks,
        RelayKindShadowTlsV3,
        -> true

        else -> false
    }

fun RelayProfileRecord.isSupportedChainExitHop(): Boolean = isSupportedChainNonEntryHop()

@Serializable
data class RelayCredentialRecord(
    val profileId: String,
    val vlessUuid: String? = null,
    val chainEntryUuid: String? = null,
    val chainExitUuid: String? = null,
    val hysteriaPassword: String? = null,
    val hysteriaSalamanderKey: String? = null,
    val hysteriaInsecure: Boolean = false,
    val tuicUuid: String? = null,
    val tuicPassword: String? = null,
    val anyTlsPassword: String? = null,
    val shadowTlsPassword: String? = null,
    val trojanPassword: String? = null,
    val mieruUsername: String? = null,
    val mieruPassword: String? = null,
    val sshUsername: String? = null,
    val sshPassword: String? = null,
    val sshPrivateKey: String? = null,
    val sshPrivateKeyPassphrase: String? = null,
    val shadowsocksMethod: String? = null,
    val shadowsocksPassword: String? = null,
    val naiveUsername: String? = null,
    val naivePassword: String? = null,
    val masqueAuthMode: String? = null,
    val masqueAuthToken: String? = null,
    val masqueClientCertificateChainPem: String? = null,
    val masqueClientPrivateKeyPem: String? = null,
    val cloudflareTunnelToken: String? = null,
    val cloudflareTunnelCredentialsJson: String? = null,
    val appsScriptAuthKey: String? = null,
    @EncodeDefault
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

/**
 * Result of [migrateRelayProfileRecord]: the (possibly rewritten) record plus a
 * flag indicating whether a migration was actually applied. [changed] is `true`
 * only when the on-disk shape was rewritten, so callers can emit exactly one
 * audit entry per rewritten record.
 */
data class RelayProfileMigrationResult(
    val record: RelayProfileRecord,
    val changed: Boolean,
)

/**
 * One-shot, deterministic, idempotent migration for the relay security-layer /
 * transport decoupling.
 *
 * A legacy record stored as `kind=vless_reality, vlessTransport=xhttp` with an
 * empty [RelayProfileRecord.realityPublicKey] is the deployer's plain-TLS xHTTP
 * shape that the old model could not express. It is rewritten to the new shape:
 * `kind=vless, securityLayer=tls`. Every other record (real Reality profiles,
 * non-VLESS kinds, already-migrated records) is returned unchanged.
 */
fun migrateRelayProfileRecord(record: RelayProfileRecord): RelayProfileMigrationResult {
    val isLegacyPlainTlsXhttp =
        record.kind == RelayKindVlessReality &&
            record.vlessTransport == RelayVlessTransportXhttp &&
            record.realityPublicKey.isEmpty()
    if (!isLegacyPlainTlsXhttp) {
        return RelayProfileMigrationResult(record = record, changed = false)
    }
    return RelayProfileMigrationResult(
        record =
            record.copy(
                kind = RelayKindVless,
                securityLayer = RelaySecurityLayerTls,
            ),
        changed = true,
    )
}

interface RelayProfileStore {
    suspend fun load(profileId: String): RelayProfileRecord?

    suspend fun list(): List<RelayProfileRecord>

    suspend fun save(profile: RelayProfileRecord)

    suspend fun clear(profileId: String)

    suspend fun clearAll() {
        error("Bulk clear is not implemented")
    }
}

interface RelayCredentialRepository {
    suspend fun load(profileId: String): RelayCredentialRecord?

    suspend fun save(credentials: RelayCredentialRecord)

    suspend fun clear(profileId: String)
}

interface RelayCredentialStore : RelayCredentialRepository {
    suspend fun clearAll() {
        error("Bulk clear is not implemented")
    }
}

@Singleton
class SharedPreferencesRelayProfileStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : RelayProfileStore {
        private val preferences = context.getSharedPreferences(ProfilePrefsName, Context.MODE_PRIVATE)
        private val json = RipDpiJson

        override suspend fun load(profileId: String): RelayProfileRecord? =
            withContext(Dispatchers.IO) {
                val stored =
                    preferences.getString(prefKey(profileId), null)?.let {
                        json.decodeFromString(RelayProfileRecord.serializer(), it)
                    } ?: return@withContext null
                val migration = migrateRelayProfileRecord(stored)
                if (migration.changed) {
                    // One-shot migration: persist the rewritten shape so it only
                    // runs once per legacy record.
                    persistBlocking(migration.record)
                }
                migration.record
            }

        override suspend fun list(): List<RelayProfileRecord> =
            withContext(Dispatchers.IO) {
                val migrations =
                    preferences.all.keys
                        .asSequence()
                        .filter { it.startsWith(ProfilePrefKeyPrefix) }
                        .mapNotNull { key ->
                            preferences.getString(key, null)?.let { encoded ->
                                json.decodeFromString(RelayProfileRecord.serializer(), encoded)
                            }
                        }.map(::migrateRelayProfileRecord)
                        .toList()
                migrations.filter(RelayProfileMigrationResult::changed).forEach { persistBlocking(it.record) }
                migrations.map(RelayProfileMigrationResult::record).sortedBy(RelayProfileRecord::id)
            }

        override suspend fun save(profile: RelayProfileRecord) {
            withContext(Dispatchers.IO) { persistBlocking(profile) }
        }

        override suspend fun clear(profileId: String) {
            withContext(Dispatchers.IO) { preferences.edit().remove(prefKey(profileId)).commitOrThrow() }
        }

        override suspend fun clearAll() {
            withContext(Dispatchers.IO) { preferences.edit().clear().commitOrThrow() }
        }

        private fun persistBlocking(profile: RelayProfileRecord) {
            preferences
                .edit()
                .putString(
                    prefKey(profile.id),
                    json.encodeToString(RelayProfileRecord.serializer(), profile),
                ).commitOrThrow()
        }

        private fun prefKey(profileId: String): String = "$ProfilePrefKeyPrefix$profileId"

        private companion object {
            const val ProfilePrefsName = "relay_profile_cache"
            const val ProfilePrefKeyPrefix = "relay-profile:"
        }
    }

@Singleton
class KeystoreRelayCredentialStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : RelayCredentialStore {
        private val json = RipDpiJson
        private val blobStore =
            KeystoreEncryptedPreferences(
                preferences = context.getSharedPreferences(CredentialsPrefsName, Context.MODE_PRIVATE),
                keyAlias = CredentialsKeyAlias,
            )

        override suspend fun load(profileId: String): RelayCredentialRecord? =
            blobStore
                .getString(prefKey(profileId))
                ?.let { json.decodeFromString(RelayCredentialRecord.serializer(), it) }

        override suspend fun save(credentials: RelayCredentialRecord) {
            withContext(Dispatchers.IO) {
                blobStore.putString(
                    prefKey(credentials.profileId),
                    json.encodeToString(RelayCredentialRecord.serializer(), credentials),
                )
            }
        }

        override suspend fun clear(profileId: String) {
            withContext(Dispatchers.IO) { blobStore.remove(prefKey(profileId)) }
        }

        override suspend fun clearAll() {
            withContext(Dispatchers.IO) { blobStore.clear() }
        }

        private fun prefKey(profileId: String): String = "$CredentialsEntryPrefix$profileId"

        private companion object {
            const val CredentialsPrefsName = "relay_credentials_secure"
            const val CredentialsEntryPrefix = "relay-credentials:"
            const val CredentialsKeyAlias = "ripdpi_relay_credentials"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class RelayStoreModule {
    @Binds
    @Singleton
    abstract fun bindRelayProfileStore(store: SharedPreferencesRelayProfileStore): RelayProfileStore

    @Binds
    @Singleton
    abstract fun bindRelayCredentialStore(store: KeystoreRelayCredentialStore): RelayCredentialStore

    @Binds
    @Singleton
    abstract fun bindRelayCredentialRepository(store: KeystoreRelayCredentialStore): RelayCredentialRepository
}
