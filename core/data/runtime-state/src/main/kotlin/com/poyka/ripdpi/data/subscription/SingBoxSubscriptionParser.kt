package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
    ) : SingBoxParseResult

    /** Parsing failed; [message] carries a human-readable, location-aware reason. */
    data class Error(
        val message: String,
    ) : SingBoxParseResult
}

/**
 * Parses a sing-box JSON subscription — either a bare `outbounds:` array or a
 * single-outbound object — into [ProxyProfile] records.
 *
 * Detection mirrors NekoBox's `RawUpdater.parseJSON()`: the payload is parsed
 * with a permissive JSON reader; a top-level `outbounds` array is iterated,
 * otherwise a lone outbound object is wrapped as a one-element list. Known
 * `type:` values map to first-class [ProxyProfile] subtypes; every other type
 * round-trips as [ProxyProfile.RawConfig] holding the raw JSON fragment.
 * `selector` / `urltest` entries are group metadata, not profiles, and are
 * skipped here (see [SelectorUrltestGroupImport]). `inbounds`, `route`, `dns`
 * and `experimental` sections are ignored. Malformed JSON yields
 * [SingBoxParseResult.Error].
 */
object SingBoxSubscriptionParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Outbound `type:` values that are group metadata rather than concrete nodes. */
    val GROUP_OUTBOUND_TYPES: Set<String> = setOf("selector", "urltest")

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
    ): SingBoxParseResult {
        val rootElement =
            runCatching { json.parseToJsonElement(payload) }.getOrElse { error ->
                return SingBoxParseResult.Error(
                    "malformed sing-box JSON: ${error.message ?: "could not be parsed"}",
                )
            }
        val outbounds =
            when (val extracted = extractOutboundsFromElement(rootElement)) {
                is OutboundExtraction.Failure -> return SingBoxParseResult.Error(extracted.message)
                is OutboundExtraction.Outbounds -> extracted.entries
            }
        var profiles =
            outbounds.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val type = obj.string("type") ?: return@mapNotNull null
                if (type.lowercase() in GROUP_OUTBOUND_TYPES) return@mapNotNull null
                mapOutbound(type, obj, groupId)
            }

        // Process RIPDPI extension block when present and version == 1.
        val ripdpiBlock = (rootElement as? JsonObject)?.get("ripdpi") as? JsonObject
        val awgProfiles = mutableListOf<AmneziaWgSubscriptionProfile>()
        if (ripdpiBlock != null) {
            val schemaVersion = ripdpiBlock.int("schema_version")
            if (schemaVersion == RIPDPI_SCHEMA_VERSION) {
                // 1. AmneziaWG entries.
                val awgArray = ripdpiBlock["amneziawg"] as? JsonArray
                awgArray?.forEach { element ->
                    val awgObj = element as? JsonObject ?: return@forEach
                    mapRipdpiAwg(awgObj, groupId)?.let { awgProfiles += it }
                }
                // 2. Hysteria extras: patch already-parsed Hysteria2 profiles by tag.
                val hyExtras = ripdpiBlock["hysteria_extras"] as? JsonObject
                if (hyExtras != null) {
                    profiles = applyHysteriaExtras(profiles, hyExtras)
                }
            }
            // Unknown schema_version: silently ignore the ripdpi block.
        }

        return SingBoxParseResult.Success(profiles = profiles, amneziaWgProfiles = awgProfiles)
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
            runCatching { json.parseToJsonElement(payload) }.getOrElse { error ->
                return OutboundExtraction.Failure(
                    "malformed sing-box JSON: ${error.message ?: "could not be parsed"}",
                )
            }
        return extractOutboundsFromElement(element)
    }

    /**
     * Routes a pre-parsed [JsonElement] to the outbound extraction result,
     * avoiding a second JSON parse when the element was already parsed.
     */
    private fun extractOutboundsFromElement(element: JsonElement): OutboundExtraction =
        when (element) {
            is JsonObject -> {
                val outbounds = element["outbounds"]
                when {
                    outbounds is JsonArray -> {
                        OutboundExtraction.Outbounds(outbounds.toList())
                    }

                    element["type"] is JsonPrimitive -> {
                        OutboundExtraction.Outbounds(listOf(element))
                    }

                    else -> {
                        OutboundExtraction.Failure(
                            "sing-box JSON has neither an 'outbounds' array nor a single-outbound 'type'",
                        )
                    }
                }
            }

            is JsonArray -> {
                OutboundExtraction.Outbounds(element.toList())
            }

            else -> {
                OutboundExtraction.Failure("sing-box JSON root is not an object or array")
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
            "vless", "vmess" -> {
                if (server != null && port != null) {
                    val tlsObj = obj["tls"] as? JsonObject
                    val realityObj = tlsObj?.get("reality") as? JsonObject
                    // Detect REALITY: tls.reality.enabled == true, OR tls.reality.public_key is non-empty.
                    val realityPublicKey = realityObj?.string("public_key")
                    val realityEnabled = realityObj?.let { r ->
                        (r["enabled"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                    }
                    val isReality = (realityEnabled == true) || !realityPublicKey.isNullOrBlank()
                    if (isReality) {
                        val realityShortId = realityObj?.string("short_id").orEmpty()
                        val serverName = tlsObj?.string("server_name") ?: server
                        val flow = obj.string("flow") ?: "xtls-rprx-vision"
                        val fingerprint = (tlsObj?.get("utls") as? JsonObject)?.string("fingerprint")
                        val transportObj = obj["transport"] as? JsonObject
                        val isXhttp = transportObj?.string("type")?.lowercase() == "xhttp"
                        val xhttpPath = if (isXhttp) transportObj?.string("path") else null
                        val xhttpHost = if (isXhttp) transportObj?.string("host") else null
                        ProxyProfile.VlessReality(
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
                        )
                    } else {
                        ProxyProfile.Vless(
                            id = newId(),
                            displayName = name,
                            groupId = groupId,
                            server = server,
                            serverPort = port,
                            uuid = obj.string("uuid").orEmpty(),
                        )
                    }
                } else {
                    rawConfig(name, groupId, obj)
                }
            }

            "shadowsocks" -> {
                if (server != null && port != null) {
                    ProxyProfile.Shadowsocks(
                        id = newId(),
                        displayName = name,
                        groupId = groupId,
                        server = server,
                        serverPort = port,
                        method = obj.string("method").orEmpty(),
                        password = obj.string("password").orEmpty(),
                    )
                } else {
                    rawConfig(name, groupId, obj)
                }
            }

            "trojan" -> {
                if (server != null && port != null) {
                    ProxyProfile.Trojan(
                        id = newId(),
                        displayName = name,
                        groupId = groupId,
                        server = server,
                        serverPort = port,
                        password = obj.string("password").orEmpty(),
                    )
                } else {
                    rawConfig(name, groupId, obj)
                }
            }

            "hysteria2" -> {
                if (server != null && port != null) {
                    ProxyProfile.Hysteria2(
                        id = newId(),
                        displayName = name,
                        groupId = groupId,
                        server = server,
                        serverPort = port,
                        password = obj.string("password").orEmpty(),
                    )
                } else {
                    rawConfig(name, groupId, obj)
                }
            }

            "anytls" -> {
                val password = obj.string("password")
                if (server != null && port != null && password != null) {
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

            else -> {
                // hysteria (v1), tuic, wireguard, shadowtls, ssh, … — no
                // first-class subtype; round-trip the raw JSON fragment so the
                // engine can still consume it via the custom-config path.
                rawConfig(name, groupId, obj)
            }
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
            config = json.encodeToString(JsonObject.serializer(), obj),
        )

    // -------------------------------------------------------------------------
    // RIPDPI extension block helpers
    // -------------------------------------------------------------------------

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
        val host = endpoint.substring(0, lastColon).removePrefix("[").removeSuffix("]")
            .takeIf { it.isNotBlank() } ?: return null
        val port = endpoint.substring(lastColon + 1).toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null

        // Address list (interface-level CIDR strings).
        val addressList = (obj["address"] as? JsonArray)
            ?.filterIsInstance<JsonPrimitive>()
            ?.mapNotNull { it.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()

        // DNS list.
        val dnsList = (obj["dns"] as? JsonArray)
            ?.filterIsInstance<JsonPrimitive>()
            ?.mapNotNull { it.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()

        val mtu = obj.int("mtu")

        // AmneziaWG obfuscation parameters.
        val awg = AmneziaWgParameters(
            jc = obj.int("jc"),
            jmin = obj.int("jmin"),
            jmax = obj.int("jmax"),
            s1 = obj.int("s1"),
            s2 = obj.int("s2"),
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
            allowedIps = (peerObj["allowed_ips"] as? JsonArray)
                ?.filterIsInstance<JsonPrimitive>()
                ?.mapNotNull { it.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                ?: emptyList(),
            persistentKeepalive = peerObj.int("persistent_keepalive"),
            awg = awg,
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
            val obfsPassword = if (obfsType == "salamander") obfsObj?.string("password") else null
            val insecure = extras.bool("insecure")
            val portHopObj = extras["port_hopping"] as? JsonObject
            val portHopPorts = portHopObj?.string("ports")
            val portHopInterval = portHopObj?.string("interval")
            profile.copy(
                obfsPassword = obfsPassword ?: profile.obfsPassword,
                insecure = insecure ?: profile.insecure,
                portHopPorts = portHopPorts ?: profile.portHopPorts,
                portHopInterval = portHopInterval ?: profile.portHopInterval,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Shared private helpers
    // -------------------------------------------------------------------------

    private fun newId(): String = UUID.randomUUID().toString()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

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

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        /** The only `ripdpi.schema_version` value this parser understands. */
        const val RIPDPI_SCHEMA_VERSION = 1
    }
}
