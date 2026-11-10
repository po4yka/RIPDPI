package com.poyka.ripdpi.data

import java.net.URI
import java.util.Base64

/** Checks the required managed-transport fields before persistence and activation. */
fun torBridgeValidationError(bridgeLine: String): String? {
    if (bridgeLine.isBlank()) return "required"
    val tokens = bridgeLine.trim().split(Regex("\\s+"))
    val start = if (tokens.first().equals("Bridge", ignoreCase = true)) 1 else 0
    val transport = tokens.getOrNull(start)
    if (transport !in setOf(RelayKindObfs4, RelayKindSnowflake, RelayKindWebTunnel)) return "invalid"
    val endpoint = tokens.getOrNull(start + 1)
    if (!(transport == RelayKindSnowflake && endpoint == "-") && !validTorBridgeEndpoint(endpoint)) return "invalid"
    val rest = tokens.drop(start + 2)
    val identities = rest.takeWhile { '=' !in it }
    val types = identities.map(::torBridgeIdentityType)
    if ("rsa" !in types || null in types || types.toSet().size != types.size) return "invalid"
    val pairs = rest.drop(identities.size).map { it.split('=', limit = 2) }
    if (pairs.any { it.size != 2 || it[0].isBlank() || it[1].isBlank() } ||
        pairs.map { it[0] }.toSet().size != pairs.size
    ) {
        return "invalid"
    }
    val values = pairs.associate { it[0] to it[1] }
    return when (transport) {
        RelayKindObfs4 -> {
            if (values["cert"].isNullOrBlank() || values["iat-mode"] !in setOf("0", "1", "2")) "invalid" else null
        }

        RelayKindWebTunnel -> {
            if (validTorWebTunnelUrl(values["url"])) null else "invalid"
        }

        else -> {
            null // Snowflake supplies defaults for omitted transport options.
        }
    }
}

private fun validTorBridgeEndpoint(endpoint: String?): Boolean {
    val address = runCatching { URI("tcp://${endpoint.orEmpty()}") }.getOrNull() ?: return false
    return !address.host.isNullOrBlank() && address.port in 1..65_535 && address.rawUserInfo == null &&
        address.rawPath.isNullOrEmpty() && address.rawQuery == null && address.rawFragment == null
}

private fun validTorWebTunnelUrl(rawUrl: String?): Boolean {
    val url = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return false
    return url.scheme.equals("https", ignoreCase = true) && !url.host.isNullOrBlank() &&
        (url.port == -1 || url.port in 1..65_535) && !url.rawPath.isNullOrEmpty() && url.rawPath != "/"
}

private fun torBridgeIdentityType(value: String): String? {
    if (value.matches(Regex("\\$?[0-9A-Fa-f]{40}"))) return "rsa"
    val type = if (':' in value) value.substringBefore(':').lowercase() else "ed25519"
    val expectedBytes =
        when (type) {
            "rsa" -> 20
            "ed25519" -> 32
            else -> return null
        }
    val encoded = value.substringAfter(':')
    val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
    return type.takeIf {
        decoded.size == expectedBytes && Base64.getEncoder().withoutPadding().encodeToString(decoded) == encoded
    }
}
