package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Split-strict DNS policy model
//
// Separates DNS resolution into four distinct resolver planes:
//   BOOTSTRAP — pinned-IP pre-resolution; allows plaintext for initial queries
//   PROXY     — strict-encrypted resolver for all proxied/default domains
//   DIRECT    — resolver used only for domains whose connect route is DIRECT
//   BLOCK     — refuse/block plane; all queries are NXDOMAIN / refused
//
// The proxy plane MUST use an encrypted protocol and MUST NOT fall back to
// plaintext. This prevents DNS leaks when the encrypted resolver is unavailable.
// ---------------------------------------------------------------------------

/** Identifies which resolver plane a [SplitStrictResolverSpec] belongs to. */
@Serializable
enum class DnsResolverPlane {
    BOOTSTRAP,
    PROXY,
    DIRECT,
    BLOCK,
}

/**
 * Encrypted DNS protocol variants for use in [SplitStrictResolverSpec].
 *
 * [NONE] is used for the DIRECT and BLOCK planes where no encrypted resolver
 * is configured (plaintext or refuse, respectively).
 */
@Serializable
enum class StrictEncryptedDnsProtocol {
    /** DNS-over-HTTPS using POST method (RFC 8484). */
    DOH_POST,

    /** DNS-over-TLS strict mode (RFC 7858). No fallback to plaintext. */
    DOT,

    /** DNS-over-QUIC (RFC 9250). Optional; falls back to DOT if unavailable. */
    DOQ,

    /** No encrypted protocol — used for DIRECT (plaintext allowed) and BLOCK planes. */
    NONE,
}

/**
 * Classifies a domain into a resolver plane routing bucket.
 *
 * - [PROXY]: domain is tunnelled; must use the PROXY encrypted resolver.
 * - [DIRECT]: domain's connect route is DIRECT; may use the DIRECT resolver.
 * - [BLOCK]: domain is refused/blocked; no resolution occurs.
 */
@Serializable
enum class DomainClass {
    PROXY,
    DIRECT,
    BLOCK,
}

/**
 * Configuration for a single resolver plane within [SplitStrictDnsPolicy].
 *
 * @param plane              Which resolver plane this spec describes.
 * @param protocol           Encrypted protocol to use. Must not be [StrictEncryptedDnsProtocol.NONE]
 *                           for the PROXY plane.
 * @param endpoint           Resolver endpoint: DoH URL for [StrictEncryptedDnsProtocol.DOH_POST],
 *                           hostname for [StrictEncryptedDnsProtocol.DOT] /
 *                           [StrictEncryptedDnsProtocol.DOQ], empty for BLOCK.
 * @param tlsServerName      TLS SNI override for DOT / DOQ connections.
 * @param pinnedBootstrapIp  Optional pinned IP address for the BOOTSTRAP plane, bypassing
 *                           hostname resolution for the resolver itself.
 * @param allowPlaintextFallback
 *                           Whether plain-UDP DNS is permitted as a fallback.
 *                           MUST be `false` for the PROXY plane.
 */
@Serializable
data class SplitStrictResolverSpec(
    val plane: DnsResolverPlane,
    val protocol: StrictEncryptedDnsProtocol? = null,
    val endpoint: String = "",
    val tlsServerName: String = "",
    val pinnedBootstrapIp: String = "",
    val allowPlaintextFallback: Boolean = false,
) {
    companion object {
        /** Default BOOTSTRAP plane: pinned AdGuard IP, plaintext permitted. */
        fun bootstrapDefault(): SplitStrictResolverSpec =
            SplitStrictResolverSpec(
                plane = DnsResolverPlane.BOOTSTRAP,
                pinnedBootstrapIp = "94.140.14.14",
                allowPlaintextFallback = true,
            )

        /** Default PROXY plane: DoH POST via AdGuard, strict — no plaintext fallback. */
        fun proxyDefault(): SplitStrictResolverSpec =
            SplitStrictResolverSpec(
                plane = DnsResolverPlane.PROXY,
                protocol = StrictEncryptedDnsProtocol.DOH_POST,
                endpoint = "https://dns.adguard-dns.com/dns-query",
                tlsServerName = "dns.adguard-dns.com",
                allowPlaintextFallback = false,
            )

        /** Default DIRECT plane: plaintext DNS is acceptable for direct-route domains. */
        fun directDefault(): SplitStrictResolverSpec =
            SplitStrictResolverSpec(
                plane = DnsResolverPlane.DIRECT,
                protocol = StrictEncryptedDnsProtocol.NONE,
                allowPlaintextFallback = true,
            )

        /** Default BLOCK plane: all queries refused; no resolver is consulted. */
        fun blockDefault(): SplitStrictResolverSpec =
            SplitStrictResolverSpec(
                plane = DnsResolverPlane.BLOCK,
                protocol = StrictEncryptedDnsProtocol.NONE,
                allowPlaintextFallback = false,
            )
    }
}

/**
 * Top-level split-strict DNS policy.
 *
 * Ties together the four resolver planes with an [IPv6 policy][ipv6Policy] and
 * an explicit [directAllowlist] — the set of domain suffixes / CIDR prefixes
 * whose connect route is DIRECT and therefore may use the [direct] resolver.
 *
 * Design invariant: the [proxy] plane's [SplitStrictResolverSpec.allowPlaintextFallback]
 * is always `false`. Callers constructing a policy with `allowPlaintextFallback = true`
 * on the proxy plane are violating the contract and will see unexpected behaviour at
 * the resolver runtime layer.
 *
 * @param bootstrap       BOOTSTRAP plane resolver spec (pinned IP, plaintext OK).
 * @param proxy           PROXY plane resolver spec (strict-encrypted, no fallback).
 * @param direct          DIRECT plane resolver spec; `null` means use system DNS for
 *                        direct domains.
 * @param block           BLOCK/refuse plane spec; `null` means no domain blocking.
 * @param directAllowlist Domain suffixes and CIDR prefixes whose connect route is DIRECT.
 *                        Only these domains may be resolved via the [direct] plane.
 * @param ipv6Policy      How AAAA records and IPv6 connectivity are handled.
 */
@Serializable
data class SplitStrictDnsPolicy(
    val bootstrap: SplitStrictResolverSpec = SplitStrictResolverSpec.bootstrapDefault(),
    val proxy: SplitStrictResolverSpec = SplitStrictResolverSpec.proxyDefault(),
    val direct: SplitStrictResolverSpec? = null,
    val block: SplitStrictResolverSpec? = null,
    val directAllowlist: List<String> = emptyList(),
    val ipv6Policy: Ipv6Policy = Ipv6Policy.ALLOW,
)
