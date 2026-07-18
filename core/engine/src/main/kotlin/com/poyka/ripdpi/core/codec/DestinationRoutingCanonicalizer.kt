package com.poyka.ripdpi.core.codec

import java.net.Inet6Address
import java.net.InetAddress

/** Canonical syntax shared by destination-routing wire validation and compiler output. */
internal object DestinationRoutingCanonicalizer {
    fun isCanonicalHost(value: String): Boolean =
        value.length in 1..MaxTokenLength &&
            value.all { it.isLowerAsciiLetterOrDigit() || it == '-' || it == '.' } &&
            !value.startsWith('.') &&
            !value.endsWith('.') &&
            !value.contains("..") &&
            value.split('.').all { label ->
                label.length in 1..MaxHostLabelLength &&
                    label.first().isLowerAsciiLetterOrDigit() &&
                    label.last().isLowerAsciiLetterOrDigit()
            }

    fun isCanonicalGeoToken(value: String): Boolean =
        value.length in 1..MaxTokenLength &&
            value.all { it.isLowerAsciiLetterOrDigit() || it == '-' || it == '_' }

    fun isCanonicalCidr(value: String): Boolean = canonicalCidr(value) == value

    private fun canonicalCidr(raw: String): String? =
        raw
            .split('/')
            .takeIf { it.size == CidrPartCount }
            ?.let { parts ->
                parts[1].toIntOrNull()?.let { prefix ->
                    if ('.' in parts[0] && ':' !in parts[0]) {
                        canonicalIpv4(parts[0], prefix)
                    } else {
                        canonicalIpv6(parts[0], prefix)
                    }
                }
            }

    private fun canonicalIpv4(
        rawAddress: String,
        prefix: Int,
    ): String? =
        rawAddress
            .split('.')
            .takeIf { it.size == Ipv4Octets && prefix in 0..Ipv4Bits }
            ?.takeIf { parts -> parts.all { it.isCanonicalIpv4Octet() } }
            ?.map(String::toInt)
            ?.takeIf { values -> values.all { it in MinOctet..MaxOctet } }
            ?.map(Int::toByte)
            ?.toByteArray()
            ?.also { clearHostBits(it, prefix) }
            ?.joinToString(".") { it.toUByte().toString() }
            ?.let { "$it/$prefix" }

    private fun canonicalIpv6(
        rawAddress: String,
        prefix: Int,
    ): String? =
        rawAddress
            .takeIf { ':' in it && '%' !in it && prefix in 0..Ipv6Bits }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() as? Inet6Address }
            ?.address
            ?.also { clearHostBits(it, prefix) }
            ?.let(::formatIpv6)
            ?.let { "$it/$prefix" }

    private fun formatIpv6(bytes: ByteArray): String =
        bytes.asList().chunked(Ipv6GroupBytes).joinToString(":") { pair ->
            val group = ((pair[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (pair[1].toInt() and ByteMask)
            group.toString(Ipv6Radix)
        }

    private fun String.isCanonicalIpv4Octet(): Boolean =
        isNotEmpty() && length <= MaxOctetDigits && all(Char::isDigit) && (length == 1 || !startsWith('0'))

    private fun Char.isLowerAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private fun clearHostBits(
        bytes: ByteArray,
        prefix: Int,
    ) {
        for (bit in prefix until bytes.size * Byte.SIZE_BITS) {
            val byteIndex = bit / Byte.SIZE_BITS
            val mask = 1 shl (Byte.SIZE_BITS - 1 - bit % Byte.SIZE_BITS)
            bytes[byteIndex] = (bytes[byteIndex].toInt() and mask.inv()).toByte()
        }
    }

    private const val MaxTokenLength = 253
    private const val MaxHostLabelLength = 63
    private const val CidrPartCount = 2
    private const val Ipv4Bits = 32
    private const val Ipv6Bits = 128
    private const val Ipv4Octets = 4
    private const val Ipv6GroupBytes = 2
    private const val Ipv6Radix = 16
    private const val MinOctet = 0
    private const val MaxOctet = 255
    private const val MaxOctetDigits = 3
    private const val ByteMask = 0xff
}
