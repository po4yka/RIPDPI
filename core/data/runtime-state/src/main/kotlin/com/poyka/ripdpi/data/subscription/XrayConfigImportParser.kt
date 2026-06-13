package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
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
    private const val DEFAULT_REALITY_FLOW = "xtls-rprx-vision"

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
        return if (looksLikeJson(trimmed)) {
            parseJson(trimmed, groupId)
        } else {
            parseLinks(trimmed, groupId)
        }
    }

    private fun looksLikeJson(text: String): Boolean = text.startsWith("{") || text.startsWith("[")

    // ---------------------------------------------------------------------
    // xray-core JSON
    // ---------------------------------------------------------------------

    private fun parseJson(
        text: String,
        groupId: String,
    ): XrayConfigImportResult {
        val root =
            runCatching { RipDpiLenientJson.parseToJsonElement(text) }.getOrElse {
                return XrayConfigImportResult.Unparseable(XrayUnparseableReason.MALFORMED_JSON)
            }
        val outbounds =
            extractOutbounds(root)
                ?: return XrayConfigImportResult.Unparseable(XrayUnparseableReason.NO_OUTBOUNDS)

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
            "vless" -> mapVless(settings, stream, tag, groupId)
            "trojan" -> mapTrojan(settings, tag, groupId)
            "shadowsocks" -> mapShadowsocks(settings, tag, groupId)
            "vmess" -> OutboundMapping.Skip(XraySkipReason.VMESS_REMOVED)
            in NON_PROXY_PROTOCOLS -> OutboundMapping.Skip(XraySkipReason.NON_PROXY_OUTBOUND, protocol)
            else -> OutboundMapping.Skip(XraySkipReason.UNSUPPORTED_PROTOCOL, protocol)
        }
    }

    private fun mapVless(
        settings: JsonObject?,
        stream: JsonObject?,
        tag: String?,
        groupId: String,
    ): OutboundMapping {
        val vnext = (settings?.get("vnext") as? JsonArray)?.firstObject()
        val address = vnext?.string("address")
        val port = vnext?.int("port")
        val user = (vnext?.get("users") as? JsonArray)?.firstObject()
        val uuid = user?.string("id")
        if (address == null || port == null || uuid == null) {
            return OutboundMapping.Skip(XraySkipReason.MALFORMED)
        }
        val security = stream?.string("security")?.lowercase()
        val reality = stream?.get("realitySettings") as? JsonObject
        val publicKey = reality?.string("publicKey")
        val isReality = security == "reality" || !publicKey.isNullOrBlank()
        if (!isReality) {
            return OutboundMapping.Skip(XraySkipReason.VLESS_REQUIRES_REALITY)
        }
        if (publicKey.isNullOrBlank()) {
            return OutboundMapping.Skip(XraySkipReason.MALFORMED)
        }
        val network = stream.string("network")?.lowercase()
        val xhttp = if (network == "xhttp") stream["xhttpSettings"] as? JsonObject else null
        return OutboundMapping.Profile(
            ProxyProfile.VlessReality(
                id = newId(),
                displayName = tag ?: address,
                groupId = groupId,
                server = address,
                serverPort = port,
                uuid = uuid,
                realityPublicKey = publicKey,
                realityShortId = reality.string("shortId").orEmpty(),
                serverName = reality.string("serverName") ?: address,
                flow = user.string("flow") ?: DEFAULT_REALITY_FLOW,
                fingerprint = reality.string("fingerprint"),
                xhttpPath = xhttp?.string("path"),
                xhttpHost = xhttp?.string("host"),
            ),
        )
    }

    private fun mapTrojan(
        settings: JsonObject?,
        tag: String?,
        groupId: String,
    ): OutboundMapping {
        val server = (settings?.get("servers") as? JsonArray)?.firstObject()
        val address = server?.string("address")
        val port = server?.int("port")
        val password = server?.string("password")
        if (address == null || port == null || password == null) {
            return OutboundMapping.Skip(XraySkipReason.MALFORMED)
        }
        return OutboundMapping.Profile(
            ProxyProfile.Trojan(
                id = newId(),
                displayName = tag ?: address,
                groupId = groupId,
                server = address,
                serverPort = port,
                password = password,
            ),
        )
    }

    private fun mapShadowsocks(
        settings: JsonObject?,
        tag: String?,
        groupId: String,
    ): OutboundMapping {
        val server = (settings?.get("servers") as? JsonArray)?.firstObject()
        val address = server?.string("address")
        val port = server?.int("port")
        val method = server?.string("method")
        val password = server?.string("password")
        if (address == null || port == null || method == null || password == null) {
            return OutboundMapping.Skip(XraySkipReason.MALFORMED)
        }
        return OutboundMapping.Profile(
            ProxyProfile.Shadowsocks(
                id = newId(),
                displayName = tag ?: address,
                groupId = groupId,
                server = address,
                serverPort = port,
                method = method,
                password = password,
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
        if (profiles.isEmpty() && skipped.isEmpty()) {
            return XrayConfigImportResult.Unparseable(XrayUnparseableReason.UNRECOGNISED_INPUT)
        }
        return XrayConfigImportResult.Translated(profiles, skipped)
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
                profiles += profile.copy(groupId = groupId)
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
                skipped += XraySkippedNode(index, "$scheme link", XraySkipReason.VLESS_REQUIRES_REALITY)
            }

            null -> {
                skipped += XraySkippedNode(index, "$scheme link", XraySkipReason.MALFORMED, scheme)
            }

            else -> {
                skipped +=
                    XraySkippedNode(index, "$scheme link", XraySkipReason.UNSUPPORTED_PROTOCOL, scheme)
            }
        }
    }

    /**
     * Returns the decoded text of a base64-wrapped link list, or [input]
     * unchanged when it is already a plain link list / not base64.
     */
    private fun decodeBase64OrPlain(input: String): String {
        if (input.contains("://")) return input.replace("\r\n", "\n").replace('\r', '\n')
        val condensed =
            input
                .trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
        if (condensed.isEmpty()) return input
        val padded = condensed.replace('-', '+').replace('_', '/').let { padBase64(it) }
        for (decoder in listOf(Base64.getUrlDecoder(), Base64.getMimeDecoder(), Base64.getDecoder())) {
            val decoded = runCatching { String(decoder.decode(padded)) }.getOrNull() ?: continue
            if (decoded.contains("://")) return decoded.replace("\r\n", "\n").replace('\r', '\n')
        }
        return input
    }

    private fun padBase64(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }

    // ---------------------------------------------------------------------
    // JSON helpers (file-private; the sister parsers keep their own copies)
    // ---------------------------------------------------------------------

    private fun newId(): String =
        java.util.UUID
            .randomUUID()
            .toString()

    private fun JsonArray.firstObject(): JsonObject? = firstOrNull() as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }
}
