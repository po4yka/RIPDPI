package com.poyka.ripdpi.data.wireguard

import kotlinx.serialization.Serializable

/*
 * Kotlin config model for the WireGuard `.conf` INI format and its AmneziaWG
 * extension.
 *
 * `ProxyProfile` (in `ProxyGroupStores.kt`) is intentionally owned read-only by
 * another file, so WireGuard / AmneziaWG are modelled here as standalone
 * `@Serializable` data classes rather than as a `ProxyProfile` variant.
 *
 * The AmneziaWG obfuscation field set and the parse rules are ported from
 * amnezia-vpn/amneziawg-android (`tunnel/.../config/Interface.java`), which is
 * Apache-2.0 licensed.
 */

/**
 * The `[Interface]` section of a WireGuard `.conf` file.
 *
 * Only the fields RIPDPI needs are modelled; unknown keys are dropped by the
 * parser. `address` and `dns` are comma-separated lists in the source format.
 */
@Serializable
data class WireGuardInterface(
    val privateKey: String,
    val address: List<String> = emptyList(),
    val dns: List<String> = emptyList(),
    val mtu: Int? = null,
    val listenPort: Int? = null,
)

/**
 * A `[Peer]` section of a WireGuard `.conf` file. AmneziaWG adds no peer-level
 * extensions, so this is shared by both [WireGuardConfig] and [AmneziaWgConfig].
 */
@Serializable
data class WireGuardPeer(
    val publicKey: String,
    val presharedKey: String? = null,
    val allowedIps: List<String> = emptyList(),
    val endpoint: String? = null,
    val persistentKeepalive: Int? = null,
)

/**
 * AmneziaWG obfuscation parameters carried on the `[Interface]` block.
 *
 * Every field is optional: a `.conf` with none of these keys parses as a
 * vanilla [WireGuardConfig]; a `.conf` with any of these keys parses as an
 * [AmneziaWgConfig].
 *
 * - `jc`, `jmin`, `jmax`, `s1`, `s2`: non-negative integers (junk packet
 *   counts / sizes).
 * - `h1`..`h4`: 4-byte unsigned magic-header values, stored as [Long] in the
 *   `0..0xFFFFFFFF` range.
 * - `i1`..`i5`: special-junk payloads, stored as lowercase hex strings.
 */
@Serializable
data class AmneziaWgParameters(
    val jc: Int? = null,
    val jmin: Int? = null,
    val jmax: Int? = null,
    val s1: Int? = null,
    val s2: Int? = null,
    val h1: Long? = null,
    val h2: Long? = null,
    val h3: Long? = null,
    val h4: Long? = null,
    val i1: String? = null,
    val i2: String? = null,
    val i3: String? = null,
    val i4: String? = null,
    val i5: String? = null,
) {
    /** True when no AmneziaWG key was present. */
    val isEmpty: Boolean
        get() =
            jc == null && jmin == null && jmax == null && s1 == null && s2 == null &&
                h1 == null && h2 == null && h3 == null && h4 == null &&
                i1 == null && i2 == null && i3 == null && i4 == null && i5 == null

    /**
     * SHA-256 fingerprint of this resolved obfuscation parameter set, in the
     * cross-repo form `"sha256:<64-hex>"`. The server stamps the same value into
     * `ripdpi.amneziawg[].cohort_fingerprint`; recomputing it here lets the
     * client tell the user "profile outdated, refresh your subscription" when a
     * cached bundle's params have drifted from the server's current cohort,
     * instead of letting the AmneziaWG handshake stall silently.
     *
     * The algorithm is the cross-language contract pinned by
     * `contract/cohort-fingerprint.golden.json` (committed identically in this
     * repo and ripdpi-vpn-deploy) and implemented server-side in
     * `ripdpi_cohort_fingerprint.py`. Pre-image (UTF-8, no trailing newline):
     * `jc=<jc>&jmin=<jmin>&...&h4=<h4>&i1=<i1>&...&i5=<i5>`, each value base-10
     * (jc..h4) or lowercase hex (i1..i5), an absent value rendering as "".
     * Keep byte-for-byte in sync with the Python implementation.
     */
    fun cohortFingerprint(): String {
        fun v(value: Any?): String = value?.toString() ?: ""
        val preimage =
            "jc=${v(jc)}&jmin=${v(jmin)}&jmax=${v(jmax)}&s1=${v(s1)}&s2=${v(s2)}" +
                "&h1=${v(h1)}&h2=${v(h2)}&h3=${v(h3)}&h4=${v(h4)}" +
                "&i1=${v(i1)}&i2=${v(i2)}&i3=${v(i3)}&i4=${v(i4)}&i5=${v(i5)}"
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(preimage.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}

/** Common shape of a parsed `.conf`: an `[Interface]` plus zero or more `[Peer]`s. */
sealed interface WireGuardConfModel {
    val interfaceSection: WireGuardInterface
    val peers: List<WireGuardPeer>
}

/** A vanilla WireGuard `.conf` — no AmneziaWG obfuscation keys present. */
@Serializable
data class WireGuardConfig(
    override val interfaceSection: WireGuardInterface,
    override val peers: List<WireGuardPeer> = emptyList(),
) : WireGuardConfModel

/**
 * An AmneziaWG `.conf` — a WireGuard config plus the [AmneziaWgParameters]
 * obfuscation field set found on the `[Interface]` block.
 */
@Serializable
data class AmneziaWgConfig(
    override val interfaceSection: WireGuardInterface,
    val awg: AmneziaWgParameters,
    override val peers: List<WireGuardPeer> = emptyList(),
) : WireGuardConfModel
