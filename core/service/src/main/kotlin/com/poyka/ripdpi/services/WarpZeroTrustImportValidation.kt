package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import java.util.Base64

private const val WarpWireGuardKeyBytes = 32

internal fun WarpZeroTrustImportRequest.validatedEndpoint(profileId: String): WarpEndpointCacheEntry {
    require(privateKey.isWarpImportKey()) { "Zero Trust private key is invalid" }
    require(publicKey.isWarpImportKey()) { "Zero Trust public key is invalid" }
    require(peerPublicKey.isWarpImportKey()) { "Zero Trust peer public key is invalid" }
    require(interfaceAddressV4.isWarpInterfaceAddress(ipv6 = false)) { "Zero Trust IPv4 interface address is invalid" }
    require(interfaceAddressV6 == null || interfaceAddressV6.isWarpInterfaceAddress(ipv6 = true)) {
        "Zero Trust IPv6 interface address is invalid"
    }
    val imported = requireNotNull(endpoint) { "Zero Trust endpoint is missing" }
    require(imported.port in 1..MaxWarpEndpointPort) { "Zero Trust endpoint port is invalid" }
    val ipv4 = normalizeWarpNumericEndpoint(imported.ipv4, ipv6 = false)
    val ipv6 = normalizeWarpNumericEndpoint(imported.ipv6, ipv6 = true)
    require(ipv4 == null || NumericIp.parseAddress(ipv4)?.isIPv4 == true) { "Zero Trust IPv4 endpoint is invalid" }
    require(ipv6 == null || NumericIp.parseAddress(ipv6)?.isIPv6 == true) { "Zero Trust IPv6 endpoint is invalid" }
    val host = imported.host?.trim()?.takeIf(String::isNotEmpty) ?: ipv4 ?: ipv6
    require(host != null && host.isWarpEndpointHost()) { "Zero Trust endpoint host is invalid" }
    return imported.copy(
        profileId = profileId,
        networkScopeKey = GlobalWarpEndpointScopeKey,
        host = host,
        ipv4 = ipv4,
        ipv6 = ipv6,
        source = "zero_trust_import",
        rttMs = null,
        updatedAtEpochMillis = System.currentTimeMillis(),
    )
}

private fun String?.isWarpImportKey(): Boolean {
    val decoded = this?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
    return decoded.size == WarpWireGuardKeyBytes && Base64.getEncoder().encodeToString(decoded) == this
}

private fun String?.isWarpInterfaceAddress(ipv6: Boolean): Boolean {
    val value = this ?: return false
    val address = if ('/' in value) NumericIp.parseCidr(value)?.address else NumericIp.parseAddress(value)
    return address != null && address.isIPv6 == ipv6
}
