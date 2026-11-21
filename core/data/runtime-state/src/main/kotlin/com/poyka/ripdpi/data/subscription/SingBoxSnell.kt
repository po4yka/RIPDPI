package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SnellUserKeyMaxBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

private const val SnellSupportedVersion = 4

/**
 * The native Snell client speaks only the plain v4 stream: version 4, no connection reuse, no obfs,
 * TCP. Any other Snell outbound returns null and stays an inert raw profile.
 */
internal fun nativeSnellProfile(
    obj: JsonObject,
    groupId: String,
    server: String?,
    port: Int?,
    name: String,
): ProxyProfile.Snell? {
    val psk = obj.text("psk")?.takeIf { it.isNotBlank() }
    val userKey = obj.text("userkey").orEmpty()
    val plain =
        (obj["version"] as? JsonPrimitive)?.intOrNull == SnellSupportedVersion &&
            (obj["reuse"] as? JsonPrimitive).let { it == null || it.booleanOrNull == false } &&
            (obj.text("obfs_mode")?.takeIf { it.isNotBlank() } ?: "none") == "none" &&
            (obj.text("network")?.takeIf { it.isNotBlank() } ?: "tcp") == "tcp" &&
            userKey.toByteArray(Charsets.UTF_8).size <= SnellUserKeyMaxBytes
    val complete = server != null && port != null && psk != null
    if (!plain || !complete) return null
    return ProxyProfile.Snell(
        id = UUID.randomUUID().toString(),
        displayName = name,
        groupId = groupId,
        server = server,
        serverPort = port,
        psk = psk,
        userKey = userKey,
    )
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
