package com.poyka.ripdpi.data.wireguard

/**
 * Renders this profile as wg-quick `.conf` text that [WireGuardConfParser.parse] reads back to the
 * same fields: identity, `Address`, `DNS`, `MTU`, every AmneziaWG 1.x/2.0 key, the AWG 3.x keys, and
 * a `PersistentKeepalive` value or `lo-hi` range. Unset fields are omitted. The display [name] is not
 * written. The text contains key material; do not log it.
 *
 * @throws IllegalArgumentException when a value contains a line break or a `#`/`;` comment character,
 *     which would let one field inject another `.conf` line.
 */
fun AmneziaWgProfile.toWgQuickConf(): String {
    val interfaceLines =
        buildList {
            add("PrivateKey" to privateKey)
            if (address.isNotEmpty()) add("Address" to address.joinToString(", "))
            if (dns.isNotEmpty()) add("DNS" to dns.joinToString(", "))
            mtu?.let { add("MTU" to it.toString()) }
            addAll(awg.wgQuickLines())
        }
    val peerLines =
        buildList {
            add("PublicKey" to publicKey)
            presharedKey?.let { add("PresharedKey" to it) }
            if (allowedIps.isNotEmpty()) add("AllowedIPs" to allowedIps.joinToString(", "))
            add("Endpoint" to (if (':' in host) "[$host]:$port" else "$host:$port"))
            persistentKeepalive?.let { low ->
                add("PersistentKeepalive" to (persistentKeepaliveMax?.let { "$low-$it" } ?: "$low"))
            }
        }
    return buildString {
        appendSection("Interface", interfaceLines)
        append('\n')
        appendSection("Peer", peerLines)
    }
}

private fun AmneziaWgParameters.wgQuickLines(): List<Pair<String, String>> =
    buildList {
        listOf("Jc" to jc, "Jmin" to jmin, "Jmax" to jmax, "S1" to s1, "S2" to s2, "S3" to s3, "S4" to s4)
            .forEach { (key, value) -> value?.let { add(key to it.toString()) } }
        listOf("H1" to h1, "H2" to h2, "H3" to h3, "H4" to h4)
            .plus(listOf("I1" to i1, "I2" to i2, "I3" to i3, "I4" to i4, "I5" to i5))
            .forEach { (key, value) -> value?.let { add(key to it) } }
        if (v3.headerProtectionKey.isSet) add("HeaderProtectionKey" to v3.headerProtectionKey.value)
        v3.ranges.filter { it.second.isNotEmpty() }.forEach(::add)
        if (v3.randomTrailers) add("RandomTrailers" to "on")
        if (v3.disableCookies) add("DisableCookies" to "on")
    }

private fun StringBuilder.appendSection(
    name: String,
    lines: List<Pair<String, String>>,
) {
    append('[').append(name).append("]\n")
    lines.forEach { (key, value) ->
        require(value.none { it == '\n' || it == '\r' || it == '#' || it == ';' }) {
            "AmneziaWG $key cannot be written to a .conf line"
        }
        append(key).append(" = ").append(value).append('\n')
    }
}
