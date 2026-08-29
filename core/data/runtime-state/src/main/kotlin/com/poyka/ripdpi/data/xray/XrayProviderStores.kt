package com.poyka.ripdpi.data.xray

import android.content.Context
import com.poyka.ripdpi.data.KeystoreEncryptedPreferences
import com.poyka.ripdpi.data.commitOrThrow
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, split-storage persistence for a validated [XrayProfile].
 *
 * ### Why two stores (privacy invariant)
 * `.claude/rules/network-fingerprint-privacy.md` forbids any secret reaching an
 * at-rest artifact in plaintext. We therefore MIRROR the relay credential split
 * (`RelayProfileRecord` + `RelayCredentialRecord` in `RelayStores.kt`):
 *
 * - **Secrets** ([XrayProfileSecretRecord]) — VLESS UUID, REALITY public/private
 *   key — go through the Android-Keystore-backed [KeystoreEncryptedPreferences]
 *   (the SAME helper `KeystoreRelayCredentialStore` / `KeystoreWarpCredentialStore`
 *   reuse). Never readable as plaintext on disk.
 * - **Metadata** ([XrayProfileMetadataRecord]) — name, protocol, security,
 *   network, flow, and the local-inbound / DNS knobs — go to a plain
 *   `SharedPreferences`. The server `host:port` is an ENDPOINT, not a secret at
 *   rest, but it MUST be redacted in any telemetry/log/snapshot via
 *   [XrayProfileRedactor]; it is persisted here only so the renderer has an
 *   input and never surfaces it to a diagnostics path.
 *
 * The two halves are re-joined by [DurableXrayProfileStore] into a full
 * [XrayProfile] for [XrayConfigRenderer]; the secret half is loaded only at
 * render time and never retained on a telemetry/snapshot object.
 */
@Serializable
data class XrayProfileMetadataRecord(
    val profileId: String = DefaultXrayProfileId,
    /** Matches the encrypted half; an empty revision is not a committed profile. */
    val revision: String = "",
    val name: String = "",
    /** Server endpoint host. Endpoint — never logged; redact in telemetry. */
    val serverAddress: String = "",
    val serverPort: Int = 443,
    val flow: String = "xtls-rprx-vision",
    /** `reality` | `tls`. */
    val security: String = SecurityReality,
    /** `tcp` | `xhttp`. */
    val network: String = NetworkTcp,
    // REALITY non-secret params (publicKey is a secret -> secret record).
    val realityServerName: String = "",
    val realityShortId: String = "",
    val realityFingerprint: String = "chrome",
    val hasReality: Boolean = false,
    // TLS params (none secret).
    val tlsServerName: String = "",
    val tlsFingerprint: String = "chrome",
    val tlsAllowInsecure: Boolean = false,
    val hasTls: Boolean = false,
    // XHTTP params (none secret).
    val xhttpPath: String = "/",
    val xhttpMode: String = "auto",
    val xhttpHost: String = "",
    val hasXhttp: Boolean = false,
    // Local inbound.
    val inboundListen: String = "127.0.0.1",
    val inboundPort: Int = XrayProfile.DEFAULT_LOCAL_INBOUND_PORT,
    val inboundUdpEnabled: Boolean = true,
    // DNS.
    val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val dnsQueryStrategy: String = "UseIP",
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SecurityReality = "reality"
        const val SecurityTls = "tls"
        const val NetworkTcp = "tcp"
        const val NetworkXhttp = "xhttp"
    }
}

/**
 * Keystore-encrypted secret half of a persisted [XrayProfile]. ONLY secrets:
 * the VLESS UUID and the REALITY public/private keys. Never written in plaintext;
 * never embedded in metadata, telemetry, snapshots, or logs.
 */
@Serializable
data class XrayProfileSecretRecord(
    val profileId: String,
    val revision: String = "",
    /** VLESS user UUID. Secret. */
    val uuid: String = "",
    /** REALITY x25519 public key (client side). Secret. */
    val realityPublicKey: String? = null,
    /** REALITY x25519 private key (server side only). Secret. */
    val realityPrivateKey: String? = null,
)

@Serializable
data class XrayProfileRecordPair(
    val metadata: XrayProfileMetadataRecord,
    val secret: XrayProfileSecretRecord,
)

fun XrayProfile.toXrayProfileRecordPair(
    profileId: String,
    revision: String = UUID.randomUUID().toString(),
): XrayProfileRecordPair {
    val outbound = outbound
    return XrayProfileRecordPair(
        metadata =
            XrayProfileMetadataRecord(
                profileId = profileId,
                revision = revision,
                name = name,
                serverAddress = outbound.serverAddress,
                serverPort = outbound.serverPort,
                flow = outbound.flow,
                security = outbound.security.wire(),
                network = outbound.network.wire(),
                realityServerName = outbound.reality?.serverName.orEmpty(),
                realityShortId = outbound.reality?.shortId.orEmpty(),
                realityFingerprint = outbound.reality?.fingerprint ?: "chrome",
                hasReality = outbound.reality != null,
                tlsServerName = outbound.tls?.serverName.orEmpty(),
                tlsFingerprint = outbound.tls?.fingerprint ?: "chrome",
                tlsAllowInsecure = outbound.tls?.allowInsecure ?: false,
                hasTls = outbound.tls != null,
                xhttpPath = outbound.xhttp?.path ?: "/",
                xhttpMode = outbound.xhttp?.mode ?: "auto",
                xhttpHost = outbound.xhttp?.host.orEmpty(),
                hasXhttp = outbound.xhttp != null,
                inboundListen = inbound.listen,
                inboundPort = inbound.port,
                inboundUdpEnabled = inbound.udpEnabled,
                dnsServers = dns.servers,
                dnsQueryStrategy = dns.queryStrategy,
            ),
        secret =
            XrayProfileSecretRecord(
                profileId = profileId,
                revision = revision,
                uuid = outbound.uuid,
                realityPublicKey = outbound.reality?.publicKey,
                realityPrivateKey = outbound.reality?.privateKey,
            ),
    )
}

private fun XrayProfile.Security.wire(): String =
    when (this) {
        XrayProfile.Security.REALITY -> XrayProfileMetadataRecord.SecurityReality
        XrayProfile.Security.TLS -> XrayProfileMetadataRecord.SecurityTls
    }

private fun XrayProfile.Network.wire(): String =
    when (this) {
        XrayProfile.Network.TCP -> XrayProfileMetadataRecord.NetworkTcp
        XrayProfile.Network.XHTTP -> XrayProfileMetadataRecord.NetworkXhttp
    }

/** Plaintext metadata persistence for an Xray profile. NO secrets. */
interface XrayProfileMetadataStore {
    suspend fun load(profileId: String): XrayProfileMetadataRecord?

    suspend fun list(): List<XrayProfileMetadataRecord>

    suspend fun save(record: XrayProfileMetadataRecord)

    suspend fun clear(profileId: String)

    suspend fun clearAll() {
        error("Bulk clear is not implemented")
    }
}

/** Keystore-backed secret persistence for an Xray profile. */
interface XrayProfileSecretStore {
    suspend fun load(profileId: String): XrayProfileSecretRecord?

    suspend fun save(record: XrayProfileSecretRecord)

    suspend fun clear(profileId: String)

    suspend fun clearAll() {
        error("Bulk clear is not implemented")
    }
}

@Singleton
class SharedPreferencesXrayProfileMetadataStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : XrayProfileMetadataStore {
        private val preferences = context.getSharedPreferences(MetadataPrefsName, Context.MODE_PRIVATE)
        private val json = RipDpiJson

        override suspend fun load(profileId: String): XrayProfileMetadataRecord? =
            preferences.getString(prefKey(profileId), null)?.let {
                runCatching { json.decodeFromString(XrayProfileMetadataRecord.serializer(), it) }
                    .getOrNull()
                    ?.takeIf { record -> record.profileId == profileId }
            }

        override suspend fun list(): List<XrayProfileMetadataRecord> =
            preferences.all.keys
                .asSequence()
                .filter { it.startsWith(MetadataKeyPrefix) }
                .mapNotNull { key ->
                    preferences.getString(key, null)?.let { encoded ->
                        runCatching {
                            json.decodeFromString(XrayProfileMetadataRecord.serializer(), encoded)
                        }.getOrNull()?.takeIf { record -> prefKey(record.profileId) == key }
                    }
                }.sortedBy { it.profileId }
                .toList()

        override suspend fun save(record: XrayProfileMetadataRecord) {
            preferences
                .edit()
                .putString(
                    prefKey(record.profileId),
                    json.encodeToString(XrayProfileMetadataRecord.serializer(), record),
                ).commitOrThrow()
        }

        override suspend fun clear(profileId: String) {
            preferences.edit().remove(prefKey(profileId)).commitOrThrow()
        }

        override suspend fun clearAll() {
            preferences.edit().clear().commitOrThrow()
        }

        private fun prefKey(profileId: String): String = "$MetadataKeyPrefix$profileId"

        private companion object {
            const val MetadataPrefsName = "xray_profile_metadata"
            const val MetadataKeyPrefix = "xray-profile:"
        }
    }

@Singleton
class KeystoreXrayProfileSecretStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : XrayProfileSecretStore {
        private val json = RipDpiJson
        private val blobStore =
            KeystoreEncryptedPreferences(
                preferences = context.getSharedPreferences(SecretsPrefsName, Context.MODE_PRIVATE),
                keyAlias = SecretsKeyAlias,
            )

        override suspend fun load(profileId: String): XrayProfileSecretRecord? =
            blobStore
                .getString(prefKey(profileId))
                ?.let { json.decodeFromString(XrayProfileSecretRecord.serializer(), it) }

        override suspend fun save(record: XrayProfileSecretRecord) {
            blobStore.putString(
                prefKey(record.profileId),
                json.encodeToString(XrayProfileSecretRecord.serializer(), record),
            )
        }

        override suspend fun clear(profileId: String) {
            blobStore.remove(prefKey(profileId))
        }

        override suspend fun clearAll() {
            blobStore.clear()
        }

        private fun prefKey(profileId: String): String = "$SecretsKeyPrefix$profileId"

        private companion object {
            const val SecretsPrefsName = "xray_profile_secrets_secure"
            const val SecretsKeyPrefix = "xray-secrets:"
            const val SecretsKeyAlias = "ripdpi_xray_profile_secrets"
        }
    }

/**
 * Durable [XrayProfile] persistence that splits a profile into a plaintext
 * metadata half and a Keystore-encrypted secret half, then re-joins them on
 * load. This is the production input to [XrayConfigRenderer]; before this store
 * existed the import surface kept the validated profile in memory only.
 *
 * Each save writes a fresh revision to both halves. A process death between
 * writes leaves mismatched revisions, which [load] and [listProfileIds] reject.
 * This also covers replacement of an existing profile. Writes are durably
 * committed; a mutex prevents concurrent readers from observing an in-flight save.
 * Records predating revision binding must be re-imported before use.
 */
interface DurableXrayProfileStore {
    suspend fun load(profileId: String): XrayProfile?

    suspend fun save(
        profileId: String,
        profile: XrayProfile,
    )

    suspend fun clear(profileId: String)

    /** Profile ids that have BOTH halves persisted. */
    suspend fun listProfileIds(): List<String>
}

@Singleton
class DefaultDurableXrayProfileStore
    @Inject
    constructor(
        private val metadataStore: XrayProfileMetadataStore,
        private val secretStore: XrayProfileSecretStore,
    ) : DurableXrayProfileStore {
        private val mutex = Mutex()

        override suspend fun load(profileId: String): XrayProfile? =
            mutex.withLock {
                val metadata = metadataStore.load(profileId)
                val secret = if (metadata != null) secretStore.load(profileId) else null
                if (metadata?.profileId == profileId &&
                    matches(metadata, secret)
                ) {
                    reconstitute(requireNotNull(metadata), requireNotNull(secret))
                } else {
                    null
                }
            }

        override suspend fun save(
            profileId: String,
            profile: XrayProfile,
        ) = mutex.withLock {
            val records = profile.toXrayProfileRecordPair(profileId)
            secretStore.save(records.secret)
            metadataStore.save(records.metadata)
        }

        override suspend fun clear(profileId: String) =
            mutex.withLock {
                metadataStore.clear(profileId)
                secretStore.clear(profileId)
            }

        override suspend fun listProfileIds(): List<String> =
            mutex.withLock {
                metadataStore.list().mapNotNull { metadata ->
                    metadata.profileId.takeIf { matches(metadata, secretStore.load(it)) }
                }
            }

        private fun matches(
            metadata: XrayProfileMetadataRecord?,
            secret: XrayProfileSecretRecord?,
        ): Boolean =
            metadata != null && secret != null && metadata.profileId == secret.profileId &&
                metadata.revision.isNotBlank() && metadata.revision == secret.revision

        private fun reconstitute(
            metadata: XrayProfileMetadataRecord,
            secret: XrayProfileSecretRecord,
        ): XrayProfile? {
            val security =
                when (metadata.security) {
                    XrayProfileMetadataRecord.SecurityReality -> XrayProfile.Security.REALITY
                    XrayProfileMetadataRecord.SecurityTls -> XrayProfile.Security.TLS
                    else -> null
                }
            val network =
                when (metadata.network) {
                    XrayProfileMetadataRecord.NetworkTcp -> XrayProfile.Network.TCP
                    XrayProfileMetadataRecord.NetworkXhttp -> XrayProfile.Network.XHTTP
                    else -> null
                }
            return if (security == null || network == null) {
                null
            } else {
                XrayProfile(
                    name = metadata.name,
                    outbound =
                        XrayProfile.Outbound(
                            serverAddress = metadata.serverAddress,
                            serverPort = metadata.serverPort,
                            uuid = secret.uuid,
                            flow = metadata.flow,
                            security = security,
                            network = network,
                            reality =
                                if (metadata.hasReality) {
                                    XrayProfile.Reality(
                                        publicKey = secret.realityPublicKey.orEmpty(),
                                        serverName = metadata.realityServerName,
                                        shortId = metadata.realityShortId,
                                        fingerprint = metadata.realityFingerprint,
                                        privateKey = secret.realityPrivateKey,
                                    )
                                } else {
                                    null
                                },
                            tls =
                                if (metadata.hasTls) {
                                    XrayProfile.Tls(
                                        serverName = metadata.tlsServerName,
                                        fingerprint = metadata.tlsFingerprint,
                                        allowInsecure = metadata.tlsAllowInsecure,
                                    )
                                } else {
                                    null
                                },
                            xhttp =
                                if (metadata.hasXhttp) {
                                    XrayProfile.Xhttp(
                                        path = metadata.xhttpPath,
                                        mode = metadata.xhttpMode,
                                        host = metadata.xhttpHost,
                                    )
                                } else {
                                    null
                                },
                        ),
                    inbound =
                        XrayProfile.LocalInbound(
                            listen = metadata.inboundListen,
                            port = metadata.inboundPort,
                            udpEnabled = metadata.inboundUdpEnabled,
                        ),
                    dns =
                        XrayProfile.DnsSettings(
                            servers = metadata.dnsServers,
                            queryStrategy = metadata.dnsQueryStrategy,
                        ),
                )
            }
        }
    }

/**
 * Cross-module provider-selection signal: which [VpnProviderKind] is active and,
 * for the Xray provider, which durable profile id backs it. This is the small,
 * secret-free signal `:core:service` reads to decide whether VPN startup should
 * branch onto the Xray orchestrator. It carries NO profile contents — only the
 * provider kind and a profile id pointer into [DurableXrayProfileStore].
 */
@Serializable
data class XrayProviderSelectionRecord(
    val providerKind: String = ProviderKindNative,
    /** Active durable Xray profile id when [providerKind] is xray; else blank. */
    val activeProfileId: String = "",
) {
    val kind: VpnProviderKind
        get() =
            when (providerKind) {
                ProviderKindNative -> VpnProviderKind.Native
                ProviderKindXray -> VpnProviderKind.Xray
                else -> error("Unknown stored VPN provider")
            }

    companion object {
        const val ProviderKindNative = "native"
        const val ProviderKindXray = "xray"

        fun of(
            kind: VpnProviderKind,
            activeProfileId: String?,
        ): XrayProviderSelectionRecord =
            XrayProviderSelectionRecord(
                providerKind =
                    when (kind) {
                        VpnProviderKind.Native -> ProviderKindNative
                        VpnProviderKind.Xray -> ProviderKindXray
                    },
                activeProfileId = activeProfileId.orEmpty(),
            )
    }
}

/**
 * Durable, cross-module read/write surface for the active provider selection.
 *
 * Persisted on every selection change (event-driven, not timer-driven) per
 * `.claude/rules/android-vpn-lifecycle.md` so it survives an LMK `SIGKILL`. The
 * `:app` selection UI writes it; `:core:service` reads [current] at VPN startup.
 */
interface XrayProviderSelectionStore {
    fun current(): XrayProviderSelectionRecord

    fun update(record: XrayProviderSelectionRecord)
}

@Singleton
class SharedPreferencesXrayProviderSelectionStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : XrayProviderSelectionStore {
        private val preferences = context.getSharedPreferences(SelectionPrefsName, Context.MODE_PRIVATE)
        private val json = RipDpiJson

        override fun current(): XrayProviderSelectionRecord =
            preferences.getString(SelectionKey, null)?.let {
                json.decodeFromString(XrayProviderSelectionRecord.serializer(), it).also { record -> record.kind }
            } ?: XrayProviderSelectionRecord()

        override fun update(record: XrayProviderSelectionRecord) {
            record.kind
            preferences
                .edit()
                .putString(SelectionKey, json.encodeToString(XrayProviderSelectionRecord.serializer(), record))
                .commitOrThrow()
        }

        suspend fun clear() {
            preferences.edit().clear().commitOrThrow()
        }

        private companion object {
            const val SelectionPrefsName = "xray_provider_selection"
            const val SelectionKey = "selection"
        }
    }

/** Default durable Xray profile id when only one profile is supported. */
const val DefaultXrayProfileId: String = "default"

@Module
@InstallIn(SingletonComponent::class)
abstract class XrayProviderStoreModule {
    @Binds
    @Singleton
    abstract fun bindXrayProfileMetadataStore(
        store: SharedPreferencesXrayProfileMetadataStore,
    ): XrayProfileMetadataStore

    @Binds
    @Singleton
    abstract fun bindXrayProfileSecretStore(store: KeystoreXrayProfileSecretStore): XrayProfileSecretStore

    @Binds
    @Singleton
    abstract fun bindDurableXrayProfileStore(store: DefaultDurableXrayProfileStore): DurableXrayProfileStore

    @Binds
    @Singleton
    abstract fun bindXrayProviderSelectionStore(
        store: SharedPreferencesXrayProviderSelectionStore,
    ): XrayProviderSelectionStore
}
