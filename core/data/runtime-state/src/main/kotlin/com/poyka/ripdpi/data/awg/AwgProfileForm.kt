package com.poyka.ripdpi.data.awg

import kotlinx.serialization.Serializable

/**
 * The editable state of an AmneziaWG profile in the profile editor.
 *
 * Splits the profile into two field groups:
 * - **identity** — [server], [serverPort], [interfacePrivateKey],
 *   [peerPublicKey], [presharedKey]: the per-device material, owned by the user.
 * - **obfuscation** — [jc], [jmin], [jmax], [s1], [s2], [s3], [s4], [h1]..[h4],
 *   [i1]..[i5]: server-coordinated, rewritten wholesale by [applyCohortPreset]
 *   when a cohort preset is selected.
 *
 * [cohortId] tags which preset the obfuscation fields came from, or `"Custom"`
 * when they were hand-entered / imported without a match. Applying a preset
 * touches only the obfuscation group and [cohortId]; the identity group is
 * always preserved.
 */
@Serializable
data class AwgProfileForm(
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
    val h1: Long = 0L,
    val h2: Long = 0L,
    val h3: Long = 0L,
    val h4: Long = 0L,
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
    val cohortId: String = CUSTOM_COHORT_ID,
) {
    companion object {
        /** The sentinel id for a profile whose obfuscation fields match no preset. */
        const val CUSTOM_COHORT_ID: String = "Custom"
    }
}
