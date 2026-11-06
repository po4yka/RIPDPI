package com.poyka.ripdpi.data

import inet.ipaddr.AddressStringParameters.RangeParameters
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import inet.ipaddr.IPAddressStringParameters

/** Numeric syntax shared by configuration, routing, and diagnostics. Never resolves hostnames. */
object NumericIp {
    private val literalOptions =
        IPAddressStringParameters
            .Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allowSingleSegment(false)
            .allowPrefix(false)
            .allowPrefixOnly(false)
            .allowMask(false)
            .allowWildcardedSeparator(false)
            .setRangeOptions(RangeParameters.NO_RANGE)
            .allow_inet_aton(false)
            .apply {
                getIPv4AddressParametersBuilder()
                    .allowLeadingZeros(false)
                    .allowUnlimitedLeadingZeros(false)
                    .allowBinary(false)
                getIPv6AddressParametersBuilder()
                    .allowZone(false)
                    .allowEmptyZone(false)
                    .allowBase85(false)
                    .allowBinary(false)
                    .allowUnlimitedLeadingZeros(false)
                    .getEmbeddedIPv4AddressParametersBuilder()
                    .allowLeadingZeros(false)
                    .allowUnlimitedLeadingZeros(false)
            }.toParams()

    fun parseAddress(value: String): IPAddress? =
        value
            .takeIf { it.length in 1..MaxLiteralLength && it.all { char -> char in LiteralCharacters } }
            ?.let { IPAddressString(it, literalOptions).address }

    fun parseCidr(value: String): NumericIpPrefix? {
        val parts = value.split('/')
        if (parts.size != CidrParts) return null
        val address = parseAddress(parts[0])
        val prefixText = parts[1]
        val validPrefix =
            prefixText.isNotEmpty() && prefixText.all { it in '0'..'9' } &&
                (prefixText.length == 1 || prefixText.first() != '0')
        val prefix = prefixText.takeIf { validPrefix }?.toIntOrNull()
        return if (address != null && prefix != null &&
            prefix in 0..address.bitCount
        ) {
            NumericIpPrefix(address, prefix)
        } else {
            null
        }
    }

    fun canonicalCidr(value: String): String? = parseCidr(value)?.toCanonicalString()

    private const val MaxLiteralLength = 45
    private const val CidrParts = 2
    private const val LiteralCharacters = "0123456789abcdefABCDEF:."
}

/** Keeps an interface host distinct from its containing network. */
class NumericIpPrefix internal constructor(
    val address: IPAddress,
    val prefixLength: Int,
) {
    val network: IPAddress = address.toPrefixBlock(prefixLength)
    val networkAddress: IPAddress get() = network.lower.withoutPrefixLength()

    fun contains(candidate: IPAddress): Boolean = address.isIPv4 == candidate.isIPv4 && network.contains(candidate)

    fun toCanonicalString(): String = "${networkAddress.toNormalizedString()}/$prefixLength"
}
