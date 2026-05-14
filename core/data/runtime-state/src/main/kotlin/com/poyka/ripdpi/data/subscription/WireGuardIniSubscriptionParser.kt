package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.wireguard.WireGuardConfParser

/**
 * One WireGuard profile produced from a single `[Peer]` of a WireGuard `.conf`
 * INI payload. All peers parsed from the same `.conf` share the `[Interface]`
 * key material; they are distinguished by their peer endpoint.
 *
 * `ProxyProfile` (in `ProxyGroupStores.kt`) has no WireGuard variant and is
 * owned read-only by another file, so a WireGuard subscription profile is
 * modelled here as a standalone type, mirroring how
 * [com.poyka.ripdpi.data.wireguard.WireGuardConfig] is a standalone model.
 *
 * [allowedIps] is carried as a per-profile routing hint even though the current
 * runtime ignores it; it is kept for the future routing epic.
 */
data class WireGuardSubscriptionProfile(
    val displayName: String,
    val groupId: String,
    val server: String,
    val serverPort: Int,
    val interfacePrivateKey: String,
    val interfaceAddress: List<String>,
    val dns: List<String>,
    val mtu: Int?,
    val peerPublicKey: String,
    val peerPresharedKey: String?,
    val allowedIps: List<String>,
    val persistentKeepalive: Int?,
)

/** Outcome of a [WireGuardIniSubscriptionParser] run: profiles plus per-peer warnings. */
data class WireGuardIniSubscriptionResult(
    val profiles: List<WireGuardSubscriptionProfile>,
    val warnings: List<SubscriptionLineWarning>,
)

/**
 * Subscription-format parser for raw WireGuard `.conf` / INI payloads,
 * including WARP-compatible layouts.
 *
 * Detection marker is `[Interface]` header presence. The structural INI
 * scanning and per-key validation are delegated to the existing
 * [com.poyka.ripdpi.data.wireguard.WireGuardConfParser]; this parser does not
 * reimplement INI scanning. To get per-peer failure isolation out of an
 * all-or-nothing config parser, the payload is split into its `[Interface]`
 * block plus one block per `[Peer]`, and each `[Interface] + [Peer]` pair is
 * handed to [WireGuardConfParser] independently.
 *
 * Failure handling matches the other subscription parsers:
 * - A payload with no `[Interface]` header surfaces a typed
 *   [SubscriptionLineWarning] rather than throwing.
 * - A malformed `[Interface]` block fails the whole subscription with one
 *   typed warning — the interface key material is shared by every peer, so it
 *   cannot be salvaged per-peer.
 * - A malformed or incomplete `[Peer]` block (bad key, missing PublicKey,
 *   missing Endpoint) degrades to "skip and warn"; the other peers still
 *   produce profiles.
 */
object WireGuardIniSubscriptionParser {
    /** Detection marker, mirroring NekoBox's `text.contains("[Interface]")`. */
    fun looksLikeWireGuardIni(payload: String): Boolean = payload.contains("[Interface]")

    /**
     * Parses [payload] into a [WireGuardIniSubscriptionResult]. Every produced
     * profile is stamped with [groupId]. Never throws.
     */
    fun parse(
        payload: String,
        groupId: String,
    ): WireGuardIniSubscriptionResult {
        if (!looksLikeWireGuardIni(payload)) {
            return WireGuardIniSubscriptionResult(
                profiles = emptyList(),
                warnings =
                    listOf(
                        SubscriptionLineWarning(
                            lineNumber = 0,
                            line = payload.lineSequence().firstOrNull().orEmpty(),
                            reason = "payload has no [Interface] header; not a WireGuard INI config",
                        ),
                    ),
            )
        }

        val sections = splitSections(payload)
        if (sections.interfaceBlock == null) {
            return WireGuardIniSubscriptionResult(
                profiles = emptyList(),
                warnings =
                    listOf(
                        SubscriptionLineWarning(
                            lineNumber = 0,
                            line = "[Interface]",
                            reason = "malformed WireGuard INI config: could not isolate the [Interface] block",
                        ),
                    ),
            )
        }

        if (sections.peerBlocks.isEmpty()) {
            return WireGuardIniSubscriptionResult(
                profiles = emptyList(),
                warnings =
                    listOf(
                        SubscriptionLineWarning(
                            lineNumber = 0,
                            line = "[Interface]",
                            reason = "WireGuard INI config has an [Interface] but no [Peer] section",
                        ),
                    ),
            )
        }

        val profiles = mutableListOf<WireGuardSubscriptionProfile>()
        val warnings = mutableListOf<SubscriptionLineWarning>()

        sections.peerBlocks.forEachIndexed { index, peerBlock ->
            // Re-attach the shared [Interface] block to this one [Peer] block
            // and parse the pair through the existing config parser. A failure
            // here is isolated to this peer.
            val singlePeerConf = sections.interfaceBlock + "\n" + peerBlock
            val model =
                runCatching { WireGuardConfParser.parse(singlePeerConf) }
                    .getOrElse { error ->
                        warnings +=
                            SubscriptionLineWarning(
                                lineNumber = index + 1,
                                line = "[Peer] #${index + 1}",
                                reason =
                                    "WireGuard [Peer] #${index + 1} skipped: " +
                                        (error.message ?: "could not be parsed"),
                            )
                        return@forEachIndexed
                    }
            val peer =
                model.peers.firstOrNull() ?: run {
                    warnings +=
                        SubscriptionLineWarning(
                            lineNumber = index + 1,
                            line = "[Peer] #${index + 1}",
                            reason = "WireGuard [Peer] #${index + 1} skipped: no peer parsed from the block",
                        )
                    return@forEachIndexed
                }
            val hostPort = splitEndpoint(peer.endpoint)
            if (hostPort == null) {
                warnings +=
                    SubscriptionLineWarning(
                        lineNumber = index + 1,
                        line = peer.endpoint.orEmpty(),
                        reason = "WireGuard [Peer] #${index + 1} has no usable Endpoint; skipped",
                    )
                return@forEachIndexed
            }
            val (host, port) = hostPort
            val interfaceSection = model.interfaceSection
            profiles +=
                WireGuardSubscriptionProfile(
                    displayName = "WireGuard $host:$port",
                    groupId = groupId,
                    server = host,
                    serverPort = port,
                    interfacePrivateKey = interfaceSection.privateKey,
                    interfaceAddress = interfaceSection.address,
                    dns = interfaceSection.dns,
                    mtu = interfaceSection.mtu,
                    peerPublicKey = peer.publicKey,
                    peerPresharedKey = peer.presharedKey,
                    allowedIps = peer.allowedIps,
                    persistentKeepalive = peer.persistentKeepalive,
                )
        }

        return WireGuardIniSubscriptionResult(profiles = profiles, warnings = warnings)
    }

    /** The `[Interface]` text block and the per-`[Peer]` text blocks of a `.conf`. */
    private data class WireGuardSections(
        val interfaceBlock: String?,
        val peerBlocks: List<String>,
    )

    /**
     * Splits [payload] into its `[Interface]` block and one block per `[Peer]`,
     * each block being the header line plus every line up to the next section
     * header. Section matching mirrors [WireGuardConfParser]'s own header
     * recognition (`[name]`, case-insensitive). The first `[Interface]` wins;
     * any text before the first section header is dropped (the config parser
     * would reject it anyway, and that rejection is reproduced when the block
     * is re-parsed).
     */
    private fun splitSections(payload: String): WireGuardSections {
        var interfaceBlock: String? = null
        val peerBlocks = mutableListOf<String>()
        var current: StringBuilder? = null
        var currentIsInterface = false
        var currentIsPeer = false

        fun flush() {
            val block = current?.toString() ?: return
            when {
                currentIsInterface && interfaceBlock == null -> interfaceBlock = block
                currentIsPeer -> peerBlocks += block
            }
        }

        for (rawLine in payload.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                flush()
                val name = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                val started = StringBuilder().append(rawLine).append('\n')
                current = started
                currentIsInterface = name == "interface"
                currentIsPeer = name == "peer"
            } else {
                // A non-comment line before any section header has no builder;
                // it is dropped here and the config parser would reject it too.
                current?.append(rawLine)?.append('\n')
            }
        }
        flush()
        return WireGuardSections(interfaceBlock = interfaceBlock, peerBlocks = peerBlocks)
    }

    /**
     * Splits a WireGuard `Endpoint` value (`host:port`) into its host and port.
     * Supports bracketed IPv6 literals. Returns `null` when the endpoint is
     * absent or has no parseable `:port` suffix.
     */
    private fun splitEndpoint(endpoint: String?): Pair<String, Int>? {
        val value = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sep = value.lastIndexOf(':')
        if (sep <= 0 || sep == value.length - 1) return null
        val host =
            value
                .substring(0, sep)
                .removePrefix("[")
                .removeSuffix("]")
                .takeIf { it.isNotBlank() } ?: return null
        val port = value.substring(sep + 1).toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        return host to port
    }
}
