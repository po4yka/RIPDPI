package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.MaxNaiveConcurrency
import com.poyka.ripdpi.data.NaiveExtraHeadersResult
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.isValidNaiveCredentials
import com.poyka.ripdpi.data.parseNaiveExtraHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/** sing-box options the NaiveProxy helper has no equivalent for. */
private val UnsupportedNaiveOptions =
    listOf("stream_receive_window", "quic_congestion_control", "quic_session_receive_window")

/**
 * Maps a sing-box `naive` outbound to a NaiveProxy profile, or returns null when the helper cannot
 * run the node as written.
 *
 * The helper dials `server` and verifies that name against the system trust store. A separate TLS
 * name, a private CA, ECH, UDP over TCP and the QUIC tuning options have no native path. The caller
 * keeps such a node as an inactive raw profile, so no option is dropped silently.
 */
internal fun naiveProfile(
    obj: JsonObject,
    groupId: String,
    name: String,
): ProxyProfile.Naive? {
    val server = obj.text("server")?.takeIf { it.isNotBlank() }
    val port = (obj["server_port"] as? JsonPrimitive)?.intOrNull
    val tls = obj["tls"] as? JsonObject
    val username = obj.text("username").orEmpty()
    val password = obj.text("password").orEmpty()
    val headers = parseNaiveExtraHeaders(naiveHeaderLines(obj["extra_headers"])) as? NaiveExtraHeadersResult.Valid
    // sing-box reads 0 as "not set"; the upstream client then opens one connection.
    val concurrency = (obj["insecure_concurrency"] as? JsonPrimitive)?.intOrNull?.takeIf { it != 0 } ?: 1
    val udpOverTcp = obj["udp_over_tcp"]
    val runnable =
        server != null &&
            port != null &&
            headers != null &&
            tls.isNativeXhttpTls() &&
            (tls?.text("server_name")?.takeIf { it.isNotBlank() } ?: server) == server &&
            isValidNaiveCredentials(username, password) &&
            concurrency in 1..MaxNaiveConcurrency &&
            (udpOverTcp == null || (udpOverTcp as? JsonPrimitive)?.booleanOrNull == false) &&
            UnsupportedNaiveOptions.none { !obj.text(it).isNullOrBlank() }
    return if (runnable) {
        ProxyProfile.Naive(
            id = UUID.randomUUID().toString(),
            displayName = name,
            groupId = groupId,
            server = server,
            serverPort = port,
            username = username,
            password = password,
            extraHeaders = headers.normalized,
            quic = (obj["quic"] as? JsonPrimitive)?.booleanOrNull == true,
            concurrency = concurrency,
        )
    } else {
        null
    }
}

/** sing-box `extra_headers` (name to a string or a string list) in the helper's `Name: value` line form. */
private fun naiveHeaderLines(headers: JsonElement?): String =
    (headers as? JsonObject)
        .orEmpty()
        .flatMap { (key, value) ->
            (value as? JsonArray ?: listOf(value)).map { "$key: ${(it as? JsonPrimitive)?.contentOrNull.orEmpty()}" }
        }.joinToString("\r\n")

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
