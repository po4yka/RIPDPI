package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgProfile
import com.poyka.ripdpi.data.wireguard.toWgQuickConf

val AwgActivationRequest.isConfExportable: Boolean
    get() =
        privateKey.isNotBlank() &&
            carrier == AwgActivationRequest.CARRIER_UDP &&
            runCatching { requireRuntimeReady() }.isSuccess

fun AwgActivationRequest.toWgQuickConf(): String {
    require(isConfExportable) { "AmneziaWG server cannot be written to a .conf" }
    return AmneziaWgProfile(
        name = "",
        host = endpointHost,
        port = endpointPort,
        privateKey = privateKey,
        publicKey = peerPublicKey,
        presharedKey = presharedKey.ifEmpty { null },
        allowedIps = allowedIps,
        dns = dnsServers,
        mtu = mtu,
        awg = obfuscation.toConfParameters(),
        address = listOf(interfaceAddressV4, interfaceAddressV6).filter(String::isNotEmpty),
        persistentKeepalive = persistentKeepalive.takeIf { it > 0 },
        persistentKeepaliveMax = persistentKeepaliveMax.takeIf { it > 0 },
    ).toWgQuickConf()
}

private fun AwgActivationObfuscation.toConfParameters(): AmneziaWgParameters =
    if (this == AwgActivationObfuscation(wireProfile = wireProfile)) {
        AmneziaWgParameters()
    } else {
        AmneziaWgParameters(
            wireProfile = wireProfile,
            jc = jc,
            jmin = jmin,
            jmax = jmax,
            s1 = s1,
            s2 = s2,
            s3 = s3.takeIf { it != 0 },
            s4 = s4.takeIf { it != 0 },
            h1 = h1,
            h2 = h2,
            h3 = h3,
            h4 = h4,
            i1 = i1.ifEmpty { null },
            i2 = i2.ifEmpty { null },
            i3 = i3.ifEmpty { null },
            i4 = i4.ifEmpty { null },
            i5 = i5.ifEmpty { null },
            v3 = v3,
        )
    }
