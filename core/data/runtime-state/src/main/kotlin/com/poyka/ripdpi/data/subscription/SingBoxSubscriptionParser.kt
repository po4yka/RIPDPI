package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.normalizeImportedTlsFingerprint
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Outcome of a [SingBoxSubscriptionParser] run. */
sealed interface SingBoxParseResult {
    /**
     * Parsing succeeded.
     *
     * [profiles] is the mapped sing-box outbound list (may be empty).
     * [amneziaWgProfiles] contains AmneziaWG device-VPN profiles produced from
     * the `ripdpi.amneziawg` extension block; empty for a plain sing-box bundle.
     */
    data class Success(
        val profiles: List<ProxyProfile>,
        val amneziaWgProfiles: List<AmneziaWgSubscriptionProfile> = emptyList(),
        /** Supported Android package routes imported from `route.rules`. */
        val packageRoutingRules: List<PackageRoutingRule> = emptyList(),
        /**
         * Declared transport topology from `ripdpi.topology`, or `null` for a
         * plain sing-box bundle or one whose `schema_version` is unknown. Lets
         * the client distinguish a split-hop / realm-relayed endpoint from a
         * direct one instead of mis-modelling a dual-role flow.
         */
        val topology: RipdpiTopology? = null,
        /**
         * Subscription expiry from `ripdpi.expires` (RFC-3339 / ISO-8601), or
         * `null` when the bundle omits it. Lets the client warn "expires in N
         * days, refresh" proactively rather than discovering expiry only when a
         * later `/sub` fetch returns 410. The `.meta` sidecar / `410` remains
         * the enforcement point; this is the early-warning copy.
         */
        val tokenExpiresAtEpochMillis: Long? = null,
        /** Nodes rejected before mapping because their declared wire mode is unsupported. */
        val skipped: List<SingBoxSkippedNode> = emptyList(),
    ) : SingBoxParseResult

    /** Parsing failed; [message] carries a human-readable, location-aware reason. */
    data class Error(
        val message: String,
    ) : SingBoxParseResult
}

/** Stable reason why a sing-box outbound was not made selectable. */
enum class SingBoxSkipReason {
    UNSUPPORTED_TRANSPORT,
    UNSUPPORTED_OBFUSCATION,
    UNSUPPORTED_PORT_HOPPING,
    UNSUPPORTED_FINGERPRINT,
}

/** One rejected sing-box outbound. [detail] is a non-secret protocol identifier. */
data class SingBoxSkippedNode(
    val index: Int,
    val label: String,
    val reason: SingBoxSkipReason,
    val detail: String? = null,
)

/**
 * Transport topology declared by `ripdpi.topology`. Both fields are optional
 * within the block; absent values default to the direct-deployment case
 * ([splitHopEgress] = false, [hysteriaRealm] = null).
 */
data class RipdpiTopology(
    /**
     * True when the endpoint is the entry of a two-VPS split-hop topology
     * (entry and egress are different hosts); the client must not assume the
     * egress IP equals the endpoint IP.
     */
    val splitHopEgress: Boolean = false,
    /**
     * Realm/relay id when the Hysteria2 endpoint is reached via a STUN/NAT
     * realm relay rather than directly; `null` when direct.
     */
    val hysteriaRealm: String? = null,
)

/**
 * Parses a sing-box JSON subscription — either a bare `outbounds:` array or a
 * single-outbound object — into [ProxyProfile] records.
 *
 * Detection mirrors NekoBox's `RawUpdater.parseJSON()`: the payload is parsed
 * with a permissive JSON reader; a top-level `outbounds` array is iterated,
 * otherwise a lone outbound object is wrapped as a one-element list. Known
 * `type:` values map to first-class [ProxyProfile] subtypes; every other type
 * round-trips as [ProxyProfile.RawConfig] holding the raw JSON fragment.
 * `selector` / `urltest` group entries and `direct` / `block` / `dns` service
 * outbounds are routing metadata, not remote profiles, and are skipped here
 * (see [SelectorUrltestGroupImport]). Supported Android
 * `route.rules[].package_name` entries are returned with subscription
 * provenance. `inbounds`, `dns` and `experimental` sections are ignored. Malformed JSON yields
 * [SingBoxParseResult.Error].
 */
object SingBoxSubscriptionParser {
    /** Outbound `type:` values that configure routing/group behavior rather than remote nodes. */
    val NON_PROFILE_OUTBOUND_TYPES: Set<String> = setOf("selector", "urltest", "direct", "block", "dns")

    /**
     * The only `ripdpi.schema_version` this parser understands. Post-1 fields
     * are additive and optional, so this stays 1; a future breaking change
     * bumps it in lockstep with the server. Must equal `x-contract-version` in
     * `contract/ripdpi-bundle.schema.json` — `RipdpiBundleContractTest` pins it.
     */
    const val RIPDPI_SCHEMA_VERSION: Int = 1

    /**
     * Parses [payload] into a [SingBoxParseResult]. Every produced
     * [ProxyProfile] is stamped with [groupId]. Never throws.
     *
     * When the payload is a RIPDPI-extended bundle (a sing-box JSON config with
     * an extra top-level `ripdpi` object), the `ripdpi` block is processed after
     * the standard outbounds:
     * - `ripdpi.amneziawg[]` entries are mapped to [AmneziaWgSubscriptionProfile]
     *   records and returned in [SingBoxParseResult.Success.amneziaWgProfiles].
     * - `ripdpi.hysteria_extras` entries are matched by tag to already-parsed
     *   [ProxyProfile.Hysteria2] profiles and the salamander obfs password /
     *   insecure / port-hopping fields are merged onto those profiles.
     * - An unknown or missing `schema_version` causes the `ripdpi` block to be
     *   ignored (forward-compatible); standard outbounds are still imported.
     */
    fun parse(
        payload: String,
        groupId: String,
    ): SingBoxParseResult =
        runCatching { singBoxJson.parseToJsonElement(payload) }.fold(
            onSuccess = { rootElement -> parseRootElement(rootElement, groupId) },
            onFailure = { error ->
                SingBoxParseResult.Error(
                    "malformed sing-box JSON: ${error.message ?: "could not be parsed"}",
                )
            },
        )

    private fun parseRootElement(
        rootElement: JsonElement,
        groupId: String,
    ): SingBoxParseResult {
        val routeResult =
            if (rootElement is JsonObject) {
                SingBoxRouteRulesParser.parse(rootElement, groupId)
            } else {
                SingBoxRouteRulesParseResult.Success(emptyList())
            }
        return when (routeResult) {
            is SingBoxRouteRulesParseResult.Error -> {
                SingBoxParseResult.Error(routeResult.message)
            }

            is SingBoxRouteRulesParseResult.Success -> {
                parseExtractedOutbounds(rootElement, groupId, routeResult.rules)
            }
        }
    }

    private fun parseExtractedOutbounds(
        rootElement: JsonElement,
        groupId: String,
        packageRoutingRules: List<PackageRoutingRule>,
    ): SingBoxParseResult =
        when (val extracted = extractOutboundsFromElement(rootElement)) {
            is OutboundExtraction.Failure -> {
                SingBoxParseResult.Error(extracted.message)
            }

            is OutboundExtraction.Outbounds -> {
                val skipped = mutableListOf<SingBoxSkippedNode>()
                val baseProfiles =
                    extracted.entries.mapIndexedNotNull { index, element ->
                        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                        val type = obj.string("type") ?: return@mapIndexedNotNull null
                        if (type.lowercase() in NON_PROFILE_OUTBOUND_TYPES) return@mapIndexedNotNull null
                        unsupportedNode(obj, type, index)?.let {
                            skipped += it
                            return@mapIndexedNotNull null
                        }
                        mapOutbound(type, obj, groupId)
                    }
                val ripdpi = processRipdpiBlock(rootElement, baseProfiles, groupId)
                SingBoxParseResult.Success(
                    profiles = ripdpi.profiles,
                    amneziaWgProfiles = ripdpi.amneziaWgProfiles,
                    packageRoutingRules = packageRoutingRules,
                    topology = ripdpi.topology,
                    tokenExpiresAtEpochMillis = ripdpi.tokenExpiresAtEpochMillis,
                    skipped = skipped,
                )
            }
        }

    /** Extracted outbound entries, or a typed failure when the payload is not sing-box JSON. */
    internal sealed interface OutboundExtraction {
        data class Outbounds(
            val entries: List<JsonElement>,
        ) : OutboundExtraction

        data class Failure(
            val message: String,
        ) : OutboundExtraction
    }

    /**
     * Permissively parses [payload] and routes on its top-level shape:
     * an `outbounds` array is returned as-is; a lone outbound object (carrying
     * a `type` key) is wrapped as a single-element list.
     */
    internal fun extractOutbounds(payload: String): OutboundExtraction {
        val element =
            runCatching { singBoxJson.parseToJsonElement(payload) }.getOrElse { error ->
                return OutboundExtraction.Failure(
                    "malformed sing-box JSON: ${error.message ?: "could not be parsed"}",
                )
            }
        return extractOutboundsFromElement(element)
    }
}

private fun unsupportedNode(
    obj: JsonObject,
    type: String,
    index: Int,
): SingBoxSkippedNode? {
    val normalizedType = type.lowercase()
    val label = obj.string("tag") ?: "$normalizedType outbound ${index + 1}"
    return unsupportedTransport(obj, normalizedType, index, label)
        ?: unsupportedFingerprint(obj, normalizedType, index, label)
        ?: unsupportedHysteria2Option(obj, normalizedType, index, label)
}

private fun unsupportedTransport(
    obj: JsonObject,
    type: String,
    index: Int,
    label: String,
): SingBoxSkippedNode? {
    val transport = (obj["transport"] as? JsonObject)?.string("type")?.lowercase()
    val allowedTransports =
        when (type) {
            "vless" -> setOf("tcp", "xhttp")
            "trojan", "shadowsocks" -> setOf("tcp")
            else -> emptySet()
        }
    return transport
        ?.takeIf { type in setOf("vless", "trojan", "shadowsocks") && it !in allowedTransports }
        ?.let { SingBoxSkippedNode(index, label, SingBoxSkipReason.UNSUPPORTED_TRANSPORT, it) }
}

private fun unsupportedFingerprint(
    obj: JsonObject,
    type: String,
    index: Int,
    label: String,
): SingBoxSkippedNode? {
    val fingerprint = ((obj["tls"] as? JsonObject)?.get("utls") as? JsonObject)?.string("fingerprint")
    return fingerprint
        ?.takeIf { type == "vless" && normalizeImportedTlsFingerprint(it) == null }
        ?.let { SingBoxSkippedNode(index, label, SingBoxSkipReason.UNSUPPORTED_FINGERPRINT, it.lowercase()) }
}

private fun unsupportedHysteria2Option(
    obj: JsonObject,
    type: String,
    index: Int,
    label: String,
): SingBoxSkippedNode? {
    if (type != "hysteria2") return null
    val hasPortHopping = obj["server_ports"] != null || obj["hop_interval"] != null || obj["hop_interval_max"] != null
    val obfsType = (obj["obfs"] as? JsonObject)?.string("type")?.lowercase()
    return when {
        hasPortHopping -> {
            SingBoxSkippedNode(index, label, SingBoxSkipReason.UNSUPPORTED_PORT_HOPPING, "server_ports")
        }

        obfsType != null && obfsType != "salamander" -> {
            SingBoxSkippedNode(index, label, SingBoxSkipReason.UNSUPPORTED_OBFUSCATION, obfsType)
        }

        else -> {
            null
        }
    }
}

/** Permissive JSON reader shared by the sing-box parser and its outbound mappers. */
private val singBoxJson =
    RipDpiLenientJson

/** Upper bound for a TCP/UDP port number. */
private const val MaxPort = 65_535

/** Default VLESS REALITY flow when the outbound omits one. */
private const val DefaultRealityFlow = "xtls-rprx-vision"

/**
 * Routes a pre-parsed [JsonElement] to the outbound extraction result,
 * avoiding a second JSON parse when the element was already parsed.
 */
private fun extractOutboundsFromElement(element: JsonElement): SingBoxSubscriptionParser.OutboundExtraction =
    when (element) {
        is JsonObject -> {
            val outbounds = element["outbounds"]
            when {
                outbounds is JsonArray -> {
                    SingBoxSubscriptionParser.OutboundExtraction.Outbounds(outbounds.toList())
                }

                element["type"] is JsonPrimitive -> {
                    SingBoxSubscriptionParser.OutboundExtraction.Outbounds(listOf(element))
                }

                else -> {
                    SingBoxSubscriptionParser.OutboundExtraction.Failure(
                        "sing-box JSON has neither an 'outbounds' array nor a single-outbound 'type'",
                    )
                }
            }
        }

        is JsonArray -> {
            SingBoxSubscriptionParser.OutboundExtraction.Outbounds(element.toList())
        }

        else -> {
            SingBoxSubscriptionParser.OutboundExtraction.Failure("sing-box JSON root is not an object or array")
        }
    }

private fun mapOutbound(
    type: String,
    obj: JsonObject,
    groupId: String,
): ProxyProfile {
    val tag = obj.string("tag")
    val server = obj.string("server")
    val port = obj.int("server_port")
    val name = tag ?: server ?: type
    return when (type.lowercase()) {
        "vless" -> mapVless(obj, groupId, server, port, name)

        "shadowsocks" -> mapShadowsocks(obj, groupId, server, port, name)

        "trojan" -> mapTrojan(obj, groupId, server, port, name)

        "hysteria2" -> mapHysteria2(obj, groupId, server, port, name)

        "anytls" -> mapAnyTls(obj, groupId, server, port, name)

        // vmess, trojan-go, hysteria (v1), tuic, wireguard, shadowtls, ssh, … —
        // no first-class subtype; round-trip the raw JSON fragment so the engine
        // can still consume it via the custom-config path.
        else -> rawConfig(name, groupId, obj)
    }
}

private fun mapVless(
    obj: JsonObject,
    groupId: String,
    server: String?,
    port: Int?,
    name: String,
): ProxyProfile {
    if (server == null || port == null) return rawConfig(name, groupId, obj)
    val tlsObj = obj["tls"] as? JsonObject
    val realityObj = tlsObj?.get("reality") as? JsonObject
    // Detect REALITY: tls.reality.enabled == true, OR tls.reality.public_key is non-empty.
    val realityPublicKey = realityObj?.string("public_key")
    val realityEnabled =
        realityObj?.let { r ->
            (r["enabled"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        }
    val isReality = realityEnabled == true || !realityPublicKey.isNullOrBlank()
    return if (isReality) {
        mapVlessReality(obj, groupId, server, port, name, tlsObj, realityObj, realityPublicKey)
    } else {
        // A plain VLESS node with no UUID cannot connect; keep it as an inert
        // RawConfig rather than a selectable dead member (audit P1-10).
        val uuid = obj.string("uuid")
        if (uuid.isNullOrBlank()) {
            rawConfig(name, groupId, obj)
        } else {
            val xhttp = obj.xhttpTransport()
            ProxyProfile.Vless(
                id = newId(),
                displayName = name,
                groupId = groupId,
                server = server,
                serverPort = port,
                uuid = uuid,
                serverName = tlsObj?.string("server_name") ?: server,
                flow = obj.rawString("flow").orEmpty(),
                fingerprint = (tlsObj?.get("utls") as? JsonObject)?.string("fingerprint"),
                xhttpPath = if (xhttp != null) xhttp.rawString("path").orEmpty() else null,
                xhttpHost = xhttp?.rawString("host"),
                xhttpMode = xhttp?.rawString("mode") ?: com.poyka.ripdpi.data.RelayXhttpModeAuto,
            )
        }
    }
}

private fun JsonObject.xhttpTransport(): JsonObject? =
    (this["transport"] as? JsonObject)?.takeIf { it.string("type")?.equals("xhttp", ignoreCase = true) == true }

@Suppress("LongParameterList")
private fun mapVlessReality(
    obj: JsonObject,
    groupId: String,
    server: String,
    port: Int,
    name: String,
    tlsObj: JsonObject?,
    realityObj: JsonObject?,
    realityPublicKey: String?,
): ProxyProfile {
    val realityShortId = realityObj?.string("short_id").orEmpty()
    val serverName = tlsObj?.string("server_name") ?: server
    val flow = obj.rawString("flow") ?: DefaultRealityFlow
    val fingerprint = (tlsObj?.get("utls") as? JsonObject)?.string("fingerprint")
    val transportObj = obj["transport"] as? JsonObject
    val isXhttp = transportObj?.string("type")?.lowercase() == "xhttp"
    val xhttpPath = if (isXhttp) transportObj.rawString("path").orEmpty() else null
    val xhttpHost = if (isXhttp) transportObj.rawString("host") else null
    val xhttpMode =
        if (isXhttp) {
            transportObj.rawString(
                "mode",
            ) ?: com.poyka.ripdpi.data.RelayXhttpModeAuto
        } else {
            com.poyka.ripdpi.data.RelayXhttpModeAuto
        }
    return ProxyProfile.VlessReality(
        id = newId(),
        displayName = name,
        groupId = groupId,
        server = server,
        serverPort = port,
        uuid = obj.string("uuid").orEmpty(),
        realityPublicKey = realityPublicKey.orEmpty(),
        realityShortId = realityShortId,
        serverName = serverName,
        flow = flow,
        fingerprint = fingerprint,
        xhttpPath = xhttpPath,
        xhttpHost = xhttpHost,
        xhttpMode = xhttpMode,
    )
}

// A node with a blank/unsupported credential must NOT become a first-class
// connectable member; round-trip it as an inert RawConfig so the user never sees
// a selectable member that can never connect (audit P1-10). Mirrors mapAnyTls.
private fun mapShadowsocks(
    obj: JsonObject,
    groupId: String,
    server: String?,
    port: Int?,
    name: String,
): ProxyProfile {
    if (server == null || port == null) return rawConfig(name, groupId, obj)
    val method = obj.string("method").orEmpty()
    val password = obj.string("password").orEmpty()
    return if (password.isNotEmpty() && ProxyUriCodec.isSupportedShadowsocksMethod(method)) {
        ProxyProfile.Shadowsocks(
            id = newId(),
            displayName = name,
            groupId = groupId,
            server = server,
            serverPort = port,
            method = method,
            password = password,
        )
    } else {
        rawConfig(name, groupId, obj)
    }
}

private fun mapTrojan(
    obj: JsonObject,
    groupId: String,
    server: String?,
    port: Int?,
    name: String,
): ProxyProfile {
    val password = obj.string("password")
    val tls = obj["tls"] as? JsonObject
    return if (server != null && port != null && !password.isNullOrBlank()) {
        ProxyProfile.Trojan(
            id = newId(),
            displayName = name,
            groupId = groupId,
            server = server,
            serverPort = port,
            password = password,
            serverName = tls?.string("server_name"),
        )
    } else {
        rawConfig(name, groupId, obj)
    }
}

private fun mapHysteria2(
    obj: JsonObject,
    groupId: String,
    server: String?,
    port: Int?,
    name: String,
): ProxyProfile {
    val password = obj.string("password")
    val tls = obj["tls"] as? JsonObject
    val obfs = obj["obfs"] as? JsonObject
    return if (server != null && port != null && !password.isNullOrBlank()) {
        ProxyProfile.Hysteria2(
            id = newId(),
            displayName = name,
            groupId = groupId,
            server = server,
            serverPort = port,
            password = password,
            serverName = tls?.string("server_name"),
            obfsPassword = obfs?.rawString("password"),
            insecure = (tls?.get("insecure") as? JsonPrimitive)?.booleanOrNull,
        )
    } else {
        rawConfig(name, groupId, obj)
    }
}

private fun mapAnyTls(
    obj: JsonObject,
    groupId: String,
    server: String?,
    port: Int?,
    name: String,
): ProxyProfile {
    val password = obj.string("password")
    return if (server != null && port != null && password != null) {
        ProxyProfile.AnyTls(
            id = newId(),
            displayName = name,
            groupId = groupId,
            server = server,
            serverPort = port,
            serverName = obj.nestedString("tls", "server_name") ?: obj.string("server_name") ?: server,
            password = password,
        )
    } else {
        rawConfig(name, groupId, obj)
    }
}

private fun rawConfig(
    name: String,
    groupId: String,
    obj: JsonObject,
): ProxyProfile =
    ProxyProfile.RawConfig(
        id = newId(),
        displayName = name,
        groupId = groupId,
        config = singBoxJson.encodeToString(JsonObject.serializer(), obj),
    )

// -------------------------------------------------------------------------
// RIPDPI extension block helpers
// -------------------------------------------------------------------------

/** Profiles plus AmneziaWG profiles and metadata produced by processing the `ripdpi` block. */
private data class RipdpiBlockResult(
    val profiles: List<ProxyProfile>,
    val amneziaWgProfiles: List<AmneziaWgSubscriptionProfile>,
    val topology: RipdpiTopology? = null,
    val tokenExpiresAtEpochMillis: Long? = null,
)

/**
 * Processes the optional top-level `ripdpi` extension block: maps
 * `ripdpi.amneziawg[]` entries to [AmneziaWgSubscriptionProfile]s and patches
 * already-parsed Hysteria2 profiles from `ripdpi.hysteria_extras`. An absent
 * block, or an unknown `schema_version`, leaves [profiles] untouched and yields
 * no AmneziaWG profiles (forward-compatible).
 */
private fun processRipdpiBlock(
    rootElement: JsonElement,
    profiles: List<ProxyProfile>,
    groupId: String,
): RipdpiBlockResult {
    val ripdpiBlock = (rootElement as? JsonObject)?.get("ripdpi") as? JsonObject
    val versioned =
        ripdpiBlock?.takeIf { it.int("schema_version") == SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION }
            ?: return RipdpiBlockResult(profiles, emptyList())
    val awgProfiles =
        (versioned["amneziawg"] as? JsonArray)
            ?.mapNotNull { element -> (element as? JsonObject)?.let { mapRipdpiAwg(it, groupId) } }
            ?: emptyList()
    val hyExtras = versioned["hysteria_extras"] as? JsonObject
    val patchedProfiles = hyExtras?.let { applyHysteriaExtras(profiles, it) } ?: profiles
    return RipdpiBlockResult(
        profiles = patchedProfiles,
        amneziaWgProfiles = awgProfiles,
        topology = parseRipdpiTopology(versioned["topology"] as? JsonObject),
        tokenExpiresAtEpochMillis = parseRipdpiExpiry(versioned.string("expires")),
    )
}

/** Normalizes date-only or RFC 3339 expiry text to epoch milliseconds. */
private const val IsoDateLength = 10

private fun parseRipdpiExpiry(raw: String?): Long? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching {
        if (value.length == IsoDateLength) {
            LocalDate
                .parse(value)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        } else {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }
    }.getOrNull()
}

/**
 * Maps `ripdpi.topology` to a [RipdpiTopology], or `null` when the block omits
 * topology. Missing inner fields default to the direct-deployment case.
 */
private fun parseRipdpiTopology(obj: JsonObject?): RipdpiTopology? {
    if (obj == null) return null
    return RipdpiTopology(
        splitHopEgress = obj.bool("split_hop_egress") ?: false,
        hysteriaRealm = obj.string("hysteria_realm"),
    )
}

/**
 * Maps one entry from `ripdpi.amneziawg[]` to an [AmneziaWgSubscriptionProfile].
 *
 * The `private_key_placeholder: true` flag means the server-emitted bundle
 * carries no usable private key (it is a per-device secret). The private key
 * field on the produced profile is set to an empty string — mirroring the
 * [WireGuardIniSubscriptionParser] behaviour when a `.conf` has a blank
 * `PrivateKey` — so the UI can detect it and prompt the user to supply the
 * real key via the AWG editor.
 *
 * Returns `null` when the entry is too malformed to produce a profile (e.g.
 * a missing public key or endpoint).
 */
@Suppress("ReturnCount")
private fun mapRipdpiAwg(
    obj: JsonObject,
    groupId: String,
): AmneziaWgSubscriptionProfile? {
    val tag = obj.string("tag") ?: return null
    val peerObj = obj["peer"] as? JsonObject ?: return null
    val publicKey = peerObj.string("public_key") ?: return null
    val endpoint = peerObj.string("endpoint") ?: return null

    // Parse endpoint host:port.
    val lastColon = endpoint.lastIndexOf(':')
    if (lastColon <= 0 || lastColon == endpoint.length - 1) return null
    val host =
        endpoint
            .substring(0, lastColon)
            .removePrefix("[")
            .removeSuffix("]")
            .takeIf { it.isNotBlank() } ?: return null
    val port = endpoint.substring(lastColon + 1).toIntOrNull()?.takeIf { it in 1..MaxPort } ?: return null

    // Address list (interface-level CIDR strings).
    val addressList =
        (obj["address"] as? JsonArray)
            ?.filterIsInstance<JsonPrimitive>()
            ?.mapNotNull { it.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()

    // DNS list.
    val dnsList =
        (obj["dns"] as? JsonArray)
            ?.filterIsInstance<JsonPrimitive>()
            ?.mapNotNull { it.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()

    val mtu = obj.int("mtu")

    // AmneziaWG obfuscation parameters.
    val awg =
        AmneziaWgParameters(
            jc = obj.int("jc"),
            jmin = obj.int("jmin"),
            jmax = obj.int("jmax"),
            s1 = obj.int("s1"),
            s2 = obj.int("s2"),
            s3 = obj.int("s3"),
            s4 = obj.int("s4"),
            h1 = obj.long("h1"),
            h2 = obj.long("h2"),
            h3 = obj.long("h3"),
            h4 = obj.long("h4"),
            i1 = obj.string("i1"),
            i2 = obj.string("i2"),
            i3 = obj.string("i3"),
            i4 = obj.string("i4"),
            i5 = obj.string("i5"),
        )
    runCatching { awg.requireArm64Safe() }.getOrElse { return null }

    // `private_key_placeholder: true` means no usable private key is present.
    // Use an empty string as the placeholder so the AWG editor can detect it.
    val privateKey = if (obj.bool("private_key_placeholder") == true) "" else obj.string("private_key").orEmpty()

    return AmneziaWgSubscriptionProfile(
        displayName = tag,
        groupId = groupId,
        server = host,
        serverPort = port,
        interfacePrivateKey = privateKey,
        interfaceAddress = addressList,
        dns = dnsList,
        mtu = mtu,
        peerPublicKey = publicKey,
        peerPresharedKey = peerObj.string("preshared_key"),
        allowedIps =
            (peerObj["allowed_ips"] as? JsonArray)
                ?.filterIsInstance<JsonPrimitive>()
                ?.mapNotNull { it.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                ?: emptyList(),
        persistentKeepalive = peerObj.int("persistent_keepalive"),
        awg = awg,
        cohortFingerprint = obj.string("cohort_fingerprint"),
    )
}

/**
 * Patches already-parsed [ProxyProfile.Hysteria2] entries with extras from
 * `ripdpi.hysteria_extras`. Matching is by [ProxyProfile.displayName] (which
 * is set from the outbound's `tag` during parsing).
 */
private fun applyHysteriaExtras(
    profiles: List<ProxyProfile>,
    hyExtras: JsonObject,
): List<ProxyProfile> {
    if (hyExtras.isEmpty()) return profiles
    return profiles.map { profile ->
        if (profile !is ProxyProfile.Hysteria2) return@map profile
        val extras = hyExtras[profile.displayName] as? JsonObject ?: return@map profile
        val obfsObj = extras["obfs"] as? JsonObject
        val obfsType = obfsObj?.string("type")?.lowercase()
        val obfsPassword = if (obfsType == "salamander") obfsObj.string("password") else null
        val insecure = extras.bool("insecure")
        val portHopObj = extras["port_hopping"] as? JsonObject
        val portHopPorts = portHopObj?.string("ports")
        val portHopInterval = portHopObj?.string("interval")
        val salamanderUpstreamTag = extras.string("salamander_upstream_tag")
        profile.copy(
            obfsPassword = obfsPassword ?: profile.obfsPassword,
            insecure = insecure ?: profile.insecure,
            portHopPorts = portHopPorts ?: profile.portHopPorts,
            portHopInterval = portHopInterval ?: profile.portHopInterval,
            salamanderUpstreamTag = salamanderUpstreamTag ?: profile.salamanderUpstreamTag,
        )
    }
}

// -------------------------------------------------------------------------
// Shared private JSON helpers
// -------------------------------------------------------------------------

private fun newId(): String = UUID.randomUUID().toString()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.rawString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.nestedString(
    objectKey: String,
    valueKey: String,
): String? = (this[objectKey] as? JsonObject)?.string(valueKey)

private fun JsonObject.int(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}

private fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
}

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
