package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.normalizeImportedTlsFingerprint
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/**
 * Why a single Xray outbound (or share-link) could not be translated to a
 * native RIPDPI relay profile. Stable, locale-independent reasons; the UI layer
 * maps each to a localized string. Mirrors the skip-with-reason contract of
 * [Base64SubscriptionParser] / [SingBoxSubscriptionParser].
 */
enum class XraySkipReason {
    /** `vmess` outbound — removed per ADR 0004 (no native backend). */
    VMESS_REMOVED,

    /** Plain VLESS (TLS / none): RIPDPI's native VLESS backend is REALITY-only. */
    VLESS_REQUIRES_REALITY,

    /** A non-proxy utility outbound (`freedom` / `blackhole` / `dns` / `loopback` / `dokodemo-door`). */
    NON_PROXY_OUTBOUND,

    /** A protocol RIPDPI does not implement natively (`socks`, `http`, `wireguard`, `trojan-go`, …). */
    UNSUPPORTED_PROTOCOL,

    /** A protocol transport the native backend does not implement (for example WebSocket or gRPC). */
    UNSUPPORTED_TRANSPORT,

    /** A uTLS fingerprint alias that has no native fingerprint profile. */
    UNSUPPORTED_FINGERPRINT,

    /** Recognised protocol but the entry is missing a required field (uuid / host / port / key). */
    MALFORMED,

    /**
     * Successfully translated, but NOT activated: RIPDPI runs a single relay, so
     * only the first supported node from a multi-node config is enabled. Emitted
     * by the import flow (not the parser) for the remaining supported nodes, so
     * they are surfaced to the user rather than silently dropped.
     */
    SINGLE_RELAY_ONLY,
}

/** Why an entire Xray import payload could not be interpreted at all. */
enum class XrayUnparseableReason {
    /** Empty / blank input. */
    EMPTY,

    /** Looked like JSON but did not parse. */
    MALFORMED_JSON,

    /** Valid JSON but carried no `outbounds` / single-outbound `protocol`. */
    NO_OUTBOUNDS,

    /** Neither JSON nor any recognisable share link. */
    UNRECOGNISED_INPUT,
}

/** One Xray outbound/link that was skipped, with a localizable [reason]. */
data class XraySkippedNode(
    val index: Int,
    /** Display label safe to show/log — outbound tag or `node N`. NEVER a secret. */
    val label: String,
    val reason: XraySkipReason,
    /** Optional non-secret detail (e.g. the offending protocol name). */
    val detail: String? = null,
)

/**
 * Outcome of translating an Xray config / share-link payload into native
 * RIPDPI [ProxyProfile]s.
 */
sealed interface XrayConfigImportResult {
    /**
     * The payload was understood. [profiles] are the natively-runnable outbounds
     * (may be empty if every node was skipped); [skipped] lists every node that
     * could not be translated, each with a reason.
     */
    data class Translated(
        val profiles: List<ProxyProfile>,
        val skipped: List<XraySkippedNode>,
    ) : XrayConfigImportResult

    /** The payload could not be interpreted as an Xray config or share link. */
    data class Unparseable(
        val reason: XrayUnparseableReason,
    ) : XrayConfigImportResult
}

/**
 * Translates an Xray (xray-core) configuration — a JSON config with an
 * `outbounds` array, a single outbound object, or one-or-many `vless://` /
 * `trojan://` / `ss://` share links (optionally base64-wrapped) — into native
 * RIPDPI [ProxyProfile]s that run on the existing native relay engine.
 *
 * This deliberately does NOT run xray-core. Each outbound RIPDPI supports
 * natively (VLESS+REALITY incl. xHTTP, Trojan, Shadowsocks, and AnyTLS from a
 * share link) maps to its first-class [ProxyProfile]; every other outbound is
 * SKIPPED with a typed [XraySkipReason] (vmess is removed per ADR 0004; plain
 * VLESS without REALITY, utility outbounds, and unknown protocols have no
 * native backend), matching the subscription-import skip behaviour rather than
 * being silently dropped or fake-accepted.
 *
 * Never throws; malformed input yields [XrayConfigImportResult.Unparseable].
 */
object XrayConfigImportParser {
    /** xray-core protocol names that are utility outbounds, not foreign exits. */
    private val NON_PROXY_PROTOCOLS =
        setOf("freedom", "blackhole", "dns", "loopback", "dokodemo-door")

    /**
     * Parses [input] into a [XrayConfigImportResult]. Every produced profile is
     * stamped with [groupId].
     */
    fun parse(
        input: String,
        groupId: String,
    ): XrayConfigImportResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return XrayConfigImportResult.Unparseable(XrayUnparseableReason.EMPTY)
        // A base64-wrapped xray-core JSON config decodes to JSON — route it (and any
        // direct JSON) to the JSON path rather than treating it as a link list.
        val json = jsonPayloadOrNull(trimmed)
        return if (json != null) parseJson(json, groupId) else parseLinks(trimmed, groupId)
    }

    // ---------------------------------------------------------------------
    // xray-core JSON
    // ---------------------------------------------------------------------

    private fun parseJson(
        text: String,
        groupId: String,
    ): XrayConfigImportResult {
        val root = runCatching { RipDpiLenientJson.parseToJsonElement(text) }.getOrNull()
        val outbounds = root?.let { extractOutbounds(it) }
        return when {
            root == null -> XrayConfigImportResult.Unparseable(XrayUnparseableReason.MALFORMED_JSON)
            outbounds == null -> XrayConfigImportResult.Unparseable(XrayUnparseableReason.NO_OUTBOUNDS)
            else -> translateOutbounds(outbounds, groupId)
        }
    }

    private fun translateOutbounds(
        outbounds: List<JsonElement>,
        groupId: String,
    ): XrayConfigImportResult.Translated {
        val profiles = mutableListOf<ProxyProfile>()
        val skipped = mutableListOf<XraySkippedNode>()
        outbounds.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            when (val mapped = mapOutbound(obj, groupId)) {
                is OutboundMapping.Profile -> {
                    profiles += mapped.profile
                }

                is OutboundMapping.Skip -> {
                    skipped +=
                        XraySkippedNode(
                            index = index,
                            label = obj.string("tag") ?: obj.string("protocol") ?: "outbound ${index + 1}",
                            reason = mapped.reason,
                            detail = mapped.detail,
                        )
                }
            }
        }
        return XrayConfigImportResult.Translated(profiles, skipped)
    }

    private sealed interface OutboundMapping {
        data class Profile(
            val profile: ProxyProfile,
        ) : OutboundMapping

        data class Skip(
            val reason: XraySkipReason,
            val detail: String? = null,
        ) : OutboundMapping
    }

    private fun mapOutbound(
        obj: JsonObject,
        groupId: String,
    ): OutboundMapping {
        val protocol =
            obj.string("protocol")?.lowercase()
                ?: return OutboundMapping.Skip(XraySkipReason.MALFORMED)
        val settings = obj["settings"] as? JsonObject
        val stream = obj["streamSettings"] as? JsonObject
        val tag = obj.string("tag")
        return when (protocol) {
            "vless" -> {
                mapVless(settings, stream, tag, groupId)
            }

            "trojan" -> {
                stream.unsupportedTcpOnlyTransport()?.let {
                    OutboundMapping.Skip(XraySkipReason.UNSUPPORTED_TRANSPORT, it)
                } ?: mapTrojan(settings, tag, groupId)
            }

            "shadowsocks" -> {
                stream.unsupportedTcpOnlyTransport()?.let {
                    OutboundMapping.Skip(XraySkipReason.UNSUPPORTED_TRANSPORT, it)
                } ?: mapShadowsocks(settings, tag, groupId)
            }

            "vmess" -> {
                OutboundMapping.Skip(XraySkipReason.VMESS_REMOVED)
            }

            in NON_PROXY_PROTOCOLS -> {
                OutboundMapping.Skip(XraySkipReason.NON_PROXY_OUTBOUND, protocol)
            }

            else -> {
                OutboundMapping.Skip(XraySkipReason.UNSUPPORTED_PROTOCOL, protocol)
            }
        }
    }

    private fun mapVless(
        settings: JsonObject?,
        stream: JsonObject?,
        tag: String?,
        groupId: String,
    ): OutboundMapping {
        val vnext = (settings?.get("vnext") as? JsonArray)?.firstObject()
        val user = (vnext?.get("users") as? JsonArray)?.firstObject()
        val address = vnext?.string("address")
        val port = vnext?.int("port")
        val uuid = user?.string("id")
        val security = stream?.string("security")?.lowercase()
        val reality = stream?.get("realitySettings") as? JsonObject
        val tls = stream?.get("tlsSettings") as? JsonObject
        val publicKey = reality?.string("publicKey")
        val isReality = security == "reality" || !publicKey.isNullOrBlank()
        val transport = stream?.string("network")?.lowercase()
        val fingerprint = reality?.string("fingerprint") ?: tls?.string("fingerprint")
        return when {
            address == null || port == null || user == null || uuid == null -> {
                OutboundMapping.Skip(XraySkipReason.MALFORMED)
            }

            transport != null && transport !in setOf("raw", "tcp", "xhttp") -> {
                OutboundMapping.Skip(XraySkipReason.UNSUPPORTED_TRANSPORT, transport)
            }

            fingerprint != null && normalizeImportedTlsFingerprint(fingerprint) == null -> {
                OutboundMapping.Skip(XraySkipReason.UNSUPPORTED_FINGERPRINT, fingerprint.lowercase())
            }

            !isReality && security == "tls" && transport == "xhttp" -> {
                OutboundMapping.Profile(buildPlainVless(stream, user, address, port, uuid, tag, groupId))
            }

            !isReality -> {
                OutboundMapping.Skip(XraySkipReason.VLESS_REQUIRES_REALITY)
            }

            reality == null || publicKey.isNullOrBlank() -> {
                OutboundMapping.Skip(XraySkipReason.MALFORMED)
            }

            else -> {
                OutboundMapping.Profile(
                    buildVlessReality(stream, reality, user, address, port, uuid, publicKey, tag, groupId),
                )
            }
        }
    }

    private fun mapTrojan(
        settings: JsonObject?,
        tag: String?,
        groupId: String,
    ): OutboundMapping {
        val creds = firstServerCredentials(settings) ?: return OutboundMapping.Skip(XraySkipReason.MALFORMED)
        return OutboundMapping.Profile(
            ProxyProfile.Trojan(
                id = newId(),
                displayName = tag ?: creds.address,
                groupId = groupId,
                server = creds.address,
                serverPort = creds.port,
                password = creds.password,
            ),
        )
    }

    private fun mapShadowsocks(
        settings: JsonObject?,
        tag: String?,
        groupId: String,
    ): OutboundMapping {
        val creds = firstServerCredentials(settings)
        val method = (settings?.get("servers") as? JsonArray)?.firstObject()?.string("method")
        if (creds == null || method == null) {
            return OutboundMapping.Skip(XraySkipReason.MALFORMED)
        }
        return OutboundMapping.Profile(
            ProxyProfile.Shadowsocks(
                id = newId(),
                displayName = tag ?: creds.address,
                groupId = groupId,
                server = creds.address,
                serverPort = creds.port,
                method = method,
                password = creds.password,
            ),
        )
    }

    // ---------------------------------------------------------------------
    // Share links (vless:// / trojan:// / ss:// / vmess:// …), optionally base64
    // ---------------------------------------------------------------------

    private fun parseLinks(
        input: String,
        groupId: String,
    ): XrayConfigImportResult {
        val text = decodeBase64OrPlain(input)
        if (!text.contains("://")) {
            return XrayConfigImportResult.Unparseable(XrayUnparseableReason.UNRECOGNISED_INPUT)
        }
        val profiles = mutableListOf<ProxyProfile>()
        val skipped = mutableListOf<XraySkippedNode>()
        var index = 0
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || !line.contains("://")) return@forEach
            val current = index
            index += 1
            classifyLink(line, current, groupId, profiles, skipped)
        }
        return if (profiles.isEmpty() && skipped.isEmpty()) {
            XrayConfigImportResult.Unparseable(XrayUnparseableReason.UNRECOGNISED_INPUT)
        } else {
            XrayConfigImportResult.Translated(profiles, skipped)
        }
    }

    private fun classifyLink(
        line: String,
        index: Int,
        groupId: String,
        profiles: MutableList<ProxyProfile>,
        skipped: MutableList<XraySkippedNode>,
    ) {
        val scheme = line.substringBefore("://").lowercase()
        if (scheme == "vmess") {
            skipped += XraySkippedNode(index, "vmess link", XraySkipReason.VMESS_REMOVED)
            return
        }
        val profile = ProxyUriCodec.parse(line)
        when (profile) {
            is ProxyProfile.VlessReality -> {
                val unsupportedFingerprint =
                    profile.fingerprint?.takeIf { normalizeImportedTlsFingerprint(it) == null }
                // Defensive check for malformed profiles from future parser changes:
                // REALITY cannot complete a handshake without public key material.
                if (profile.realityPublicKey.isBlank()) {
                    skipped += XraySkippedNode(index, "$scheme link", XraySkipReason.MALFORMED, scheme)
                } else if (unsupportedFingerprint != null) {
                    skipped +=
                        XraySkippedNode(
                            index,
                            "$scheme link",
                            XraySkipReason.UNSUPPORTED_FINGERPRINT,
                            unsupportedFingerprint.lowercase(),
                        )
                } else {
                    profiles += profile.copy(groupId = groupId)
                }
            }

            is ProxyProfile.Trojan -> {
                profiles += profile.copy(groupId = groupId)
            }

            is ProxyProfile.Shadowsocks -> {
                profiles += profile.copy(groupId = groupId)
            }

            is ProxyProfile.AnyTls -> {
                profiles += profile.copy(groupId = groupId)
            }

            is ProxyProfile.Vless -> {
                when {
                    profile.fingerprint != null && normalizeImportedTlsFingerprint(profile.fingerprint) == null -> {
                        skipped +=
                            XraySkippedNode(
                                index,
                                "$scheme link",
                                XraySkipReason.UNSUPPORTED_FINGERPRINT,
                                profile.fingerprint.lowercase(),
                            )
                    }

                    profile.xhttpPath != null || profile.xhttpHost != null -> {
                        profiles += profile.copy(groupId = groupId)
                    }

                    else -> {
                        skipped += XraySkippedNode(index, "$scheme link", XraySkipReason.VLESS_REQUIRES_REALITY)
                    }
                }
            }

            null -> {
                val transport = unsupportedVlessLinkTransport(line)
                skipped +=
                    if (transport != null) {
                        XraySkippedNode(index, "$scheme link", XraySkipReason.UNSUPPORTED_TRANSPORT, transport)
                    } else {
                        XraySkippedNode(index, "$scheme link", XraySkipReason.MALFORMED, scheme)
                    }
            }

            else -> {
                skipped +=
                    XraySkippedNode(index, "$scheme link", XraySkipReason.UNSUPPORTED_PROTOCOL, scheme)
            }
        }
    }
}

/** Base64 encodes input in groups of four characters; the unit used to pad. */
private const val Base64GroupSize = 4

/** Default XTLS flow stamped on a REALITY profile when the source omits one. */
private const val DefaultRealityFlow = "xtls-rprx-vision"

// ---------------------------------------------------------------------
// File-private helpers (the sister parsers keep their own copies; these are
// intentionally NOT shared across modules). Top-level so the parser object
// stays under detekt's per-object function threshold.
// ---------------------------------------------------------------------

/**
 * The address/port/password common to a `trojan` / `shadowsocks` `servers[0]`
 * entry, or null when any of the three required fields is absent.
 */
private data class ServerCredentials(
    val address: String,
    val port: Int,
    val password: String,
)

private fun firstServerCredentials(settings: JsonObject?): ServerCredentials? {
    val server = (settings?.get("servers") as? JsonArray)?.firstObject()
    val address = server?.string("address")
    val port = server?.int("port")
    val password = server?.string("password")
    return if (address != null && port != null && password != null) {
        ServerCredentials(address, port, password)
    } else {
        null
    }
}

private fun buildVlessReality(
    stream: JsonObject?,
    reality: JsonObject,
    user: JsonObject,
    address: String,
    port: Int,
    uuid: String,
    publicKey: String,
    tag: String?,
    groupId: String,
): ProxyProfile.VlessReality {
    val network = stream?.string("network")?.lowercase()
    val xhttp = if (network == "xhttp") stream["xhttpSettings"] as? JsonObject else null
    val isXhttp = network == "xhttp"
    return ProxyProfile.VlessReality(
        id = newId(),
        displayName = tag ?: address,
        groupId = groupId,
        server = address,
        serverPort = port,
        uuid = uuid,
        realityPublicKey = publicKey,
        realityShortId = reality.string("shortId").orEmpty(),
        serverName = reality.string("serverName") ?: address,
        flow = user.rawString("flow") ?: DefaultRealityFlow,
        fingerprint = reality.string("fingerprint"),
        xhttpPath = if (isXhttp) xhttp?.rawString("path").orEmpty() else null,
        xhttpHost = if (isXhttp) xhttp?.rawString("host") else null,
        xhttpMode = xhttp?.rawString("mode") ?: com.poyka.ripdpi.data.RelayXhttpModeAuto,
    )
}

private fun buildPlainVless(
    stream: JsonObject?,
    user: JsonObject,
    address: String,
    port: Int,
    uuid: String,
    tag: String?,
    groupId: String,
): ProxyProfile.Vless {
    val tls = stream?.get("tlsSettings") as? JsonObject
    val xhttp = stream?.get("xhttpSettings") as? JsonObject
    return ProxyProfile.Vless(
        id = newId(),
        displayName = tag ?: address,
        groupId = groupId,
        server = address,
        serverPort = port,
        uuid = uuid,
        serverName = tls?.string("serverName") ?: address,
        flow = user.rawString("flow").orEmpty(),
        fingerprint = tls?.string("fingerprint"),
        xhttpPath = xhttp?.rawString("path").orEmpty(),
        xhttpHost = xhttp?.rawString("host"),
        xhttpMode = xhttp?.rawString("mode") ?: com.poyka.ripdpi.data.RelayXhttpModeAuto,
    )
}

private fun JsonObject?.unsupportedTcpOnlyTransport(): String? =
    this?.string("network")?.lowercase()?.takeIf { it !in setOf("raw", "tcp") }

private fun unsupportedVlessLinkTransport(line: String): String? {
    val rawQuery =
        line
            .takeIf { it.startsWith("vless://", ignoreCase = true) }
            ?.let { runCatching { java.net.URI(it).rawQuery }.getOrNull() }
    return rawQuery
        ?.split('&')
        ?.firstNotNullOfOrNull { part ->
            val separator = part.indexOf('=')
            if (separator > 0 && part.substring(0, separator) == "type") {
                java.net.URLDecoder
                    .decode(part.substring(separator + 1), "UTF-8")
                    .lowercase()
            } else {
                null
            }
        }?.takeIf { it !in setOf("tcp", "xhttp") }
}

private fun looksLikeJson(text: String): Boolean = text.startsWith("{") || text.startsWith("[")

/**
 * Returns the JSON text to parse — [trimmed] itself when it already looks like
 * JSON, or its base64-decoded form when that decodes to JSON — else null.
 */
private fun jsonPayloadOrNull(trimmed: String): String? {
    if (looksLikeJson(trimmed)) return trimmed
    val decoded = decodeBase64Text(trimmed)?.trim()
    return decoded?.takeIf { looksLikeJson(it) }
}

/** Returns the outbound elements, or null when the payload has none. */
private fun extractOutbounds(root: JsonElement): List<JsonElement>? =
    when (root) {
        is JsonArray -> {
            root.toList()
        }

        is JsonObject -> {
            val outbounds = root["outbounds"]
            when {
                outbounds is JsonArray -> outbounds.toList()
                root["protocol"] is JsonPrimitive -> listOf(root)
                else -> null
            }
        }

        else -> {
            null
        }
    }

/** Normalises CRLF/CR line endings to LF. */
private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

/**
 * Returns the decoded text of a base64-wrapped link list, or [input]
 * unchanged when it is already a plain link list / not base64. Line endings
 * are always normalised to LF.
 */
private fun decodeBase64OrPlain(input: String): String {
    if (input.contains("://")) return normalizeNewlines(input)
    val decoded = decodeBase64Text(input)?.takeIf { it.contains("://") }
    return normalizeNewlines(decoded ?: input)
}

/**
 * Best-effort base64 decode of a (possibly whitespace-padded, URL-safe) blob to
 * text, or null when it does not decode. The caller validates the shape of the
 * decoded text (link list vs JSON); a successful decode of random input may still
 * be garbage.
 */
private fun decodeBase64Text(input: String): String? {
    val condensed =
        input
            .trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
    if (condensed.isEmpty()) return null
    val padded = condensed.replace('-', '+').replace('_', '/').let { padBase64(it) }
    return listOf(Base64.getMimeDecoder(), Base64.getDecoder())
        .firstNotNullOfOrNull { decoder -> runCatching { String(decoder.decode(padded)) }.getOrNull() }
}

private fun padBase64(value: String): String {
    val remainder = value.length % Base64GroupSize
    return if (remainder == 0) value else value + "=".repeat(Base64GroupSize - remainder)
}

private fun newId(): String =
    java.util.UUID
        .randomUUID()
        .toString()

private fun JsonArray.firstObject(): JsonObject? = firstOrNull() as? JsonObject

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.rawString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}
