package com.poyka.ripdpi.data.wireguard

/**
 * Minimal WireGuard `.conf` INI parser with the AmneziaWG extension.
 *
 * The `.conf` format is the standard WireGuard INI: `[Interface]` / `[Peer]`
 * section headers followed by `Key = Value` pairs. AmneziaWG adds obfuscation
 * keys (`Jc`, `Jmin`, `Jmax`, `S1`..`S4`, `H1`..`H4`, `I1`..`I5`) on the
 * `[Interface]` block.
 *
 * - A `.conf` with **no** AmneziaWG keys parses to a [WireGuardConfig].
 * - A `.conf` with **any** AmneziaWG key parses to an [AmneziaWgConfig].
 *
 * The key-recognition switch and the `Integer.parseUnsignedInt` / hex parse
 * rules are ported from amnezia-vpn/amneziawg-android
 * (`tunnel/.../config/Interface.java`, Apache-2.0).
 *
 * Unknown keys are ignored (not a hard error). Structurally malformed input —
 * a non-comment line with no `=`, a `Key = Value` pair before any section
 * header, an empty file, or a field that fails validation — throws
 * [IllegalArgumentException].
 */
object WireGuardConfParser {
    private const val FOUR_BYTE_UNSIGNED_MAX = 0xFFFF_FFFFL
    private val HEX_REGEX = Regex("[0-9a-fA-F]+")

    private enum class Section { NONE, INTERFACE, PEER }

    /** Mutable accumulator for the `[Interface]` block while scanning lines. */
    private class InterfaceBuilder {
        var privateKey: String? = null
        var address: List<String> = emptyList()
        var dns: List<String> = emptyList()
        var mtu: Int? = null
        var listenPort: Int? = null
        val awg = AmneziaWgBuilder()

        fun build(): WireGuardInterface =
            WireGuardInterface(
                privateKey = requireNotNull(privateKey) { "WireGuard [Interface] is missing PrivateKey" },
                address = address,
                dns = dns,
                mtu = mtu,
                listenPort = listenPort,
            )
    }

    /** Mutable accumulator for the AmneziaWG obfuscation keys. */
    private class AmneziaWgBuilder {
        var jc: Int? = null
        var jmin: Int? = null
        var jmax: Int? = null
        var s1: Int? = null
        var s2: Int? = null
        var s3: Int? = null
        var s4: Int? = null
        var h1: Long? = null
        var h2: Long? = null
        var h3: Long? = null
        var h4: Long? = null
        var i1: String? = null
        var i2: String? = null
        var i3: String? = null
        var i4: String? = null
        var i5: String? = null

        fun build(): AmneziaWgParameters =
            AmneziaWgParameters(
                jc = jc,
                jmin = jmin,
                jmax = jmax,
                s1 = s1,
                s2 = s2,
                s3 = s3,
                s4 = s4,
                h1 = h1,
                h2 = h2,
                h3 = h3,
                h4 = h4,
                i1 = i1,
                i2 = i2,
                i3 = i3,
                i4 = i4,
                i5 = i5,
            )
    }

    /** Mutable accumulator for a single `[Peer]` block. */
    private class PeerBuilder {
        var publicKey: String? = null
        var presharedKey: String? = null
        var allowedIps: List<String> = emptyList()
        var endpoint: String? = null
        var persistentKeepalive: Int? = null

        fun build(): WireGuardPeer =
            WireGuardPeer(
                publicKey = requireNotNull(publicKey) { "WireGuard [Peer] is missing PublicKey" },
                presharedKey = presharedKey,
                allowedIps = allowedIps,
                endpoint = endpoint,
                persistentKeepalive = persistentKeepalive,
            )
    }

    /**
     * Parses [conf] and returns either a [WireGuardConfig] or an
     * [AmneziaWgConfig] depending on whether any AmneziaWG key is present.
     *
     * @throws IllegalArgumentException on structurally malformed input or a
     *     field that fails validation.
     */
    fun parse(conf: String): WireGuardConfModel {
        val interfaceBuilder = InterfaceBuilder()
        var interfaceSeen = false
        val peers = mutableListOf<WireGuardPeer>()
        var section = Section.NONE
        var currentPeer: PeerBuilder? = null

        fun flushPeer() {
            currentPeer?.let { peers.add(it.build()) }
            currentPeer = null
        }

        for (rawLine in conf.lineSequence()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[") && line.endsWith("]")) {
                section =
                    when (line.substring(1, line.length - 1).trim().lowercase()) {
                        "interface" -> {
                            interfaceSeen = true
                            Section.INTERFACE
                        }

                        "peer" -> {
                            flushPeer()
                            currentPeer = PeerBuilder()
                            Section.PEER
                        }

                        else -> {
                            throw IllegalArgumentException("Unknown WireGuard section header: $line")
                        }
                    }
            } else {
                val separator = line.indexOf('=')
                require(separator >= 0) { "Malformed WireGuard config line (no '='): $line" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(key.isNotEmpty()) { "Malformed WireGuard config line (empty key): $line" }

                when (section) {
                    Section.INTERFACE -> {
                        applyInterfaceKey(interfaceBuilder, key, value)
                    }

                    Section.PEER -> {
                        applyPeerKey(
                            requireNotNull(currentPeer) { "WireGuard [Peer] key '$key' before [Peer] header" },
                            key,
                            value,
                        )
                    }

                    Section.NONE -> {
                        throw IllegalArgumentException("WireGuard config key '$key' before any section header")
                    }
                }
            }
        }
        flushPeer()

        require(interfaceSeen) { "WireGuard config has no [Interface] section" }

        val interfaceSection = interfaceBuilder.build()
        val awg = interfaceBuilder.awg.build()
        return if (awg.isEmpty) {
            WireGuardConfig(interfaceSection = interfaceSection, peers = peers)
        } else {
            awg.requireArm64Safe()
            AmneziaWgConfig(interfaceSection = interfaceSection, awg = awg, peers = peers)
        }
    }

    // Flat dispatch over the fixed WireGuard + AmneziaWG interface key set; the branch
    // count is inherent to the format, not accidental complexity.
    @Suppress("CyclomaticComplexMethod")
    private fun applyInterfaceKey(
        builder: InterfaceBuilder,
        key: String,
        value: String,
    ) {
        // Lower-case the key before dispatch, mirroring amneziawg-android's Interface.parse switch.
        when (key.lowercase()) {
            "privatekey" -> builder.privateKey = value
            "address" -> builder.address = splitList(value)
            "dns" -> builder.dns = splitList(value)
            "mtu" -> builder.mtu = parseNonNegativeInt(key, value)
            "listenport" -> builder.listenPort = parseNonNegativeInt(key, value)
            "jc" -> builder.awg.jc = parseNonNegativeInt(key, value)
            "jmin" -> builder.awg.jmin = parseNonNegativeInt(key, value)
            "jmax" -> builder.awg.jmax = parseNonNegativeInt(key, value)
            "s1" -> builder.awg.s1 = parseNonNegativeInt(key, value)
            "s2" -> builder.awg.s2 = parseNonNegativeInt(key, value)
            "s3" -> builder.awg.s3 = parseNonNegativeInt(key, value)
            "s4" -> builder.awg.s4 = parseNonNegativeInt(key, value)
            "h1" -> builder.awg.h1 = parseFourByteUnsigned(key, value)
            "h2" -> builder.awg.h2 = parseFourByteUnsigned(key, value)
            "h3" -> builder.awg.h3 = parseFourByteUnsigned(key, value)
            "h4" -> builder.awg.h4 = parseFourByteUnsigned(key, value)
            "i1" -> builder.awg.i1 = parseHexString(key, value)
            "i2" -> builder.awg.i2 = parseHexString(key, value)
            "i3" -> builder.awg.i3 = parseHexString(key, value)
            "i4" -> builder.awg.i4 = parseHexString(key, value)
            "i5" -> builder.awg.i5 = parseHexString(key, value)
            else -> Unit // Unknown key: ignored, not a hard error.
        }
    }

    private fun applyPeerKey(
        builder: PeerBuilder,
        key: String,
        value: String,
    ) {
        when (key.lowercase()) {
            "publickey" -> builder.publicKey = value
            "presharedkey" -> builder.presharedKey = value
            "allowedips" -> builder.allowedIps = splitList(value)
            "endpoint" -> builder.endpoint = value
            "persistentkeepalive" -> builder.persistentKeepalive = parseNonNegativeInt(key, value)
            else -> Unit // Unknown key: ignored, not a hard error.
        }
    }

    private fun stripComment(line: String): String {
        val hash = line.indexOf('#')
        return if (hash >= 0) line.substring(0, hash) else line
    }

    private fun splitList(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseNonNegativeInt(
        key: String,
        value: String,
    ): Int {
        val parsed =
            value.toIntOrNull()
                ?: throw IllegalArgumentException("WireGuard config key '$key' expects an integer, got: $value")
        require(parsed >= 0) { "WireGuard config key '$key' must be non-negative, got: $value" }
        return parsed
    }

    private fun parseFourByteUnsigned(
        key: String,
        value: String,
    ): Long {
        val parsed =
            value.toLongOrNull()
                ?: throw IllegalArgumentException(
                    "AmneziaWG key '$key' expects an unsigned 32-bit integer, got: $value",
                )
        require(parsed in 0L..FOUR_BYTE_UNSIGNED_MAX) {
            "AmneziaWG key '$key' must be a 4-byte unsigned value (0..$FOUR_BYTE_UNSIGNED_MAX), got: $value"
        }
        return parsed
    }

    private fun parseHexString(
        key: String,
        value: String,
    ): String {
        require(value.isNotEmpty() && HEX_REGEX.matches(value)) {
            "AmneziaWG key '$key' expects a hex string, got: $value"
        }
        return value.lowercase()
    }
}
