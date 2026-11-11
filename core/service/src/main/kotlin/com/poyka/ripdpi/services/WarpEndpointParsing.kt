package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.data.WarpEndpointCacheEntry

internal const val MaxWarpEndpointPort = 65_535
private const val MaxWarpEndpointHostLength = 253
private const val MaxWarpEndpointLabelLength = 63

internal fun parseWarpEndpoint(
    host: String,
    ipv4: String?,
    ipv6: String?,
    source: String,
): WarpEndpointCacheEntry =
    try {
        val authority = host.trim()
        val separator = if (authority.startsWith('[')) authority.lastIndexOf("]:") else authority.lastIndexOf(':')
        require(separator > 0) { "WARP endpoint authority is invalid" }
        val address =
            if (authority.startsWith(
                    '[',
                )
            ) {
                authority.substring(1, separator)
            } else {
                authority.substring(0, separator)
            }
        require(address.isWarpEndpointHost() && (':' !in address || authority.startsWith('['))) {
            "WARP endpoint host is invalid"
        }
        val portText = authority.substring(separator + if (authority.startsWith('[')) 2 else 1)
        require(portText.isNotEmpty() && portText.all { it in '0'..'9' }) { "WARP endpoint port is invalid" }
        val port = portText.toIntOrNull()
        require(port != null && port in 1..MaxWarpEndpointPort) { "WARP endpoint port is invalid" }
        WarpEndpointCacheEntry(
            networkScopeKey = "",
            host = address,
            ipv4 = normalizeWarpNumericEndpoint(ipv4, ipv6 = false),
            ipv6 = normalizeWarpNumericEndpoint(ipv6, ipv6 = true),
            port = port,
            source = source,
        )
    } catch (_: IllegalArgumentException) {
        throw WarpProvisioningException.MalformedResponse("WARP provisioning returned an invalid endpoint")
    }

internal fun normalizeWarpNumericEndpoint(
    value: String?,
    ipv6: Boolean,
): String? {
    val literal = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val numeric = NumericIp.parseAddress(literal)
    return if (numeric != null) {
        require(numeric.isIPv6 == ipv6) { "WARP endpoint address family is invalid" }
        literal
    } else {
        val separator = if (ipv6) literal.lastIndexOf("]:") else literal.lastIndexOf(':')
        require(separator > 0 && (!ipv6 || literal.startsWith('['))) { "WARP endpoint numeric address is invalid" }
        val address = if (ipv6) literal.substring(1, separator) else literal.substring(0, separator)
        val portText = literal.substring(separator + if (ipv6) 2 else 1)
        val port = portText.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()
        require(port != null && port in 0..MaxWarpEndpointPort) { "WARP endpoint embedded port is invalid" }
        require(NumericIp.parseAddress(address)?.isIPv6 == ipv6) { "WARP endpoint numeric address is invalid" }
        address
    }
}

internal fun String.isWarpEndpointHost(): Boolean =
    when {
        NumericIp.parseAddress(this) != null -> {
            true
        }

        all { it in '0'..'9' || it == '.' } -> {
            false
        }

        else -> {
            length <= MaxWarpEndpointHostLength &&
                removeSuffix(".").split('.').all { label ->
                    label.length in 1..MaxWarpEndpointLabelLength && label.first() != '-' && label.last() != '-' &&
                        label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
                }
        }
    }
