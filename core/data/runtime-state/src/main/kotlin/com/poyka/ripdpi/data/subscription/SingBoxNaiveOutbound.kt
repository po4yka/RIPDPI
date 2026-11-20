package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.MaxNaiveConcurrency
import com.poyka.ripdpi.data.NaiveExtraHeadersResult
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.isValidNaiveCredentials
import com.poyka.ripdpi.data.parseNaiveExtraHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/** sing-box options the NaiveProxy helper has no equivalent for; sing-box writes an unset one as "". */
private val UnsupportedNaiveOptions =
    listOf("stream_receive_window", "quic_congestion_control", "quic_session_receive_window")

/** The TLS options the helper honours. It always verifies `server` against the system trust store. */
private val NaiveTlsOptions = setOf("enabled", "server_name")

private const val MaxNaivePort = 65_535

/**
 * Maps a sing-box `naive` outbound to a NaiveProxy profile, or returns null when the helper cannot
 * run the node as written.
 *
 * The helper dials `server` and verifies that name. A separate TLS name, any other TLS option, UDP
 * over TCP, the QUIC tuning options and a value of the wrong JSON type have no native path. The
 * caller keeps such a node as an inactive raw profile, so no option is dropped silently.
 */
internal fun naiveProfile(
    obj: JsonObject,
    groupId: String,
    name: String,
): ProxyProfile.Naive? =
    runCatching {
        val server = requireNotNull(obj.optional("server") { it.stringOrNull() }?.takeIf { it.isNotBlank() })
        val port = requireNotNull(obj.optional("server_port") { it.strictInt() }?.takeIf { it in 1..MaxNaivePort })
        val tls = obj["tls"] as JsonObject
        require(NaiveTlsOptions.containsAll(tls.keys) && tls.optional("enabled") { it.strictBoolean() } == true)
        require((tls.optional("server_name") { it.stringOrNull() } ?: server) == server)
        val username = obj.optional("username") { it.stringOrNull() }.orEmpty()
        val password = obj.optional("password") { it.stringOrNull() }.orEmpty()
        require(isValidNaiveCredentials(username, password))
        val headers = parseNaiveExtraHeaders(naiveHeaderLines(obj["extra_headers"])) as NaiveExtraHeadersResult.Valid
        // sing-box reads 0 as "not set"; the upstream client then opens one connection.
        val concurrency = obj.optional("insecure_concurrency") { it.strictInt() }?.takeIf { it != 0 } ?: 1
        require(concurrency in 1..MaxNaiveConcurrency)
        require(obj.optional("udp_over_tcp") { it.strictBoolean() } != true)
        require(UnsupportedNaiveOptions.all { key -> obj.optional(key) { it.stringOrNull() }.isNullOrBlank() })
        ProxyProfile.Naive(
            id = UUID.randomUUID().toString(),
            displayName = name,
            groupId = groupId,
            server = server,
            serverPort = port,
            username = username,
            password = password,
            extraHeaders = headers.normalized,
            quic = obj.optional("quic") { it.strictBoolean() } == true,
            concurrency = concurrency,
        )
    }.getOrNull()

/** sing-box `extra_headers` (name to a string or a string list) in the helper's `Name: value` line form. */
private fun naiveHeaderLines(headers: JsonElement?): String {
    if (headers == null || headers is JsonNull) return ""
    return (headers as JsonObject)
        .flatMap { (key, value) ->
            // A colon or a line break here would become a different header name or a second header.
            require(key.none { it == ':' || it == '\r' || it == '\n' })
            (value as? JsonArray ?: listOf(value)).map { item ->
                val text = requireNotNull((item as JsonPrimitive).stringOrNull())
                require(text.none { it == '\r' || it == '\n' })
                "$key: $text"
            }
        }.joinToString("\r\n")
}

/** An absent or null option is unset; a present value of another JSON type than [read] expects throws. */
private fun <T : Any> JsonObject.optional(
    key: String,
    read: (JsonPrimitive) -> T?,
): T? {
    val value = this[key]
    return if (value == null || value is JsonNull) null else requireNotNull(read(value as JsonPrimitive))
}

private fun JsonPrimitive.stringOrNull(): String? = content.takeIf { isString }

private fun JsonPrimitive.strictInt(): Int? = intOrNull.takeUnless { isString }

private fun JsonPrimitive.strictBoolean(): Boolean? = booleanOrNull.takeUnless { isString }
