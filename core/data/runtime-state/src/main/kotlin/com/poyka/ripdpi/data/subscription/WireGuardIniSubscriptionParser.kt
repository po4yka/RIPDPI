package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.wireguard.AmneziaWgConfig
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.data.wireguard.WireGuardConfModel
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.data.wireguard.WireGuardConfig
import com.poyka.ripdpi.data.wireguard.WireGuardPeer

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

/**
 * One AmneziaWG profile produced from a single `[Peer]` of a WireGuard-INI
 * payload whose `[Interface]` block carries AmneziaWG obfuscation keys.
 *
 * This mirrors [WireGuardSubscriptionProfile] one-to-one and additionally
 * carries the interface-scope [AmneziaWgParameters]; the AWG fields are shared
 * by every peer derived from the same `.conf`, exactly as the interface key
 * material is. A subscription whose `[Interface]` has zero AWG keys produces a
 * [WireGuardSubscriptionProfile]; any AWG key produces this type instead.
 */
data class AmneziaWgSubscriptionProfile(
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
    val awg: AmneziaWgParameters,
)

/**
 * Outcome of a [WireGuardIniSubscriptionParser] run: vanilla WireGuard profiles,
 * AmneziaWG profiles, plus per-peer warnings.
 *
 * A single payload yields profiles of one kind per `[Interface]` block: a
 * vanilla `[Interface]` populates [profiles] and an AmneziaWG-flavored
 * `[Interface]` populates [amneziaWgProfiles]. Both lists are present on the
 * result type so the routing decision is visible to callers without a cast.
 */
data class WireGuardIniSubscriptionResult(
    val profiles: List<WireGuardSubscriptionProfile>,
    val amneziaWgProfiles: List<AmneziaWgSubscriptionProfile>,
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
    @Suppress("ReturnCount")
    fun parse(
        payload: String,
        groupId: String,
    ): WireGuardIniSubscriptionResult {
        if (!looksLikeWireGuardIni(payload)) {
            return failure(
                line = payload.lineSequence().firstOrNull().orEmpty(),
                reason = "payload has no [Interface] header; not a WireGuard INI config",
            )
        }

        val sections = splitSections(payload)
        if (sections.interfaceBlock == null) {
            return failure(
                line = "[Interface]",
                reason = "malformed WireGuard INI config: could not isolate the [Interface] block",
            )
        }

        if (sections.peerBlocks.isEmpty()) {
            return failure(
                line = "[Interface]",
                reason = "WireGuard INI config has an [Interface] but no [Peer] section",
            )
        }

        // The `[Interface]` block — including its AmneziaWG keys — is shared by
        // every peer. A malformed interface-scope key cannot be salvaged
        // per-peer, so validate it once up front and fail the whole
        // subscription with a single typed warning if it does not parse.
        runCatching { WireGuardConfParser.parse(sections.interfaceBlock + "\n[Peer]\nPublicKey = probe") }
            .onFailure { error ->
                return failure(
                    line = "[Interface]",
                    reason =
                        "malformed WireGuard INI config: the [Interface] block could not be parsed: " +
                            (error.message ?: "unknown error"),
                )
            }

        val accumulator = SubscriptionAccumulator()
        sections.peerBlocks.forEachIndexed { index, peerBlock ->
            accumulator.acceptPeerBlock(
                interfaceBlock = sections.interfaceBlock,
                peerBlock = peerBlock,
                peerIndex = index,
                groupId = groupId,
            )
        }
        return accumulator.toResult()
    }

    /** Builds an all-profiles-empty [WireGuardIniSubscriptionResult] carrying a single typed warning. */
    private fun failure(
        line: String,
        reason: String,
    ): WireGuardIniSubscriptionResult =
        WireGuardIniSubscriptionResult(
            profiles = emptyList(),
            amneziaWgProfiles = emptyList(),
            warnings =
                listOf(
                    SubscriptionLineWarning(
                        lineNumber = 0,
                        line = line,
                        reason = reason,
                    ),
                ),
        )

    /**
     * Mutable per-run accumulator. Each `[Peer]` block is re-attached to the
     * shared `[Interface]` block and parsed through [WireGuardConfParser]; a
     * failure is isolated to that one peer as a typed warning, while a parsed
     * peer is routed to the vanilla or AmneziaWG profile list by the bean type
     * the config parser returned.
     */
    private class SubscriptionAccumulator {
        private val profiles = mutableListOf<WireGuardSubscriptionProfile>()
        private val amneziaWgProfiles = mutableListOf<AmneziaWgSubscriptionProfile>()
        private val warnings = mutableListOf<SubscriptionLineWarning>()

        @Suppress("ReturnCount")
        fun acceptPeerBlock(
            interfaceBlock: String,
            peerBlock: String,
            peerIndex: Int,
            groupId: String,
        ) {
            val peerLabel = "[Peer] #${peerIndex + 1}"
            val model =
                runCatching { WireGuardConfParser.parse(interfaceBlock + "\n" + peerBlock) }
                    .getOrElse { error ->
                        warn(peerIndex, peerLabel, "$peerLabel skipped: ${error.message ?: "could not be parsed"}")
                        return
                    }
            val peer =
                model.peers.firstOrNull() ?: run {
                    warn(peerIndex, peerLabel, "$peerLabel skipped: no peer parsed from the block")
                    return
                }
            val hostPort = splitEndpoint(peer.endpoint)
            if (hostPort == null) {
                warn(peerIndex, peer.endpoint.orEmpty(), "$peerLabel has no usable Endpoint; skipped")
                return
            }
            addProfile(model, peer, hostPort.first, hostPort.second, groupId)
        }

        private fun addProfile(
            model: WireGuardConfModel,
            peer: WireGuardPeer,
            host: String,
            port: Int,
            groupId: String,
        ) {
            val interfaceSection = model.interfaceSection
            when (model) {
                is AmneziaWgConfig -> {
                    amneziaWgProfiles +=
                        AmneziaWgSubscriptionProfile(
                            displayName = "AmneziaWG $host:$port",
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
                            awg = model.awg,
                        )
                }

                is WireGuardConfig -> {
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
            }
        }

        private fun warn(
            peerIndex: Int,
            line: String,
            reason: String,
        ) {
            warnings +=
                SubscriptionLineWarning(
                    lineNumber = peerIndex + 1,
                    line = line,
                    reason = reason,
                )
        }

        fun toResult(): WireGuardIniSubscriptionResult =
            WireGuardIniSubscriptionResult(
                profiles = profiles.toList(),
                amneziaWgProfiles = amneziaWgProfiles.toList(),
                warnings = warnings.toList(),
            )
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
