package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.applyCohortPreset
import com.poyka.ripdpi.data.awg.matchCohortForConf
import com.poyka.ripdpi.data.wireguard.AmneziaWgConfig
import com.poyka.ripdpi.data.wireguard.WireGuardConfModel
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser

private const val FourByteUnsignedMax = 0xFFFF_FFFFL
private val HexRegex = Regex("[0-9a-fA-F]+")

/**
 * Every editable field of the AmneziaWG profile editor.
 *
 * The fields split into two groups:
 * - **identity** ([SERVER]..[PRESHARED_KEY], plus the standard WireGuard transport
 *   fields): per-device material, always editable.
 * - **obfuscation** ([JC]..[I5]): server-coordinated, locked whenever a cohort preset is
 *   selected (see [AmneziaWgEditorState.obfuscationLocked]).
 *
 * [validate] mirrors `WireGuardConfParser`: integer ranges for `Jc`/`Jmin`/`Jmax`/`S1`-`S4`,
 * 4-byte unsigned for `H1`-`H4`, hex strings for `I1`-`I5`. A `null` return means the raw
 * text is not a legal value for the field.
 */
enum class AwgEditorField(
    val isObfuscation: Boolean,
) {
    // Identity / standard WireGuard fields — always editable.
    SERVER(isObfuscation = false),
    SERVER_PORT(isObfuscation = false),
    INTERFACE_PRIVATE_KEY(isObfuscation = false),
    ADDRESS(isObfuscation = false),
    DNS(isObfuscation = false),
    MTU(isObfuscation = false),
    PEER_PUBLIC_KEY(isObfuscation = false),
    PEER_ENDPOINT(isObfuscation = false),
    ALLOWED_IPS(isObfuscation = false),
    PRESHARED_KEY(isObfuscation = false),
    PERSISTENT_KEEPALIVE(isObfuscation = false),

    // Obfuscation fields — locked when a cohort preset is selected.
    JC(isObfuscation = true),
    JMIN(isObfuscation = true),
    JMAX(isObfuscation = true),
    S1(isObfuscation = true),
    S2(isObfuscation = true),
    S3(isObfuscation = true),
    S4(isObfuscation = true),
    H1(isObfuscation = true),
    H2(isObfuscation = true),
    H3(isObfuscation = true),
    H4(isObfuscation = true),
    I1(isObfuscation = true),
    I2(isObfuscation = true),
    I3(isObfuscation = true),
    I4(isObfuscation = true),
    I5(isObfuscation = true),
    ;

    /**
     * Validates [raw] for this field. Returns the parsed value (`Int`, `Long`, or `String`)
     * when legal, or `null` when the text cannot be interpreted as a value of this field.
     */
    fun validate(raw: String): Any? =
        when (this) {
            JC, JMIN, JMAX, S1, S2, S3, S4 -> raw.toIntOrNull()?.takeIf { it >= 0 }
            H1, H2, H3, H4 -> raw.toLongOrNull()?.takeIf { it in 0L..FourByteUnsignedMax }
            I1, I2, I3, I4, I5 -> raw.takeIf { it.isNotEmpty() && HexRegex.matches(it) }?.lowercase()
            else -> raw
        }
}

/**
 * Immutable snapshot of the AmneziaWG profile editor.
 *
 * [form] is the canonical [AwgProfileForm] (the persistable shape). [rawTextByField] keeps
 * the user-typed text per field so an in-progress invalid edit is not silently discarded.
 * [obfuscationLocked] is `true` exactly when a non-`Custom` cohort preset owns the
 * obfuscation group.
 */
data class AmneziaWgEditorState(
    val form: AwgProfileForm,
    val rawTextByField: Map<AwgEditorField, String>,
    val obfuscationLocked: Boolean,
) {
    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: AwgEditorField): String = rawTextByField[field].orEmpty()

    /** `true` when [field]'s raw text is non-empty but fails [AwgEditorField.validate]. */
    fun hasFieldError(field: AwgEditorField): Boolean {
        val raw = rawTextByField[field].orEmpty()
        return raw.isNotEmpty() && field.validate(raw) == null
    }

    /**
     * Applies a user edit of [field] to [raw]. An edit of an obfuscation field while
     * [obfuscationLocked] is a no-op (the preset owns those values). A valid edit is
     * folded into [form]; an invalid one updates only [rawTextByField] so the editor can
     * flag the error without losing the keystrokes.
     */
    fun updateField(
        field: AwgEditorField,
        raw: String,
    ): AmneziaWgEditorState {
        if (field.isObfuscation && obfuscationLocked) return this
        val nextRaw = rawTextByField + (field to raw)
        val parsed = field.validate(raw)
        val nextForm = if (parsed == null) form else form.applyField(field, parsed)
        return copy(form = nextForm, rawTextByField = nextRaw)
    }

    /**
     * Selects [preset]: rewrites the obfuscation group via [applyCohortPreset], tags
     * [AwgProfileForm.cohortId], and locks the obfuscation fields. The identity group is
     * untouched.
     */
    fun selectCohort(preset: AwgCohortPreset): AmneziaWgEditorState {
        val nextForm = applyCohortPreset(form, preset)
        return copy(
            form = nextForm,
            rawTextByField = rawTextByField + nextForm.obfuscationRawText(),
            obfuscationLocked = true,
        )
    }

    /**
     * Switches to the `Custom` sentinel: unlocks the obfuscation fields while keeping
     * their current numeric values so the user can tweak from a known-good baseline.
     */
    fun selectCustom(): AmneziaWgEditorState =
        copy(
            form = form.copy(cohortId = AwgProfileForm.CUSTOM_COHORT_ID),
            obfuscationLocked = false,
        )

    /**
     * Replaces the editor state from a pasted AmneziaWG `.conf`. Parsing reuses
     * [WireGuardConfParser]; a vanilla WireGuard config or structurally malformed input
     * leaves the state unchanged. When the parsed obfuscation params byte-match a
     * [catalog] preset the editor locks onto that cohort, otherwise it lands on `Custom`.
     */
    @Suppress("ReturnCount")
    fun populateFromConf(
        conf: String,
        catalog: AwgCohortCatalogData,
    ): AmneziaWgEditorState {
        val parsed = runCatching { WireGuardConfParser.parse(conf) }.getOrNull() ?: return this
        val awg = (parsed as? AmneziaWgConfig)?.awg ?: return this
        val cohortId = matchCohortForConf(conf, catalog)
        val nextForm =
            buildFormFromConf(parsed, awg, cohortId)
        val nextRaw = nextForm.identityRawText() + nextForm.obfuscationRawText() + awg.iFieldRawText()
        return copy(
            form = nextForm,
            rawTextByField = rawTextByField + nextRaw,
            obfuscationLocked = cohortId != AwgProfileForm.CUSTOM_COHORT_ID,
        )
    }

    private fun buildFormFromConf(
        parsed: WireGuardConfModel,
        awg: com.poyka.ripdpi.data.wireguard.AmneziaWgParameters,
        cohortId: String,
    ): AwgProfileForm {
        val peer = parsed.peers.firstOrNull()
        val endpoint = peer?.endpoint.orEmpty()
        val host = endpoint.substringBeforeLast(':', endpoint)
        val port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: 0
        return form.copy(
            interfacePrivateKey = parsed.interfaceSection.privateKey,
            peerPublicKey = peer?.publicKey.orEmpty(),
            presharedKey = peer?.presharedKey.orEmpty(),
            server = host,
            serverPort = port,
            jc = awg.jc ?: 0,
            jmin = awg.jmin ?: 0,
            jmax = awg.jmax ?: 0,
            s1 = awg.s1 ?: 0,
            s2 = awg.s2 ?: 0,
            h1 = awg.h1 ?: 0L,
            h2 = awg.h2 ?: 0L,
            h3 = awg.h3 ?: 0L,
            h4 = awg.h4 ?: 0L,
            cohortId = cohortId,
        )
    }

    companion object {
        /** A fresh editor: an empty `Custom` profile with an unlocked obfuscation group. */
        fun initial(): AmneziaWgEditorState =
            AmneziaWgEditorState(
                form =
                    AwgProfileForm(
                        server = "",
                        serverPort = 0,
                        interfacePrivateKey = "",
                        peerPublicKey = "",
                    ),
                rawTextByField = emptyMap(),
                obfuscationLocked = false,
            )
    }
}

private fun AwgProfileForm.applyField(
    field: AwgEditorField,
    parsed: Any,
): AwgProfileForm =
    when (field) {
        AwgEditorField.SERVER -> copy(server = parsed as String)

        AwgEditorField.SERVER_PORT -> copy(serverPort = (parsed as String).toIntOrNull() ?: serverPort)

        AwgEditorField.INTERFACE_PRIVATE_KEY -> copy(interfacePrivateKey = parsed as String)

        AwgEditorField.PEER_PUBLIC_KEY -> copy(peerPublicKey = parsed as String)

        AwgEditorField.PRESHARED_KEY -> copy(presharedKey = parsed as String)

        AwgEditorField.JC -> copy(jc = parsed as Int)

        AwgEditorField.JMIN -> copy(jmin = parsed as Int)

        AwgEditorField.JMAX -> copy(jmax = parsed as Int)

        AwgEditorField.S1 -> copy(s1 = parsed as Int)

        AwgEditorField.S2 -> copy(s2 = parsed as Int)

        AwgEditorField.H1 -> copy(h1 = parsed as Long)

        AwgEditorField.H2 -> copy(h2 = parsed as Long)

        AwgEditorField.H3 -> copy(h3 = parsed as Long)

        AwgEditorField.H4 -> copy(h4 = parsed as Long)

        // S3/S4/I1-I5/ADDRESS/DNS/MTU/PEER_ENDPOINT/ALLOWED_IPS/PERSISTENT_KEEPALIVE are
        // not first-class AwgProfileForm columns; their raw text is tracked separately.
        else -> this
    }

private fun AwgProfileForm.identityRawText(): Map<AwgEditorField, String> =
    mapOf(
        AwgEditorField.SERVER to server,
        AwgEditorField.SERVER_PORT to serverPort.toString(),
        AwgEditorField.INTERFACE_PRIVATE_KEY to interfacePrivateKey,
        AwgEditorField.PEER_PUBLIC_KEY to peerPublicKey,
        AwgEditorField.PRESHARED_KEY to presharedKey,
    )

private fun AwgProfileForm.obfuscationRawText(): Map<AwgEditorField, String> =
    mapOf(
        AwgEditorField.JC to jc.toString(),
        AwgEditorField.JMIN to jmin.toString(),
        AwgEditorField.JMAX to jmax.toString(),
        AwgEditorField.S1 to s1.toString(),
        AwgEditorField.S2 to s2.toString(),
        AwgEditorField.H1 to h1.toString(),
        AwgEditorField.H2 to h2.toString(),
        AwgEditorField.H3 to h3.toString(),
        AwgEditorField.H4 to h4.toString(),
    )

private fun com.poyka.ripdpi.data.wireguard.AmneziaWgParameters.iFieldRawText(): Map<AwgEditorField, String> =
    buildMap {
        i1?.let { put(AwgEditorField.I1, it) }
        i2?.let { put(AwgEditorField.I2, it) }
        i3?.let { put(AwgEditorField.I3, it) }
        i4?.let { put(AwgEditorField.I4, it) }
        i5?.let { put(AwgEditorField.I5, it) }
    }
