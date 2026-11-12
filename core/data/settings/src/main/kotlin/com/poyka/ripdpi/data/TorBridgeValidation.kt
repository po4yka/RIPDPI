package com.poyka.ripdpi.data

import java.net.URI
import java.util.Base64

private const val TorBridgeMaxPort = 65_535
private const val Obfs4CertificateBytes = 52
private const val TorRsaIdentityBytes = 20
private const val TorEd25519IdentityBytes = 32
private val TorBridgeTransports = setOf(RelayKindObfs4, RelayKindSnowflake, RelayKindWebTunnel)

/** Checks the required managed-transport fields before persistence and activation. */
fun torBridgeValidationError(bridgeLine: String): String? =
    when {
        bridgeLine.isBlank() -> "required"
        validTorBridge(bridgeLine) -> null
        else -> "invalid"
    }

private fun validTorBridge(bridgeLine: String): Boolean {
    val tokens = bridgeLine.trim().split(Regex("\\s+"))
    val start = if (tokens.first().equals("Bridge", ignoreCase = true)) 1 else 0
    val transport = tokens.getOrNull(start)
    val endpoint = tokens.getOrNull(start + 1)
    val endpointValid = (transport == RelayKindSnowflake && endpoint == "-") || validTorBridgeEndpoint(endpoint)
    val values = torBridgeOptions(tokens.drop(start + 2)) ?: return false
    return transport in TorBridgeTransports && endpointValid && validTorTransportOptions(transport, values)
}

private fun torBridgeOptions(rest: List<String>): Map<String, String>? {
    val identities = rest.takeWhile { '=' !in it }
    val types = identities.map(::torBridgeIdentityType)
    val identitiesValid = "rsa" in types && null !in types && types.toSet().size == types.size
    val pairs = rest.drop(identities.size).map { it.split('=', limit = 2) }
    val optionsValid = pairs.all { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
    val namesUnique = pairs.map { it[0] }.toSet().size == pairs.size
    return if (identitiesValid && optionsValid && namesUnique) pairs.associate { it[0] to it[1] } else null
}

private fun validTorTransportOptions(
    transport: String?,
    values: Map<String, String>,
): Boolean =
    when (transport) {
        RelayKindObfs4 -> validObfs4Certificate(values["cert"]) && values["iat-mode"] in setOf("0", "1", "2")
        RelayKindWebTunnel -> validTorWebTunnelUrl(values["url"])
        else -> true // Snowflake supplies defaults for omitted transport options.
    }

private fun validObfs4Certificate(encoded: String?): Boolean {
    if (encoded == null) return false
    val decoded = runCatching { Base64.getDecoder().decode("$encoded==") }.getOrNull()
    return decoded?.size == Obfs4CertificateBytes
}

private fun validTorBridgeEndpoint(endpoint: String?): Boolean {
    val address = runCatching { URI("tcp://${endpoint.orEmpty()}") }.getOrNull() ?: return false
    val authorityValid =
        !address.host.isNullOrBlank() && address.port in 1..TorBridgeMaxPort && address.rawUserInfo == null
    val undecorated = address.rawPath.isNullOrEmpty() && address.rawQuery == null && address.rawFragment == null
    return authorityValid && undecorated
}

private fun validTorWebTunnelUrl(rawUrl: String?): Boolean {
    val url = runCatching { URI(rawUrl.orEmpty()) }.getOrNull() ?: return false
    val portValid = url.port == -1 || url.port in 1..TorBridgeMaxPort
    val originValid = url.scheme.equals("https", ignoreCase = true) && !url.host.isNullOrBlank() && portValid
    val secretPathPresent = !url.rawPath.isNullOrEmpty() && url.rawPath != "/"
    return originValid && secretPathPresent
}

private fun torBridgeIdentityType(value: String): String? =
    if (value.matches(Regex("\\$?[0-9A-Fa-f]{40}"))) "rsa" else torBridgeBase64IdentityType(value)

private fun torBridgeBase64IdentityType(value: String): String? {
    val type = if (':' in value) value.substringBefore(':').lowercase() else "ed25519"
    val expectedBytes =
        when (type) {
            "rsa" -> TorRsaIdentityBytes
            "ed25519" -> TorEd25519IdentityBytes
            else -> null
        }
    val encoded = value.substringAfter(':')
    val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    return type.takeIf {
        decoded != null && decoded.size == expectedBytes &&
            Base64.getEncoder().withoutPadding().encodeToString(decoded) == encoded
    }
}
