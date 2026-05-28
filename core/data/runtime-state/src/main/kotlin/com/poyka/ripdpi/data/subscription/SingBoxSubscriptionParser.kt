package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/** Outcome of a [SingBoxSubscriptionParser] run. */
sealed interface SingBoxParseResult {
    /** Parsing succeeded; [profiles] is the mapped outbound list (may be empty). */
    data class Success(
        val profiles: List<ProxyProfile>,
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
     */
    fun parse(
        payload: String,
        groupId: String,
    ): SingBoxParseResult {
        val outbounds =
            when (val extracted = extractOutbounds(payload)) {
                is OutboundExtraction.Failure -> return SingBoxParseResult.Error(extracted.message)
                is OutboundExtraction.Outbounds -> extracted.entries
            }
        val profiles =
            outbounds.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val type = obj.string("type") ?: return@mapNotNull null
                if (type.lowercase() in GROUP_OUTBOUND_TYPES) return@mapNotNull null
                mapOutbound(type, obj, groupId)
            }
        return SingBoxParseResult.Success(profiles)
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
        return when (element) {
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
}
