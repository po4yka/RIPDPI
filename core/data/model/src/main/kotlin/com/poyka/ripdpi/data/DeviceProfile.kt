package com.poyka.ripdpi.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// ---------------------------------------------------------------------------
// Secret wrapper — never leaks value through toString / hashCode / equals
// ---------------------------------------------------------------------------

/**
 * Wrapper for a secret value that suppresses [toString], [hashCode], and
 * [equals] so secrets are never inadvertently written to logs or crash reports.
 * Serialization writes the raw value; callers must handle storage encryption
 * separately (e.g. via Android Keystore).
 */
open class Redacted<T>(
    val value: T,
) {
    override fun toString(): String = "<redacted>"

    override fun hashCode(): Int = 0

    override fun equals(other: Any?): Boolean = other is Redacted<*> && value == other.value
}

/**
 * A [Redacted] string secret with a dedicated [KSerializer] so
 * kotlinx.serialization can round-trip it as a plain JSON string.
 */
@Serializable(with = SecretStringSerializer::class)
class SecretString(
    value: String,
) : Redacted<String>(value) {
    companion object {
        val EMPTY = SecretString("")
    }
}

internal object SecretStringSerializer : KSerializer<SecretString> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SecretString", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: SecretString,
    ) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): SecretString = SecretString(decoder.decodeString())
}

// ---------------------------------------------------------------------------
// Outbound transport shapes
// ---------------------------------------------------------------------------

/** TLS / REALITY security layer used by a VLESS or XHTTP outbound. */
@Serializable
enum class OutboundSecurityLayer {
    TLS,
    REALITY,
    NONE,
}

/** Transport protocol used beneath a VLESS outbound. */
@Serializable
enum class VlessTransport {
    TCP,
    XHTTP,
    WEBSOCKET,
    GRPC,
    HTTPUPGRADE,
}

/** Typed representation of a VLESS outbound with optional REALITY security. */
@Serializable
@SerialName("vless")
data class VlessOutbound(
    val server: String,
    val serverPort: Int,
    val uuid: SecretString,
    val serverName: String = "",
    val securityLayer: OutboundSecurityLayer = OutboundSecurityLayer.TLS,
    /** REALITY public key; non-empty only when [securityLayer] is [OutboundSecurityLayer.REALITY]. */
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val transport: VlessTransport = VlessTransport.TCP,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
)

/**
 * Typed representation of an XHTTP or plain-HTTPS outbound.
 * Separate from VLESS so callers never confuse the two despite the overlapping
 * wire path fields.
 */
@Serializable
@SerialName("xhttp")
data class XhttpOutbound(
    val server: String,
    val serverPort: Int,
    val serverName: String = "",
    val securityLayer: OutboundSecurityLayer = OutboundSecurityLayer.TLS,
    val path: String = "",
    val host: String = "",
    val uuid: SecretString = SecretString.EMPTY,
)

/** Typed representation of a Hysteria2 outbound. */
@Serializable
@SerialName("hysteria2")
data class Hysteria2Outbound(
    val server: String,
    val serverPort: Int,
    val password: SecretString,
    val serverName: String = "",
    val salamanderPassword: SecretString = SecretString.EMPTY,
    val obfsType: String = "",
)

/** Discriminated union of all supported typed outbound shapes. */
@Serializable
sealed interface TypedOutbound {
    @Serializable
    @SerialName("vless")
    data class Vless(
        val outbound: VlessOutbound,
    ) : TypedOutbound

    @Serializable
    @SerialName("xhttp")
    data class Xhttp(
        val outbound: XhttpOutbound,
    ) : TypedOutbound

    @Serializable
    @SerialName("hysteria2")
    data class Hysteria2(
        val outbound: Hysteria2Outbound,
    ) : TypedOutbound
}

// ---------------------------------------------------------------------------
// Selector / URLTest group
// ---------------------------------------------------------------------------

/** Strategy used by a proxy selector/urltest group. */
@Serializable
enum class GroupStrategy {
    SELECTOR,
    URLTEST,
}

/**
 * A logical group referencing outbound IDs, used to drive selector and
 * urltest policies in Xray / sing-box style configs.
 */
@Serializable
data class OutboundGroup(
    val tag: String,
    val strategy: GroupStrategy = GroupStrategy.SELECTOR,
    val outboundTags: List<String> = emptyList(),
    val urlTestUrl: String = "http://www.gstatic.com/generate_204",
    val urlTestIntervalMs: Long = 300_000L,
    val selectedTag: String = "",
)

// ---------------------------------------------------------------------------
// DNS / routing / IPv6 / kill-switch policy
// ---------------------------------------------------------------------------

/** DNS resolution mode for the VPN tunnel. */
@Serializable
enum class ProfileDnsMode {
    SYSTEM,
    DOH,
    DOT,
    CUSTOM_IP,
}

/** IPv6 handling policy. */
@Serializable
enum class Ipv6Policy {
    /**
     * Secure default: no IPv6 address, route, DNS, or allowFamily(AF_INET6) is
     * programmed into the VPN tunnel. All IPv6 traffic is suppressed.
     */
    IPV4_ONLY,

    /** Full dual-stack: IPv6 TUN address, ::/0 route, AAAA DNS forwarded through tunnel. */
    ALLOW,

    /** IPv6 is blocked at the application layer but no tunnel artefacts are added. */
    BLOCK,

    /** Prefer IPv6 paths when available; falls back to IPv4. */
    PREFER,

    /** AAAA queries are forwarded to the resolver unchanged. */
    NATIVE,

    /** NAT64/DNS64 synthesis: AAAA is synthesised from A results via a NAT64 prefix. */
    TRANSLATED,
}

/** Kill-switch operating mode. */
@Serializable
enum class KillSwitchMode {
    OFF,
    ON,
    STRICT,
}

/** DNS configuration carried inside [DeviceProfile]. */
@Serializable
data class ProfileDnsConfig(
    val mode: ProfileDnsMode = ProfileDnsMode.DOH,
    val primaryServer: String = "https://dns.google/dns-query",
    val fallbackServer: String = "",
    val customIp: String = "",
)

/** Routing configuration carried inside [DeviceProfile]. */
@Serializable
data class ProfileRoutingConfig(
    val bypassLan: Boolean = true,
    val bypassCn: Boolean = false,
    val bypassPrivateIp: Boolean = true,
    val extraBypassCidrs: List<String> = emptyList(),
    val extraBypassDomains: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Subscription state
// ---------------------------------------------------------------------------

/** Lifecycle state of a remote subscription attached to a [DeviceProfile]. */
@Serializable
enum class SubscriptionLifecycleState {
    ACTIVE,
    EXPIRED,
    SUSPENDED,
    UNKNOWN,
}

/**
 * Subscription accounting and lifecycle metadata.
 * [expiresAtEpochMillis] of `null` means no expiry is known.
 */
@Serializable
data class SubscriptionState(
    val lifecycleState: SubscriptionLifecycleState = SubscriptionLifecycleState.UNKNOWN,
    val bytesUsed: Long = 0L,
    val bytesRemaining: Long = -1L,
    val expiresAtEpochMillis: Long? = null,
    val subscriptionUserinfo: String = "",
)

// ---------------------------------------------------------------------------
// Redaction metadata for raw extension blocks
// ---------------------------------------------------------------------------

/**
 * Metadata describing which fields in a raw extension block have been
 * redacted during import of a third-party subscription profile.
 */
@Serializable
data class RedactionMetadata(
    val redactedFieldNames: List<String> = emptyList(),
    val rawExtension: String = "",
)

// ---------------------------------------------------------------------------
// DeviceProfile — top-level schema
// ---------------------------------------------------------------------------

/**
 * Current persisted schema version for [DeviceProfile].
 * Bump this constant and add a branch in [DeviceProfileSchema.migrate] for
 * every breaking shape change.
 */
const val CurrentDeviceProfileSchemaVersion: Int = 1

/**
 * Full-device VPN policy profile. This is RIPDPI's internal runtime source of
 * truth: transport URIs are import/export formats, not the app's operating
 * model.
 *
 * Field categories:
 * - Identity: [deviceId], [profileId], [profileVersion]
 * - Transport: [outbounds], [groups]
 * - Policy: [dns], [routing], [ipv6Policy], [killSwitchMode]
 * - Lifecycle: [subscriptionState], [expiresAtEpochMillis]
 * - Extensibility: [redactionMetadata]
 */
@Serializable
data class DeviceProfile(
    val deviceId: String = "",
    val profileId: String = "",
    /** Schema version written by the serializing client. Used for forward migration. */
    val profileVersion: Int = CurrentDeviceProfileSchemaVersion,
    val displayName: String = "",
    val outbounds: List<TypedOutbound> = emptyList(),
    val groups: List<OutboundGroup> = emptyList(),
    val activeOutboundTag: String = "",
    val dns: ProfileDnsConfig = ProfileDnsConfig(),
    val routing: ProfileRoutingConfig = ProfileRoutingConfig(),
    val ipv6Policy: Ipv6Policy = Ipv6Policy.IPV4_ONLY,
    val killSwitchMode: KillSwitchMode = KillSwitchMode.OFF,
    val subscriptionState: SubscriptionState = SubscriptionState(),
    /** Epoch-millis timestamp after which this profile is considered expired; `null` means no expiry. */
    val expiresAtEpochMillis: Long? = null,
    val redactionMetadata: RedactionMetadata = RedactionMetadata(),
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
)

/**
 * Schema versioning and migration helpers for [DeviceProfile].
 */
object DeviceProfileSchema {
    /** Current persisted-schema version. */
    const val SCHEMA_VERSION: Int = CurrentDeviceProfileSchemaVersion

    /**
     * Forward-migrates a raw JSON [json] payload written at [fromVersion] up to
     * [SCHEMA_VERSION]. The current version is the identity transform. Add
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
                    // Example: 1 -> migrateV1ToV2(payload)
                    else -> payload
                }
            version += 1
        }
        return payload
    }
}
