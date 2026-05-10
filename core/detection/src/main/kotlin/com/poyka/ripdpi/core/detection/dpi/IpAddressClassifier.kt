package com.poyka.ripdpi.core.detection.dpi

import java.net.InetAddress
import java.net.UnknownHostException

enum class IpAddressType {
    FAKE_IP,
    ISP_STUB,
    LOCAL,
    PUBLIC,
}

object IpAddressClassifier {
    fun classify(ip: String): IpAddressType {
        val address = ip.parseIpLiteralOrNull() ?: return IpAddressType.LOCAL
        return when {
            address.isInSubnet("198.18.0.0", 15) -> IpAddressType.FAKE_IP
            address.isInSubnet("100.64.0.0", 10) -> IpAddressType.ISP_STUB
            address.isLocalAddress() -> IpAddressType.LOCAL
            else -> IpAddressType.PUBLIC
        }
    }

    fun isLocal(ip: String): Boolean = classify(ip) == IpAddressType.LOCAL
}

private fun String.parseIpLiteralOrNull(): InetAddress? {
    val value = trim()
    if (value.isEmpty() || !value.looksLikeIpLiteral()) return null
    return try {
        InetAddress.getByName(value)
    } catch (_: UnknownHostException) {
        null
    }
}

private fun String.looksLikeIpLiteral(): Boolean {
    val hasIpv4Shape = matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    val hasIpv6Shape = ':' in this && all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    return hasIpv4Shape || hasIpv6Shape
}

private fun InetAddress.isLocalAddress(): Boolean =
    isAnyLocalAddress ||
        isLoopbackAddress ||
        isLinkLocalAddress ||
        isSiteLocalAddress ||
        isInSubnet("0.0.0.0", 32)

private fun InetAddress.isInSubnet(
    network: String,
    prefixLength: Int,
): Boolean {
    val networkAddress = InetAddress.getByName(network)
    val addressBytes = address
    val networkBytes = networkAddress.address
    if (addressBytes.size != networkBytes.size) return false
    return addressBytes.matchesPrefix(networkBytes, prefixLength)
}

private fun ByteArray.matchesPrefix(
    networkBytes: ByteArray,
    prefixLength: Int,
): Boolean {
    val fullBytes = prefixLength / Byte.SIZE_BITS
    val remainingBits = prefixLength % Byte.SIZE_BITS
    for (index in 0 until fullBytes) {
        if (this[index] != networkBytes[index]) return false
    }
    if (remainingBits == 0) return true
    val mask = (0xFF shl (Byte.SIZE_BITS - remainingBits)) and 0xFF
    return (this[fullBytes].toInt() and mask) == (networkBytes[fullBytes].toInt() and mask)
}
