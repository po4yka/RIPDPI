package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.nativeVlessProfileRejection
import com.poyka.ripdpi.data.validateNativeRelayProfileResult
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Stable reason why a subscription entry was not made selectable. */
enum class SingBoxSkipReason {
    UNSUPPORTED_TRANSPORT,
    UNSUPPORTED_OBFUSCATION,
    UNSUPPORTED_FINGERPRINT,
    INVALID_NETWORK_POLICY,

    /** The entry's protocol has no native backend. */
    UNSUPPORTED_PROTOCOL,

    /** The entry maps to a native profile that fails backend validation. */
    INVALID_SETTINGS,
}

/** One rejected subscription entry. [detail] is a non-secret protocol or metadata identifier. */
data class SingBoxSkippedNode(
    val index: Int,
    val label: String,
    val reason: SingBoxSkipReason,
    val detail: String? = null,
)

/** Rejects connection framing and chaining that the native profile cannot retain. */
internal fun unsupportedSingBoxConnectionOptions(
    obj: JsonObject,
    index: Int,
    label: String,
): SingBoxSkippedNode? {
    val multiplex = obj["multiplex"] as? JsonObject
    val multiplexEnabled = (multiplex?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
    val option =
        when {
            obj["detour"]?.let { it != JsonPrimitive("") } == true -> "detour"
            multiplexEnabled -> "multiplex"
            else -> return null
        }
    return SingBoxSkippedNode(index, label, SingBoxSkipReason.UNSUPPORTED_TRANSPORT, option)
}

/** Why a whole payload was refused. Carries tags and protocol identifiers, never credentials or addresses. */
sealed interface ImportRejection {
    /** A RIPDPI bundle of another contract version; [found] is `null` when the bundle states none. */
    data class SchemaVersion(
        val found: Int?,
        val supported: Int = SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION,
    ) : ImportRejection

    /** No entry is usable; [node] is the first one refused. */
    data class Entry(
        val node: SingBoxSkippedNode,
    ) : ImportRejection
}

/**
 * Splits [profiles] into those the native backend can run and those it cannot. A refused profile is
 * named by its outbound tag when [tags] holds it, else by its 1-based position, never by an address.
 */
fun splitNativeProfiles(
    profiles: List<ProxyProfile>,
    tags: Set<String> = emptySet(),
): Pair<List<ProxyProfile>, List<SingBoxSkippedNode>> {
    val skipped = mutableListOf<SingBoxSkippedNode>()
    val usable =
        profiles.filterIndexed { index, profile ->
            val reason =
                when {
                    profile is ProxyProfile.RawConfig -> SingBoxSkipReason.UNSUPPORTED_PROTOCOL
                    !validateNativeRelayProfileResult(profile).getOrDefault(false) -> SingBoxSkipReason.INVALID_SETTINGS
                    else -> null
                }
            if (reason != null) {
                val label = profile.displayName.takeIf { it in tags } ?: "#${index + 1}"
                val detail =
                    if (profile is ProxyProfile.RawConfig) {
                        rawOutboundType(
                            profile,
                        )
                    } else {
                        nativeVlessProfileRejection(profile)
                    }
                skipped += SingBoxSkippedNode(index, label, reason, detail)
            }
            reason == null
        }
    return usable to skipped
}

/** The sing-box `type` of a raw outbound fragment, when it is a plain protocol identifier. */
private fun rawOutboundType(profile: ProxyProfile.RawConfig): String? =
    (runCatching { RipDpiLenientJson.parseToJsonElement(profile.config) }.getOrNull() as? JsonObject)
        ?.get("type")
        ?.let { (it as? JsonPrimitive)?.content }
        ?.takeIf(ProtocolIdentifier::matches)

private val ProtocolIdentifier = Regex("[A-Za-z0-9_-]{1,24}")
