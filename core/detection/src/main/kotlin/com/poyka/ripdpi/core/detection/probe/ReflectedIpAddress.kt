package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.core.detection.dpi.IpAddressClassifier
import com.poyka.ripdpi.core.detection.dpi.IpAddressType
import com.poyka.ripdpi.data.NumericIp

internal fun canonicalIpOrNull(value: String): String? = parseLiteral(value)?.toCanonicalString()

internal fun publicIpOrNull(value: String): String? =
    parseLiteral(value)?.let { parsed ->
        val canonical = parsed.toCanonicalString()
        val effective = if (parsed.isIPv6 && parsed.toIPv6().isIPv4Mapped) parsed.toIPv4() else parsed
        val peer = effective.toInetAddress()
        val firstByte = peer.address.first().toInt() and ByteMask
        val localIpv4 = effective.isIPv4 && (firstByte == 0 || firstByte >= MulticastPrefix)
        val uniqueLocalIpv6 = effective.isIPv6 && firstByte and UniqueLocalMask == UniqueLocalPrefix
        canonical.takeIf {
            IpAddressClassifier.classify(canonical) == IpAddressType.PUBLIC &&
                !peer.isMulticastAddress && !localIpv4 && !uniqueLocalIpv6
        }
    }

private fun parseLiteral(value: String) = NumericIp.parseAddress(value.trim().removeSurrounding("[", "]"))

private const val ByteMask = 0xff
private const val MulticastPrefix = 224
private const val UniqueLocalMask = 0xfe
private const val UniqueLocalPrefix = 0xfc
