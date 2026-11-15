package com.poyka.ripdpi.data.wireguard

/** An unsupported option in a recognized configuration section. */
class WireGuardConfUnknownKeyException internal constructor(
    val section: String,
) : IllegalArgumentException("Unsupported WireGuard option in [$section]")

/**
 * Minimal WireGuard `.conf` INI parser with the AmneziaWG extension.
 *
 * The `.conf` format is the standard WireGuard INI: `[Interface]` / `[Peer]`
 * section headers followed by `Key = Value` pairs. AmneziaWG adds obfuscation
 * keys (`Jc`, `Jmin`, `Jmax`, `S1`..`S4`, `H1`..`H4`, `I1`..`I5`) on the
 * `[Interface]` block, and AWG 3.x adds `HeaderProtectionKey`,
 * `ContentPaddingAddition`, `RekeyAfterTime`, `RekeyTimeout`, `RejectAfterTime`,
 * `KeepaliveTimeout`, `MaxHandshakeAttempts`, `RandomTrailers`, `DisableCookies`,
 * and `[Peer] PersistentKeepalive = lo-hi` ranges.
 *
 * A supported AmneziaWG interface key or a ranged peer keepalive selects [AmneziaWgConfig],
 * including syntax that selects default values. Other configs parse as [WireGuardConfig].
 *
 * The key-recognition switch and the `Integer.parseUnsignedInt` / hex parse
 * rules are ported from amnezia-vpn/amneziawg-android
 * (`tunnel/.../config/Interface.java`, Apache-2.0).
 *
 * `#` and `;` start a comment. The wg-quick host keys (`Table`, `PreUp`, `PostUp`,
 * `PreDown`, `PostDown`, `SaveConfig`, `FwMark`), `IncludedApplications`,
 * `ExcludedApplications`, and the AWG 1.5 `J1`..`J3`/`Itime` keys are ignored; any
 * other unknown key throws [WireGuardConfUnknownKeyException]. Structurally malformed input — a
 * non-comment line with no `=`, a `Key = Value` pair before any section header,
 * an empty file, or a field that fails validation — throws
 * [IllegalArgumentException].
 */
object WireGuardConfParser {
    /**
     * `[Interface]` keys that real exports carry but that do not change the RIPDPI data plane:
     * wg-quick host keys, the per-app split-tunnel keys of wireguard-android and
     * amneziawg-android, and the retired AWG 1.5 `J1`..`J3`/`Itime` keys.
     */
    private val IgnoredInterfaceKeys =
        listOf(
            "Table",
            "PreUp",
            "PostUp",
            "PreDown",
            "PostDown",
            "SaveConfig",
            "FwMark",
            "IncludedApplications",
            "ExcludedApplications",
            "J1",
            "J2",
            "J3",
            "Itime",
        ).associateBy { it.lowercase() }

    private const val MaxListenPort = 65_535

    private enum class Section { NONE, INTERFACE, PEER }

    /** Recognizes the interface header so incomplete configurations reach the editor. */
    fun looksLikeConf(conf: String): Boolean = configLines(conf).any { sectionName(it) == "interface" }

    /** Mutable accumulator for the `[Interface]` block while scanning lines. */
    private class InterfaceBuilder {
        var privateKey: String? = null
        val address = mutableListOf<String>()
        val dns = mutableListOf<String>()
        var mtu: Int? = null
        var listenPort: Int? = null
        val ignoredOptions = linkedSetOf<String>()
        val awg = AmneziaWgBuilder()

        fun build(): WireGuardInterface =
            WireGuardInterface(
                privateKey = requireNotNull(privateKey) { "WireGuard [Interface] is missing PrivateKey" },
                address = address.toList(),
                dns = dns.toList(),
                mtu = mtu,
                listenPort = listenPort,
                ignoredOptions = ignoredOptions.toList(),
            )
    }

    /** Mutable accumulator for the AmneziaWG obfuscation keys. */
    private class AmneziaWgBuilder {
        var specified = false
        var jc: Int? = null
        var jmin: Int? = null
        var jmax: Int? = null
        var s1: Int? = null
        var s2: Int? = null
        var s3: Int? = null
        var s4: Int? = null
        var h1: String? = null
        var h2: String? = null
        var h3: String? = null
        var h4: String? = null
        var i1: String? = null
        var i2: String? = null
        var i3: String? = null
        var i4: String? = null
        var i5: String? = null
        var v3 = AmneziaWgV3Parameters()

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
                v3 = v3,
            )
    }

    /** Mutable accumulator for a single `[Peer]` block. */
    private class PeerBuilder {
        var publicKey: String? = null
        var presharedKey: String? = null
        val allowedIps = mutableListOf<String>()
        var allowedIpsSpecified = false
        var endpoint: String? = null
        var persistentKeepalive: Int? = null
        var persistentKeepaliveMax: Int? = null
        var rangedKeepaliveSpecified = false

        fun build(): WireGuardPeer =
            WireGuardPeer(
                publicKey = requireNotNull(publicKey) { "WireGuard [Peer] is missing PublicKey" },
                presharedKey = presharedKey,
                allowedIps = allowedIps.toList(),
                allowedIpsSpecified = allowedIpsSpecified,
                endpoint = endpoint,
                persistentKeepalive = persistentKeepalive,
                persistentKeepaliveMax = persistentKeepaliveMax,
            )
    }

    /** Identifies a tunnel config for import routing. [parse] still validates its contents. */
    fun hasInterfaceAndPeerSections(conf: String): Boolean {
        var hasInterface = false
        var hasPeer = false
        for (line in configLines(conf)) {
            when (sectionName(line)) {
                "interface" -> hasInterface = true
                "peer" -> hasPeer = true
            }
            if (hasInterface && hasPeer) return true
        }
        return false
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
        var rangedKeepaliveSpecified = false

        fun flushPeer() {
            currentPeer?.let { peer ->
                peers.add(peer.build())
                rangedKeepaliveSpecified = rangedKeepaliveSpecified || peer.rangedKeepaliveSpecified
            }
            currentPeer = null
        }

        for (line in configLines(conf)) {
            if (line.isEmpty()) continue

            val header = sectionName(line)
            if (header != null) {
                section =
                    when (header) {
                        "interface" -> {
                            require(!interfaceSeen) { "WireGuard config has a duplicate [Interface] section" }
                            interfaceSeen = true
                            Section.INTERFACE
                        }

                        "peer" -> {
                            flushPeer()
                            currentPeer = PeerBuilder()
                            Section.PEER
                        }

                        else -> {
                            throw IllegalArgumentException("Unknown WireGuard section header")
                        }
                    }
            } else {
                val separator = line.indexOf('=')
                require(separator >= 0) { "Malformed WireGuard config line (no '=')" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(key.isNotEmpty()) { "Malformed WireGuard config line (empty key)" }

                when (section) {
                    Section.INTERFACE -> {
                        applyInterfaceKey(interfaceBuilder, key, value)
                    }

                    Section.PEER -> {
                        applyPeerKey(
                            requireNotNull(currentPeer) { "WireGuard config has no current [Peer] section" },
                            key,
                            value,
                        )
                    }

                    Section.NONE -> {
                        throw IllegalArgumentException("WireGuard config option before any section header")
                    }
                }
            }
        }
        flushPeer()

        require(interfaceSeen) { "WireGuard config has no [Interface] section" }

        val interfaceSection = interfaceBuilder.build()
        val awg = interfaceBuilder.awg.build()
        return if (!interfaceBuilder.awg.specified && !rangedKeepaliveSpecified) {
            WireGuardConfig(interfaceSection = interfaceSection, peers = peers)
        } else {
            awg.requireSupportedProfile()
            AmneziaWgConfig(interfaceSection = interfaceSection, awg = awg, peers = peers)
        }
    }

    private fun applyInterfaceKey(
        builder: InterfaceBuilder,
        key: String,
        value: String,
    ) {
        when (val normalizedKey = key.lowercase()) {
            "privatekey" -> {
                builder.privateKey = value
            }

            "address" -> {
                builder.address += splitList(value)
            }

            "dns" -> {
                builder.dns += splitList(value)
            }

            "mtu" -> {
                builder.mtu = parseNonNegativeInt(key, value)
            }

            "listenport" -> {
                val port = parseNonNegativeInt("ListenPort", value)
                require(port <= MaxListenPort) { "WireGuard ListenPort must be a 16-bit port" }
                builder.listenPort = port
                builder.ignoredOptions += "ListenPort"
            }

            in IgnoredInterfaceKeys -> {
                builder.ignoredOptions += IgnoredInterfaceKeys.getValue(normalizedKey)
            }

            else -> {
                applyAmneziaWgKey(builder.awg, normalizedKey, value)
            }
        }
    }

    private fun applyAmneziaWgKey(
        builder: AmneziaWgBuilder,
        key: String,
        value: String,
    ) {
        when (key) {
            "jc" -> builder.jc = parseNonNegativeInt(key, value)
            "jmin" -> builder.jmin = parseNonNegativeInt(key, value)
            "jmax" -> builder.jmax = parseNonNegativeInt(key, value)
            "s1" -> builder.s1 = parseNonNegativeInt(key, value)
            "s2" -> builder.s2 = parseNonNegativeInt(key, value)
            "s3" -> builder.s3 = parseNonNegativeInt(key, value)
            "s4" -> builder.s4 = parseNonNegativeInt(key, value)
            "h1" -> builder.h1 = parseHeader(key, value)
            "h2" -> builder.h2 = parseHeader(key, value)
            "h3" -> builder.h3 = parseHeader(key, value)
            "h4" -> builder.h4 = parseHeader(key, value)
            "i1" -> builder.i1 = parseSignature(key, value)
            "i2" -> builder.i2 = parseSignature(key, value)
            "i3" -> builder.i3 = parseSignature(key, value)
            "i4" -> builder.i4 = parseSignature(key, value)
            "i5" -> builder.i5 = parseSignature(key, value)
            else -> builder.v3 = builder.v3.withConfKey(key, value)
        }
        builder.specified = true
    }

    private fun applyPeerKey(
        builder: PeerBuilder,
        key: String,
        value: String,
    ) {
        when (key.lowercase()) {
            "publickey" -> {
                builder.publicKey = value
            }

            "presharedkey" -> {
                builder.presharedKey = value
            }

            "allowedips" -> {
                builder.allowedIps += splitList(value)
                builder.allowedIpsSpecified = true
            }

            "endpoint" -> {
                builder.endpoint = value
            }

            "persistentkeepalive" -> {
                // wg-quick writes `off` for "no persistent keepalive".
                val range = if (value.equals("off", ignoreCase = true)) null else parseKeepaliveRange(value)
                builder.persistentKeepalive = range?.first
                builder.persistentKeepaliveMax = range?.second
                builder.rangedKeepaliveSpecified = builder.rangedKeepaliveSpecified || '-' in value
            }

            else -> {
                throw WireGuardConfUnknownKeyException("Peer")
            }
        }
    }

    private fun splitList(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseNonNegativeInt(
        key: String,
        value: String,
    ): Int {
        val parsed =
            value.toIntOrNull()
                ?: throw IllegalArgumentException("WireGuard config key '$key' expects an integer")
        require(parsed >= 0) { "WireGuard config key '$key' must be non-negative" }
        return parsed
    }

    private fun parseHeader(
        key: String,
        value: String,
    ): String {
        require(AmneziaWgWireProfile.headerIsValid(value, key.last().digitToInt())) { "Invalid AmneziaWG $key" }
        return value
    }

    private fun parseSignature(
        key: String,
        value: String,
    ): String {
        require(AmneziaWgWireProfile.signatureSize(value) != null) { "Invalid AmneziaWG $key" }
        return value
    }
}

private fun sectionName(line: String): String? =
    line
        .takeIf { it.startsWith('[') && it.endsWith(']') }
        ?.substring(1, line.lastIndex)
        ?.trim()
        ?.lowercase()

private fun configLines(conf: String): Sequence<String> =
    conf.trimStart().removePrefix("\uFEFF").lineSequence().map { line ->
        val comment = line.indexOfFirst { it == '#' || it == ';' }
        (if (comment >= 0) line.substring(0, comment) else line).trim()
    }
