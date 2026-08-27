package com.poyka.ripdpi.data.backup

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.data.rules.RuleTypeConverters
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Current schema version for the [BackupV1] document. */
const val BackupSchemaVersion: Int = 2

/**
 * Oldest schema version this app knows how to migrate forward. Versions below this
 * never shipped a real format, so they are rejected outright rather than migrated.
 * v1 is currently both the oldest and the current version.
 */
const val OldestSupportedBackupVersion: Int = 1

/**
 * Backup export variant.
 *
 * - [SHARE]: strips every field classified as [Classification.REDACTED] or
 *   [Classification.EXCLUDED] from each profile, strips subscription secrets
 *   (`token` + credentials in the `link`) from each group, and omits app settings;
 *   safe to share publicly.
 * - [FULL]: retains all fields and sets [BackupV1.containsCredentials] = true.
 */
enum class BackupVariant {
    SHARE,
    FULL,
}

/**
 * Per-field classification used by the backup allowlist.
 *
 * - [PUBLIC]: field is non-sensitive; exported in both [BackupVariant.SHARE] and [BackupVariant.FULL].
 * - [REDACTED]: field contains a credential or share-sensitive endpoint metadata; stripped in [BackupVariant.SHARE],
 *   kept in [BackupVariant.FULL].
 * - [EXCLUDED]: field must never appear in any export (e.g. internal IDs, runtime state).
 */
enum class Classification {
    PUBLIC,
    REDACTED,
    EXCLUDED,
}

/**
 * Top-level versioned backup document.
 *
 * [schemaVersion] must equal [BackupSchemaVersion] on deserialization.
 * [containsCredentials] is `true` only for [BackupVariant.FULL] exports.
 */
@Serializable
data class BackupV1(
    val schemaVersion: Int,
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val profiles: List<JsonObject>,
    val groups: List<ProxyGroup>,
    val rules: List<RuleExport>,
    val settings: Map<String, String>,
    val containsCredentials: Boolean = false,
    /** FULL-only snapshot of profile families stored outside [ProxyGroup]. Absent in v1 and SHARE backups. */
    val privateData: BackupPrivateDataV1? = null,
)

/** FULL-backup section for user profiles and credentials held outside the group repository. */
@Serializable
data class BackupPrivateDataV1(
    val relayProfiles: List<RelayProfileRecord> = emptyList(),
    val relayCredentials: List<RelayCredentialRecord> = emptyList(),
    val warpProfiles: List<WarpProfile> = emptyList(),
    val warpCredentials: List<WarpCredentials> = emptyList(),
    val warpActiveProfileId: String? = null,
    val awgProfiles: List<AwgBackupProfile> = emptyList(),
    val xrayMetadata: List<XrayProfileMetadataRecord> = emptyList(),
    val xraySecrets: List<XrayProfileSecretRecord> = emptyList(),
    val xraySelection: XrayProviderSelectionRecord = XrayProviderSelectionRecord(),
)

/** Room metadata and its optional Keystore secret half for one standalone AWG profile. */
@Serializable
data class AwgBackupProfile(
    val id: String,
    val name: String,
    val requestJson: String,
    val updatedAt: Long,
    val secrets: AwgSecrets? = null,
) {
    fun toEntity(): AwgProfileEntity =
        AwgProfileEntity(
            id = id,
            name = name,
            requestJson = requestJson,
            updatedAt = updatedAt,
        )
}

/**
 * Portable, Room-independent representation of a [RuleEntity] for backup.
 */
@Serializable
data class RuleExport(
    val name: String,
    val userOrder: Int,
    val enabled: Boolean,
    val domains: String,
    val ipCidrs: String,
    val ports: String,
    val sourcePorts: String,
    val network: String,
    val processName: String,
    val packages: Set<String>,
    val outboundTag: String,
)

/**
 * Typed error returned when a backup document carries an unsupported [schemaVersion].
 */
class UnsupportedBackupVersion(
    val found: Int,
    val supported: Int,
) : Exception("Unsupported backup version $found (supported: $supported)")

// ---------------------------------------------------------------------------
// Allowlist
// ---------------------------------------------------------------------------

/**
 * Per-protocol field classification map.
 *
 * Denial-by-default: every property of every [ProxyProfile] subtype MUST appear
 * here. Adding a new property without a classification causes
 * [BackupAllowlist.classificationFor] to throw [IllegalStateException], which
 * surfaces in the coverage test before any export is attempted.
 */
object BackupAllowlist {
    /**
     * Shared fields present on every [ProxyProfile] subtype.
     */
    private val commonFields: Map<String, Classification> =
        mapOf(
            "type" to Classification.PUBLIC,
            "id" to Classification.EXCLUDED,
            "displayName" to Classification.PUBLIC,
            "groupId" to Classification.PUBLIC,
        )

    private val vlessFields: Map<String, Classification> =
        commonFields +
            mapOf(
                "server" to Classification.REDACTED,
                "serverPort" to Classification.REDACTED,
                "uuid" to Classification.REDACTED,
                "serverName" to Classification.REDACTED,
                "flow" to Classification.PUBLIC,
                "fingerprint" to Classification.PUBLIC,
                "xhttpPath" to Classification.REDACTED,
                "xhttpHost" to Classification.REDACTED,
                "xhttpMode" to Classification.PUBLIC,
            )

    private val shadowsocksFields: Map<String, Classification> =
        commonFields +
            mapOf(
                "server" to Classification.PUBLIC,
                "serverPort" to Classification.PUBLIC,
                "method" to Classification.PUBLIC,
                "password" to Classification.REDACTED,
            )

    private val trojanFields: Map<String, Classification> =
        commonFields +
            mapOf(
                "server" to Classification.PUBLIC,
                "serverPort" to Classification.PUBLIC,
                // TLS SNI / masquerade domain; same sensitivity class as the connect
                // host (PUBLIC), mirroring AnyTls.serverName.
                "serverName" to Classification.PUBLIC,
                "password" to Classification.REDACTED,
            )

    private val hysteria2Fields: Map<String, Classification> =
        commonFields +
            mapOf(
                "server" to Classification.PUBLIC,
                "serverPort" to Classification.PUBLIC,
                "serverName" to Classification.PUBLIC,
                "password" to Classification.REDACTED,
                "obfsPassword" to Classification.REDACTED,
                "insecure" to Classification.PUBLIC,
                "portHopPorts" to Classification.PUBLIC,
                "portHopInterval" to Classification.PUBLIC,
                // Non-secret upstream Hysteria2 release tag (e.g. "v2.9.0"); same
                // sensitivity class as the other RIPDPI-extras transport metadata.
                "salamanderUpstreamTag" to Classification.PUBLIC,
            )

    private val vlessRealityFields: Map<String, Classification> =
        commonFields +
            mapOf(
                "server" to Classification.REDACTED,
                "serverPort" to Classification.REDACTED,
                "uuid" to Classification.REDACTED,
                // REALITY key material and carrier route identify the relay and must not survive SHARE.
                "realityPublicKey" to Classification.REDACTED,
                "realityShortId" to Classification.REDACTED,
                "serverName" to Classification.REDACTED,
                "flow" to Classification.PUBLIC,
                "fingerprint" to Classification.PUBLIC,
                "xhttpPath" to Classification.REDACTED,
                "xhttpHost" to Classification.REDACTED,
                "xhttpMode" to Classification.PUBLIC,
            )

    private val anyTlsFields: Map<String, Classification> =
        commonFields +
            mapOf(
                "server" to Classification.PUBLIC,
                "serverPort" to Classification.PUBLIC,
                "serverName" to Classification.PUBLIC,
                "password" to Classification.REDACTED,
            )

    private val mieruFields: Map<String, Classification> =
        commonFields +
            mapOf(
                "server" to Classification.PUBLIC,
                "serverPort" to Classification.PUBLIC,
                // Mieru authenticates with both a username and a password — both
                // are credentials and must be redacted from a backup export.
                "username" to Classification.REDACTED,
                "password" to Classification.REDACTED,
                "protocol" to Classification.PUBLIC,
                "multiplexing" to Classification.PUBLIC,
                "mtu" to Classification.PUBLIC,
            )

    private val sshFields: Map<String, Classification> =
        commonFields +
            mapOf(
                "server" to Classification.PUBLIC,
                "serverPort" to Classification.PUBLIC,
                "username" to Classification.PUBLIC,
                "authType" to Classification.PUBLIC,
                // SSH credential material — must never survive a SHARE export.
                "password" to Classification.REDACTED,
                "privateKey" to Classification.REDACTED,
                "privateKeyPassphrase" to Classification.REDACTED,
                // Host-key pinning metadata is non-secret transport configuration.
                "hostKeyFingerprint" to Classification.PUBLIC,
                "strictHostKey" to Classification.PUBLIC,
            )

    private val rawConfigFields: Map<String, Classification> =
        commonFields +
            mapOf(
                "config" to Classification.REDACTED,
            )

    private val registry: Map<String, Map<String, Classification>> =
        mapOf(
            "vless" to vlessFields,
            "vless-reality" to vlessRealityFields,
            "shadowsocks" to shadowsocksFields,
            "trojan" to trojanFields,
            "hysteria2" to hysteria2Fields,
            "anytls" to anyTlsFields,
            "mieru" to mieruFields,
            "ssh" to sshFields,
            "raw-config" to rawConfigFields,
        )

    /**
     * Returns the [Classification] for [fieldName] within [protocolKey].
     *
     * [protocolKey] is the `@SerialName` discriminator of the [ProxyProfile] subtype
     * (e.g. `"vless"`, `"shadowsocks"`).
     *
     * Throws [IllegalStateException] (denial-by-default) if the field is not
     * classified — this surfaces at export time and in the coverage test.
     */
    fun classificationFor(
        protocolKey: String,
        fieldName: String,
    ): Classification {
        val fields =
            registry[protocolKey]
                ?: error("BackupAllowlist: unknown protocol '$protocolKey' — add it to the registry")
        return fields[fieldName]
            ?: error(
                "BackupAllowlist: field '$fieldName' of protocol '$protocolKey' is not classified. " +
                    "Add it as PUBLIC, REDACTED, or EXCLUDED before exporting.",
            )
    }

    /** Returns all (protocolKey, fieldName) entries in the registry. */
    fun allEntries(): Map<String, Map<String, Classification>> = registry
}

// ---------------------------------------------------------------------------
// Group redaction (SHARE)
// ---------------------------------------------------------------------------

/**
 * Redacts subscription secrets from a [ProxyGroup] before it enters a
 * publicly-shareable backup ([BackupVariant.SHARE]).
 *
 * A [ProxyGroup] can embed a [Subscription] whose [Subscription.token] is a bearer
 * credential and whose [Subscription.link] URL may carry `user:pass@` userinfo or a
 * `token=`/`access_token=` query parameter. The profile allowlist already classifies
 * such material as [Classification.REDACTED]; this is the equivalent for groups so the
 * two SHARE paths stay consistent — secrets never survive, non-secret metadata does.
 */
object BackupGroupRedactor {
    /** Query-parameter names whose values are bearer credentials and must be dropped. */
    private val SECRET_QUERY_KEYS: Set<String> =
        setOf("token", "access_token", "accesstoken", "auth", "key", "apikey", "api_key")

    /** Returns [group] with subscription secrets, members and package names stripped (SHARE-safe). */
    fun redact(group: ProxyGroup): ProxyGroup {
        // SHARE backups must not carry the member node list. A selector/subscription
        // group's [ProxyGroup.members] can embed per-node credentials (e.g. AnyTLS/SSH
        // passwords) and is re-fetchable from the (scrubbed) subscription link on the
        // recipient device, so it is dropped rather than risk leaking a secret into a
        // publicly-shared backup. FULL exports keep members verbatim (they bypass this
        // redactor in [BackupExporter.export]).
        val base =
            if (group.members.isEmpty() && group.packageRoutingRules.isEmpty()) {
                group
            } else {
                group.copy(members = emptyList(), packageRoutingRules = emptyList())
            }
        val sub = base.subscription ?: return base
        return base.copy(
            subscription =
                sub.copy(
                    token = "",
                    link = scrubLink(sub.link),
                    mirrors = SubscriptionMirrorSet(),
                ),
        )
    }

    /**
     * Strips credential material from a subscription URL: drops any `user:pass@`
     * userinfo component and any secret query parameter (see [SECRET_QUERY_KEYS]).
     * A value that does not parse as a URI is treated as opaque and emptied rather
     * than risk leaking an embedded secret.
     */
    internal fun scrubLink(link: String): String {
        if (link.isEmpty()) return link
        // A value that does not parse (or re-encode) as a URI is emptied via the
        // single getOrDefault below rather than a separate early return.
        return runCatching {
            val uri = java.net.URI(link)
            java.net
                .URI(
                    uri.scheme,
                    // userInfo is always dropped: `user:pass@` carries credentials.
                    null,
                    uri.host,
                    uri.port,
                    uri.path,
                    scrubQuery(uri.rawQuery),
                    uri.fragment,
                ).toString()
        }.getOrDefault("")
    }

    /** Removes secret query parameters; returns `null` when nothing remains. */
    private fun scrubQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrEmpty()) return rawQuery
        val kept =
            rawQuery
                .split("&")
                .filter { pair ->
                    val name = pair.substringBefore("=").lowercase()
                    name !in SECRET_QUERY_KEYS
                }
        return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
    }
}

// ---------------------------------------------------------------------------
// Exporter
// ---------------------------------------------------------------------------

private val backupJson =
    RipDpiEncodeDefaultsJson

/**
 * Produces a [BackupV1] document from the supplied data.
 *
 * For [BackupVariant.SHARE], each profile's JSON object is stripped of every
 * field classified as [Classification.REDACTED] or [Classification.EXCLUDED], each
 * group's subscription secrets are stripped via [BackupGroupRedactor], and callers
 * must pass a SHARE-safe settings map.
 * For [BackupVariant.FULL], all fields are kept and [BackupV1.containsCredentials]
 * is set to `true`.
 */
object BackupExporter {
    fun export(
        variant: BackupVariant,
        profiles: List<ProxyProfile>,
        groups: List<ProxyGroup>,
        rules: List<RuleEntity>,
        settings: Map<String, String>,
        createdAtEpochMillis: Long,
        appVersion: String,
        privateData: BackupPrivateDataV1? = null,
    ): BackupV1 {
        val profileObjects =
            profiles.map { profile ->
                val raw = backupJson.encodeToJsonElement(ProxyProfile.serializer(), profile).jsonObject
                val protocolKey =
                    raw["type"]?.jsonPrimitive?.content
                        ?: error("ProxyProfile JSON is missing 'type' discriminator")
                when (variant) {
                    BackupVariant.FULL -> {
                        raw
                    }

                    BackupVariant.SHARE -> {
                        JsonObject(
                            raw.entries
                                .filter { (key, _) ->
                                    val cls = BackupAllowlist.classificationFor(protocolKey, key)
                                    cls == Classification.PUBLIC
                                }.associate { (key, value) -> key to value },
                        )
                    }
                }
            }
        val ruleExports =
            rules.map { r ->
                RuleExport(
                    name = r.name,
                    userOrder = r.userOrder,
                    enabled = r.enabled,
                    domains = r.domains,
                    ipCidrs = r.ipCidrs,
                    ports = r.ports,
                    sourcePorts = r.sourcePorts,
                    network = r.network.name,
                    processName = r.processName,
                    packages = r.packages,
                    // Durable, explicit encoding shared with Room (the "sentinel:kind"
                    // form), NOT the fragile Kotlin data-class toString().
                    outboundTag = RuleTypeConverters.fromOutboundTag(r.outboundTag),
                )
            }
        val groupExports =
            when (variant) {
                // FULL is the encrypted/local export: keep subscription secrets verbatim.
                BackupVariant.FULL -> groups

                // SHARE is documented "safe to share publicly": strip subscription
                // token + credential-bearing link, mirroring the profile allowlist.
                BackupVariant.SHARE -> groups.map { BackupGroupRedactor.redact(it) }
            }
        return BackupV1(
            schemaVersion = BackupSchemaVersion,
            createdAtEpochMillis = createdAtEpochMillis,
            appVersion = appVersion,
            profiles = profileObjects,
            groups = groupExports,
            rules = ruleExports,
            settings = settings,
            containsCredentials = variant == BackupVariant.FULL,
            privateData = privateData.takeIf { variant == BackupVariant.FULL },
        )
    }
}

// ---------------------------------------------------------------------------
// Importer
// ---------------------------------------------------------------------------

/**
 * Deserializes a backup JSON string into a [BackupV1] document.
 *
 * Throws [UnsupportedBackupVersion] when [BackupV1.schemaVersion] is not
 * [BackupSchemaVersion].
 */
object BackupImporter {
    fun import(json: String): BackupV1 {
        val raw = backupJson.parseToJsonElement(json).jsonObject
        val versionElement =
            raw["schemaVersion"]
                ?: error("Backup JSON is missing 'schemaVersion'")
        val version = versionElement.jsonPrimitive.content.toInt()
        // Strict newer-than-app gating: never partially overwrite live data.
        if (version >
            BackupSchemaVersion
        ) {
            throw UnsupportedBackupVersion(found = version, supported = BackupSchemaVersion)
        }
        // Below the oldest real format there is nothing to migrate from.
        if (version <
            OldestSupportedBackupVersion
        ) {
            throw UnsupportedBackupVersion(found = version, supported = BackupSchemaVersion)
        }
        // A genuine older-but-known version is migrated forward before decoding.
        // v1 is currently both oldest and current, so this is the identity
        // transform; add migrateVNToVN+1 steps here as new versions land.
        return backupJson.decodeFromJsonElement(raw)
    }
}

// ---------------------------------------------------------------------------
// Restore decoding (reverse of the exporter)
// ---------------------------------------------------------------------------

/**
 * Outcome of decoding a single profile [JsonObject] from a backup document.
 *
 * SHARE backups strip every [Classification.REDACTED] field, so a profile whose
 * protocol requires a credential (e.g. a `password`/`uuid`) can no longer be
 * reconstructed into a complete [ProxyProfile]. Rather than silently dropping it,
 * the decoder reports a typed [Failed] result carrying the human-readable
 * [displayName] so the preview can list the profiles it cannot restore.
 */
sealed interface ProfileDecodeResult {
    /** The profile decoded into a complete [ProxyProfile]. */
    data class Decoded(
        val profile: ProxyProfile,
    ) : ProfileDecodeResult

    /**
     * The profile could not be decoded (typically a SHARE backup missing a
     * redacted credential, or an unknown protocol discriminator).
     */
    data class Failed(
        val displayName: String,
        val reason: String,
    ) : ProfileDecodeResult
}

/**
 * Decodes the [BackupV1.profiles] JSON objects into [ProfileDecodeResult]s.
 *
 * Each object is decoded independently via [ProxyProfile.serializer]; a failure on
 * one profile (e.g. a missing redacted field in a SHARE backup) never aborts the
 * others — the caller decides whether any failure blocks the restore.
 */
object BackupRestoreDecoder {
    fun decodeProfiles(document: BackupV1): List<ProfileDecodeResult> =
        document.profiles.map { obj ->
            val displayName =
                obj["displayName"]?.jsonPrimitive?.content
                    ?: obj["id"]?.jsonPrimitive?.content
                    ?: "(unnamed)"
            runCatching {
                backupJson.decodeFromJsonElement(ProxyProfile.serializer(), obj)
            }.fold(
                onSuccess = { ProfileDecodeResult.Decoded(it) },
                onFailure = { e ->
                    ProfileDecodeResult.Failed(
                        displayName = displayName,
                        // Only the exception class name — never `e.message`, which (for
                        // kotlinx.serialization) can quote the offending JSON payload.
                        reason = e::class.simpleName.orEmpty(),
                    )
                },
            )
        }

    /**
     * Maps a [RuleExport] back into a [RuleEntity]. The inverse of
     * [BackupExporter]'s `RuleEntity -> RuleExport` projection:
     * - [RuleExport.network] is the [RuleNetwork] enum name; unknown values fall
     *   back to [RuleNetwork.BOTH] rather than throwing.
     * - [RuleExport.outboundTag] is the durable `"sentinel:kind"` encoding produced by
     *   [RuleTypeConverters.fromOutboundTag]; parsed by [parseOutboundTag].
     *
     * The Room-autogenerated [RuleEntity.id] is intentionally left at its default
     * (`0L`) so the rule is inserted as a fresh row on restore.
     */
    fun ruleExportToEntity(export: RuleExport): RuleEntity =
        RuleEntity(
            name = export.name,
            userOrder = export.userOrder,
            enabled = export.enabled,
            domains = export.domains,
            ipCidrs = export.ipCidrs,
            ports = export.ports,
            sourcePorts = export.sourcePorts,
            network = parseRuleNetwork(export.network),
            processName = export.processName,
            packages = export.packages,
            outboundTag = parseOutboundTag(export.outboundTag),
        )

    private fun parseRuleNetwork(value: String): RuleNetwork =
        runCatching { RuleNetwork.valueOf(value) }.getOrDefault(RuleNetwork.BOTH)

    /**
     * Parses the durable [RuleExport.outboundTag] encoding (the `"sentinel:kind"` form
     * written by [RuleTypeConverters.fromOutboundTag]) back into an [OutboundTag] via
     * the same shared codec Room uses.
     *
     * Any unrecognized / malformed value degrades to [OutboundTag.Proxy] (the same
     * fallback the CASCADE-deletion policy uses) rather than failing the whole restore.
     */
    fun parseOutboundTag(value: String): OutboundTag =
        runCatching { RuleTypeConverters.toOutboundTag(value) }.getOrDefault(OutboundTag.Proxy)
}
