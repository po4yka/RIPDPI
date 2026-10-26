package com.poyka.ripdpi.data.wireguard

import com.poyka.ripdpi.data.redacted
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
) {
    override fun toString(): String =
        "WireGuardInterface(privateKey=${redacted(privateKey)}, address=${redacted(address)}, dns=$dns, " +
            "mtu=$mtu, listenPort=$listenPort)"
}

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
    /** High bound of an AWG 3.x `PersistentKeepalive = lo-hi` range; null = a fixed interval. */
    val persistentKeepaliveMax: Int? = null,
) {
    override fun toString(): String =
        "WireGuardPeer(publicKey=$publicKey, presharedKey=${redacted(presharedKey)}, " +
            "allowedIps=$allowedIps, endpoint=${redacted(endpoint)}, " +
            "persistentKeepalive=$persistentKeepalive, persistentKeepaliveMax=$persistentKeepaliveMax)"
}

/**
 * AmneziaWG obfuscation parameters carried on the `[Interface]` block.
 *
 * Every field is optional: a `.conf` with none of these keys parses as a
 * vanilla [WireGuardConfig]; a `.conf` with any of these keys parses as an
 * [AmneziaWgConfig].
 *
 * - `jc`, `jmin`, `jmax`, `s1`, `s2`: non-negative integers (junk packet
 *   counts / sizes).
 * - `s3`, `s4`: cookie and transport padding sizes in 0..1280.
 * - `h1`..`h4`: 4-byte unsigned magic-header values, stored as canonical decimal strings or inclusive ranges.
 * - `i1`..`i5`: special-junk payloads, stored as bounded signature programs.
 * - `v3`: the AmneziaWG 3.x keys (header protection, padding, trailers, timers).
 */
@Serializable
data class AmneziaWgParameters(
    val wireProfile: String = "awg-3.1-ranges-v1",
    val jc: Int? = null,
    val jmin: Int? = null,
    val jmax: Int? = null,
    val s1: Int? = null,
    val s2: Int? = null,
    val s3: Int? = null,
    val s4: Int? = null,
    val h1: String? = null,
    val h2: String? = null,
    val h3: String? = null,
    val h4: String? = null,
    val i1: String? = null,
    val i2: String? = null,
    val i3: String? = null,
    val i4: String? = null,
    val i5: String? = null,
    val v3: AmneziaWgV3Parameters = AmneziaWgV3Parameters(),
) {
    /** Validates the field set, including the AWG 3.x rules such as S1-S4 >= 12 for header protection. */
    fun requireSupportedProfile() {
        AmneziaWgWireProfile.requireScalars(jc, jmin, jmax, s1, s2, s3, s4)
        AmneziaWgWireProfile.requireValid(wireProfile, listOf(h1, h2, h3, h4), listOf(i1, i2, i3, i4, i5))
        v3.requireValid(listOf(s1, s2, s3, s4))
    }

    /** True when no AmneziaWG key was present. */
    val isEmpty: Boolean
        get() =
            jc == null && jmin == null && jmax == null && s1 == null && s2 == null &&
                s3 == null && s4 == null &&
                h1 == null && h2 == null && h3 == null && h4 == null &&
                i1 == null && i2 == null && i3 == null && i4 == null && i5 == null &&
                !v3.isSet

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
     * `wire_profile=<id>&jc=<jc>&...&h4=<h4>&i1=<i1>&...&i5=<i5>`.
     * Header ranges and signature programs use their canonical text. An absent value is "".
     * Keep byte-for-byte in sync with the Python implementation.
     */
    fun cohortFingerprint(): String {
        fun v(value: Any?): String = value?.toString() ?: ""
        val preimage =
            "wire_profile=$wireProfile&jc=${v(jc)}&jmin=${v(jmin)}&jmax=${v(jmax)}&s1=${v(s1)}&s2=${v(s2)}" +
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
