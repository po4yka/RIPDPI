package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationRequest
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
 * [validate] mirrors `WireGuardConfParser`: non-negative integers for `Jc`/`Jmin`/`Jmax`/`S1`/`S2`,
 * zero-only `S3`/`S4` for Android arm64 safety, 4-byte unsigned for `H1`-`H4`, and hex strings for
 * `I1`-`I5`. A `null` return means the raw text is not a legal value for the field.
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

    // WG-over-WebSocket carrier URL -- a first-class AwgProfileForm column,
    // only consulted (and required by [AmneziaWgEditorState.isActivatable])
    // when [AwgProfileForm.carrier] is [AwgActivationRequest.CARRIER_WS].
    // Carrier selection itself is a picker, not a text field -- see
    // [AmneziaWgEditorState.selectCarrier].
    CARRIER_WS_URL(isObfuscation = false),

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
            JC, JMIN, JMAX, S1, S2 -> raw.toIntOrNull()?.takeIf { it >= 0 }
            S3, S4 -> raw.toIntOrNull()?.takeIf { it == 0 }
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
     * Selects the transport [carrier] ([AwgActivationRequest.CARRIER_UDP] or
     * [AwgActivationRequest.CARRIER_WS]). A picker choice, not a text-field edit --
     * mirrors [selectCohort] / [selectCustom] rather than going through [updateField].
     * [AwgProfileForm.carrierWsUrl] is left untouched so switching back and forth
     * does not discard an already-typed URL.
     */
    fun selectCarrier(carrier: String): AmneziaWgEditorState = copy(form = form.copy(carrier = carrier))

    /**
     * `true` when the identity fields required to open a tunnel are all present:
     * server host + port, interface private key, peer public key, and an
     * interface address. Obfuscation fields are optional (an empty set is a
     * vanilla WireGuard peer). When [AwgProfileForm.carrier] is
     * [AwgActivationRequest.CARRIER_WS], [AwgProfileForm.carrierWsUrl] must also
     * be present -- the WS carrier cannot connect without a request URL.
     */
    fun isActivatable(): Boolean =
        form.server.isNotBlank() &&
            form.serverPort > 0 &&
            form.interfacePrivateKey.isNotBlank() &&
            form.peerPublicKey.isNotBlank() &&
            rawText(AwgEditorField.ADDRESS).isNotBlank() &&
            !hasFieldError(AwgEditorField.S3) &&
            !hasFieldError(AwgEditorField.S4) &&
            obfuscationConsistent() &&
            (form.carrier != AwgActivationRequest.CARRIER_WS || form.carrierWsUrl.isNotBlank())

    /**
     * `true` when the obfuscation knobs are mutually consistent and fit the
     * configured MTU. A junk packet sized above the tunnel MTU (or per-message
     * size-padding above it) fragments on the wire -- itself a DPI fingerprint
     * that can black-hole the junk on restrictive paths -- so a degenerate
     * `Jmin > Jmax` range or an oversized junk/padding value must block
     * activation rather than silently ship a fragmenting profile.
     *
     * The `Jmin <= Jmax` and `Jmax <= MTU` relationships are gated on `Jc > 0`
     * (no junk packets are emitted when `Jc == 0`); the `S1..S4` size-padding is
     * always checked because it applies to every WireGuard message type.
     */
    fun obfuscationConsistent(): Boolean {
        val mtu = effectiveMtu()
        val junkActive = form.jc > 0
        val junkRangeOk = !junkActive || form.jmin <= form.jmax
        val junkSizeFitsMtu = !junkActive || form.jmax <= mtu
        val paddingFitsMtu = form.s1 <= mtu && form.s2 <= mtu && form.s3 <= mtu && form.s4 <= mtu
        val arm64Safe = form.s3 == 0 && form.s4 == 0
        return junkRangeOk && junkSizeFitsMtu && paddingFitsMtu && arm64Safe
    }

    /** Effective tunnel MTU, mirroring [toActivationRequest]'s fallback. */
    private fun effectiveMtu(): Int =
        rawText(AwgEditorField.MTU).trim().toIntOrNull()?.takeIf { it > 0 } ?: AwgActivationRequest.DEFAULT_MTU

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
        // obfuscationRawText() seeds the S/H/I raw text from the freshly-built
        // form, so the I-fields parsed into the form above are covered here too.
        // transportRawText() seeds the non-column fields (Address/DNS/MTU/
        // AllowedIPs/Keepalive) that the parser retains but buildFormFromConf does
        // not carry as form columns — without these a pasted .conf left Address
        // blank (blocking activation) and silently discarded DNS/AllowedIPs/MTU
        // (audit P1-12).
        val nextRaw =
            nextForm.identityRawText() + nextForm.obfuscationRawText() + transportRawText(parsed)
        return copy(
            form = nextForm,
            rawTextByField = rawTextByField + nextRaw,
            obfuscationLocked = cohortId != AwgProfileForm.CUSTOM_COHORT_ID,
        )
    }

    private fun transportRawText(parsed: WireGuardConfModel): Map<AwgEditorField, String> {
        val iface = parsed.interfaceSection
        val peer = parsed.peers.firstOrNull()
        return buildMap {
            iface.address.takeIf { it.isNotEmpty() }?.let { put(AwgEditorField.ADDRESS, it.joinToString(", ")) }
            iface.dns.takeIf { it.isNotEmpty() }?.let { put(AwgEditorField.DNS, it.joinToString(", ")) }
            iface.mtu?.let { put(AwgEditorField.MTU, it.toString()) }
            peer?.allowedIps?.takeIf { it.isNotEmpty() }?.let { put(AwgEditorField.ALLOWED_IPS, it.joinToString(", ")) }
            peer?.persistentKeepalive?.let { put(AwgEditorField.PERSISTENT_KEEPALIVE, it.toString()) }
        }
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
            s3 = awg.s3 ?: 0,
            s4 = awg.s4 ?: 0,
            h1 = awg.h1 ?: 0L,
            h2 = awg.h2 ?: 0L,
            h3 = awg.h3 ?: 0L,
            h4 = awg.h4 ?: 0L,
            i1 = awg.i1.orEmpty(),
            i2 = awg.i2.orEmpty(),
            i3 = awg.i3.orEmpty(),
            i4 = awg.i4.orEmpty(),
            i5 = awg.i5.orEmpty(),
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

// Flat dispatch over the fixed editable-field set; the branch count is inherent
// to the AmneziaWG field set, not accidental complexity (mirrors the same
// CyclomaticComplexMethod suppression on WireGuardConfParser.applyInterfaceKey).
@Suppress("CyclomaticComplexMethod")
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

        AwgEditorField.CARRIER_WS_URL -> copy(carrierWsUrl = parsed as String)

        AwgEditorField.JC -> copy(jc = parsed as Int)

        AwgEditorField.JMIN -> copy(jmin = parsed as Int)

        AwgEditorField.JMAX -> copy(jmax = parsed as Int)

        AwgEditorField.S1 -> copy(s1 = parsed as Int)

        AwgEditorField.S2 -> copy(s2 = parsed as Int)

        AwgEditorField.S3 -> copy(s3 = parsed as Int)

        AwgEditorField.S4 -> copy(s4 = parsed as Int)

        AwgEditorField.H1 -> copy(h1 = parsed as Long)

        AwgEditorField.H2 -> copy(h2 = parsed as Long)

        AwgEditorField.H3 -> copy(h3 = parsed as Long)

        AwgEditorField.H4 -> copy(h4 = parsed as Long)

        AwgEditorField.I1 -> copy(i1 = parsed as String)

        AwgEditorField.I2 -> copy(i2 = parsed as String)

        AwgEditorField.I3 -> copy(i3 = parsed as String)

        AwgEditorField.I4 -> copy(i4 = parsed as String)

        AwgEditorField.I5 -> copy(i5 = parsed as String)

        // ADDRESS/DNS/MTU/PEER_ENDPOINT/ALLOWED_IPS/PERSISTENT_KEEPALIVE are not
        // first-class AwgProfileForm columns; their raw text is tracked separately.
        else -> this
    }

private fun AwgProfileForm.identityRawText(): Map<AwgEditorField, String> =
    mapOf(
        AwgEditorField.SERVER to server,
        AwgEditorField.SERVER_PORT to serverPort.toString(),
        AwgEditorField.INTERFACE_PRIVATE_KEY to interfacePrivateKey,
        AwgEditorField.PEER_PUBLIC_KEY to peerPublicKey,
        AwgEditorField.PRESHARED_KEY to presharedKey,
        AwgEditorField.CARRIER_WS_URL to carrierWsUrl,
    )

private fun AwgProfileForm.obfuscationRawText(): Map<AwgEditorField, String> =
    buildMap {
        put(AwgEditorField.JC, jc.toString())
        put(AwgEditorField.JMIN, jmin.toString())
        put(AwgEditorField.JMAX, jmax.toString())
        put(AwgEditorField.S1, s1.toString())
        put(AwgEditorField.S2, s2.toString())
        put(AwgEditorField.S3, s3.toString())
        put(AwgEditorField.S4, s4.toString())
        put(AwgEditorField.H1, h1.toString())
        put(AwgEditorField.H2, h2.toString())
        put(AwgEditorField.H3, h3.toString())
        put(AwgEditorField.H4, h4.toString())
        // I1..I5 are hex strings: only seed raw text when present so an empty
        // payload column does not flag a spurious field error in the editor.
        if (i1.isNotEmpty()) put(AwgEditorField.I1, i1)
        if (i2.isNotEmpty()) put(AwgEditorField.I2, i2)
        if (i3.isNotEmpty()) put(AwgEditorField.I3, i3)
        if (i4.isNotEmpty()) put(AwgEditorField.I4, i4)
        if (i5.isNotEmpty()) put(AwgEditorField.I5, i5)
    }
