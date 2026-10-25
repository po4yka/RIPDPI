package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import kotlinx.serialization.Serializable

/**
 * The editable state of an AmneziaWG profile in the profile editor.
 *
 * Splits the profile into two field groups:
 * - **identity** — [server], [serverPort], [interfacePrivateKey],
 *   [peerPublicKey], [presharedKey]: the per-device material, owned by the user.
 * - **obfuscation** — [jc], [jmin], [jmax], [s1], [s2], [s3], [s4], [h1]..[h4],
 *   [i1]..[i5]: server-coordinated, rewritten wholesale by [applyCohortPreset]
 *   when a cohort preset is selected. `s3`/`s4` remain in the model for wire
 *   compatibility but activation requires both to be zero (amneziawg-go#110).
 *   [v3] carries the AWG 3.x keys (header protection, padding, trailers, timer
 *   ranges); it belongs to the obfuscation group.
 *
 * [cohortId] tags which preset the obfuscation fields came from, or `"Custom"`
 * when they were hand-entered / imported without a match. Applying a preset
 * touches only the obfuscation group and [cohortId]; the identity group is
 * always preserved.
 *
 * [carrier] / [carrierWsUrl] select the transport the WireGuard datagrams
 * egress over -- [AwgActivationRequest.CARRIER_UDP] (the default) or
 * [AwgActivationRequest.CARRIER_WS] -- and mirror straight through
 * [toActivationRequest]; see that request type for the wire-token contract.
 */
@Serializable
data class AwgProfileForm(
    val wireProfile: String = "awg-3.1-ranges-v1",
    val server: String,
    val serverPort: Int,
    val interfacePrivateKey: String,
    val peerPublicKey: String,
    val presharedKey: String = "",
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
    val h1: String = "1",
    val h2: String = "2",
    val h3: String = "3",
    val h4: String = "4",
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
    val v3: AmneziaWgV3Parameters = AmneziaWgV3Parameters(),
    val cohortId: String = CUSTOM_COHORT_ID,
    val carrier: String = AwgActivationRequest.CARRIER_UDP,
    val carrierWsUrl: String = "",
) {
    companion object {
        /** The sentinel id for a profile whose obfuscation fields match no preset. */
        const val CUSTOM_COHORT_ID: String = "Custom"
    }
}
