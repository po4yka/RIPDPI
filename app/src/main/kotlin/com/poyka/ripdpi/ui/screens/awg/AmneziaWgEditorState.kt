package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.applyCohortPreset
import com.poyka.ripdpi.data.wireguard.AmneziaWgV3Parameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgWireProfile
import com.poyka.ripdpi.data.wireguard.AwgHeaderProtectionKey

/**
 * Every editable field of the AmneziaWG profile editor.
 *
 * The fields split into two groups:
 * - **identity** ([SERVER]..[PRESHARED_KEY], plus the standard WireGuard transport
 *   fields): per-device material, always editable.
 * - **obfuscation** ([JC]..[I5]): server-coordinated, locked whenever a cohort preset is
 *   selected (see [AmneziaWgEditorState.obfuscationLocked]).
 *
 * [validate] mirrors `WireGuardConfParser`: non-negative integers for `Jc`/`Jmin`/`Jmax`/`S1`-`S4`,
 * 4-byte unsigned for `H1`-`H4`, and hex strings for
 * `I1`-`I5`. A `null` return means the raw text is not a legal value for the field.
 */
enum class AwgEditorField(
    val isObfuscation: Boolean,
    val isV3: Boolean = false,
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
    HEADER_PROTECTION_KEY(isObfuscation = true, isV3 = true),
    CONTENT_PADDING_ADDITION(isObfuscation = true, isV3 = true),
    REKEY_AFTER_TIME(isObfuscation = true, isV3 = true),
    REKEY_TIMEOUT(isObfuscation = true, isV3 = true),
    REJECT_AFTER_TIME(isObfuscation = true, isV3 = true),
    KEEPALIVE_TIMEOUT(isObfuscation = true, isV3 = true),
    MAX_HANDSHAKE_ATTEMPTS(isObfuscation = true, isV3 = true),
    RANDOM_TRAILERS(isObfuscation = true, isV3 = true),
    DISABLE_COOKIES(isObfuscation = true, isV3 = true),
    ;

    /**
     * Validates [raw] for this field. Returns the parsed value (`Int`, `Long`, or `String`)
     * when legal, or `null` when the text cannot be interpreted as a value of this field.
     */
    fun validate(raw: String): Any? =
        when (this) {
            HEADER_PROTECTION_KEY -> {
                raw.takeIf { it.isEmpty() || AmneziaWgV3Parameters.isHeaderProtectionKey(it) }
            }

            CONTENT_PADDING_ADDITION, REKEY_AFTER_TIME, REKEY_TIMEOUT, REJECT_AFTER_TIME,
            KEEPALIVE_TIMEOUT, MAX_HANDSHAKE_ATTEMPTS,
            -> {
                raw.takeIf {
                    it.isEmpty() ||
                        AmneziaWgV3Parameters.rangeIsValid(it)
                }
            }

            RANDOM_TRAILERS, DISABLE_COOKIES -> {
                raw.toBooleanStrictOrNull()
            }

            PERSISTENT_KEEPALIVE -> {
                raw.takeIf {
                    it.isBlank() || AmneziaWgV3Parameters.parsePersistentKeepalive(it.trim()) != null
                }
            }

            JC -> {
                raw.toIntOrNull()?.takeIf { it in 0..AmneziaWgWireProfile.MaximumJunkPackets }
            }

            JMIN, JMAX, S1, S2, S3, S4 -> {
                raw.toIntOrNull()?.takeIf { it in 0..AmneziaWgWireProfile.MaximumPacket }
            }

            H1, H2, H3, H4 -> {
                raw.takeIf { AmneziaWgWireProfile.headerIsValid(it, name.last().digitToInt()) }
            }

            I1, I2, I3, I4, I5 -> {
                raw.takeIf { AmneziaWgWireProfile.signatureSize(it) != null }
            }

            else -> {
                raw
            }
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
    override fun toString(): String =
        "AmneziaWgEditorState(form=$form, editedFields=${rawTextByField.keys}, obfuscationLocked=$obfuscationLocked)"

    fun hasV3Edits(): Boolean = form.v3.isSet || AwgEditorField.entries.any { it.isV3 && hasFieldError(it) }

    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: AwgEditorField): String = rawTextByField[field].orEmpty()

    /** Invalid raw text or a prefix that cannot hold the configured header-protection nonce. */
    fun hasFieldError(field: AwgEditorField): Boolean {
        val raw = rawTextByField[field].orEmpty()
        return (raw.isNotEmpty() && field.validate(raw) == null) || hasHeaderProtectionPrefixError(field)
    }

    fun hasHeaderProtectionPrefixError(field: AwgEditorField): Boolean {
        val size =
            when (field) {
                AwgEditorField.S1 -> form.s1
                AwgEditorField.S2 -> form.s2
                AwgEditorField.S3 -> form.s3
                AwgEditorField.S4 -> form.s4
                else -> return false
            }
        val edited = rawTextByField[field]
        val effectiveSize = if (edited == null) size else edited.toIntOrNull()
        return form.v3.headerProtectionEnabled &&
            (effectiveSize == null || effectiveSize < AmneziaWgV3Parameters.HeaderNonceBytes)
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
            obfuscationConsistent() &&
            !hasFieldError(AwgEditorField.PERSISTENT_KEEPALIVE) &&
            (form.carrier != AwgActivationRequest.CARRIER_WS || form.carrierWsUrl.isNotBlank())

    /**
     * `true` when the obfuscation knobs are mutually consistent and fit the
     * configured MTU. A junk packet sized above the tunnel MTU (or per-message
     * size-padding above it) fragments on the wire -- itself a DPI fingerprint
     * that can black-hole the junk on restrictive paths -- so a degenerate
     * `Jmin > Jmax` range or an oversized junk/padding value must block
     * activation rather than silently ship a fragmenting profile.
     *
     * The named wire profile bounds scalar values even when `Jc == 0`.
     */
    fun obfuscationConsistent(): Boolean {
        val mtu = effectiveMtu()
        val junkActive = form.jc > 0
        val junkRangeOk = !junkActive || form.jmin <= form.jmax
        val junkSizeFitsMtu = !junkActive || form.jmax <= mtu
        val paddingFitsMtu = form.s1 <= mtu && form.s2 <= mtu && form.s3 <= mtu && form.s4 <= mtu
        val wireProfileValid =
            runCatching {
                AmneziaWgWireProfile.requireScalars(form.jc, form.jmin, form.jmax, form.s1, form.s2, form.s3, form.s4)
                form.v3.requireValid(listOf(form.s1, form.s2, form.s3, form.s4))
                AmneziaWgWireProfile.requireValid(
                    form.wireProfile,
                    listOf(form.h1, form.h2, form.h3, form.h4),
                    listOf(form.i1, form.i2, form.i3, form.i4, form.i5),
                )
            }.isSuccess
        val fieldsValid = AwgEditorField.entries.filter { it.isObfuscation }.none(::hasFieldError)
        return junkRangeOk && junkSizeFitsMtu && paddingFitsMtu && wireProfileValid && fieldsValid
    }

    /** Effective tunnel MTU, mirroring [toActivationRequest]'s fallback. */
    private fun effectiveMtu(): Int =
        rawText(AwgEditorField.MTU).trim().toIntOrNull()?.takeIf { it > 0 } ?: AwgActivationRequest.DEFAULT_MTU

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
    when {
        field.isV3 -> applyV3Field(field, parsed)
        field.isObfuscation -> applyLegacyObfuscationField(field, parsed)
        else -> applyIdentityField(field, parsed)
    }

private fun AwgProfileForm.applyIdentityField(
    field: AwgEditorField,
    parsed: Any,
): AwgProfileForm =
    when (field) {
        AwgEditorField.SERVER -> {
            copy(server = parsed as String)
        }

        AwgEditorField.SERVER_PORT -> {
            copy(serverPort = (parsed as String).toIntOrNull() ?: serverPort)
        }

        AwgEditorField.INTERFACE_PRIVATE_KEY -> {
            copy(interfacePrivateKey = parsed as String)
        }

        AwgEditorField.PEER_PUBLIC_KEY -> {
            copy(peerPublicKey = parsed as String)
        }

        AwgEditorField.PRESHARED_KEY -> {
            copy(presharedKey = parsed as String)
        }

        AwgEditorField.CARRIER_WS_URL -> {
            copy(carrierWsUrl = parsed as String)
        }

        else -> {
            this
        }
    }

private fun AwgProfileForm.applyLegacyObfuscationField(
    field: AwgEditorField,
    parsed: Any,
): AwgProfileForm =
    when (field) {
        AwgEditorField.JC -> {
            copy(jc = parsed as Int)
        }

        AwgEditorField.JMIN -> {
            copy(jmin = parsed as Int)
        }

        AwgEditorField.JMAX -> {
            copy(jmax = parsed as Int)
        }

        AwgEditorField.S1 -> {
            copy(s1 = parsed as Int)
        }

        AwgEditorField.S2 -> {
            copy(s2 = parsed as Int)
        }

        AwgEditorField.S3 -> {
            copy(s3 = parsed as Int)
        }

        AwgEditorField.S4 -> {
            copy(s4 = parsed as Int)
        }

        AwgEditorField.H1 -> {
            copy(h1 = parsed as String)
        }

        AwgEditorField.H2 -> {
            copy(h2 = parsed as String)
        }

        AwgEditorField.H3 -> {
            copy(h3 = parsed as String)
        }

        AwgEditorField.H4 -> {
            copy(h4 = parsed as String)
        }

        AwgEditorField.I1 -> {
            copy(i1 = parsed as String)
        }

        AwgEditorField.I2 -> {
            copy(i2 = parsed as String)
        }

        AwgEditorField.I3 -> {
            copy(i3 = parsed as String)
        }

        AwgEditorField.I4 -> {
            copy(i4 = parsed as String)
        }

        AwgEditorField.I5 -> {
            copy(i5 = parsed as String)
        }

        else -> {
            this
        }
    }

private fun AwgProfileForm.applyV3Field(
    field: AwgEditorField,
    parsed: Any,
): AwgProfileForm =
    when (field) {
        AwgEditorField.HEADER_PROTECTION_KEY -> {
            copy(
                v3 = v3.copy(headerProtectionKey = AwgHeaderProtectionKey(parsed as String)),
            )
        }

        AwgEditorField.CONTENT_PADDING_ADDITION -> {
            copy(v3 = v3.copy(contentPaddingAddition = parsed as String))
        }

        AwgEditorField.REKEY_AFTER_TIME -> {
            copy(v3 = v3.copy(rekeyAfterTime = parsed as String))
        }

        AwgEditorField.REKEY_TIMEOUT -> {
            copy(v3 = v3.copy(rekeyTimeout = parsed as String))
        }

        AwgEditorField.REJECT_AFTER_TIME -> {
            copy(v3 = v3.copy(rejectAfterTime = parsed as String))
        }

        AwgEditorField.KEEPALIVE_TIMEOUT -> {
            copy(v3 = v3.copy(keepaliveTimeout = parsed as String))
        }

        AwgEditorField.MAX_HANDSHAKE_ATTEMPTS -> {
            copy(v3 = v3.copy(maxHandshakeAttempts = parsed as String))
        }

        AwgEditorField.RANDOM_TRAILERS -> {
            copy(v3 = v3.copy(randomTrailers = parsed as Boolean))
        }

        AwgEditorField.DISABLE_COOKIES -> {
            copy(v3 = v3.copy(disableCookies = parsed as Boolean))
        }

        else -> {
            this
        }
    }

internal fun AwgProfileForm.identityRawText(): Map<AwgEditorField, String> =
    mapOf(
        AwgEditorField.SERVER to server,
        AwgEditorField.SERVER_PORT to serverPort.toString(),
        AwgEditorField.INTERFACE_PRIVATE_KEY to interfacePrivateKey,
        AwgEditorField.PEER_PUBLIC_KEY to peerPublicKey,
        AwgEditorField.PRESHARED_KEY to presharedKey,
        AwgEditorField.CARRIER_WS_URL to carrierWsUrl,
    )

internal fun AwgProfileForm.obfuscationRawText(): Map<AwgEditorField, String> =
    buildMap {
        put(AwgEditorField.JC, jc.toString())
        put(AwgEditorField.JMIN, jmin.toString())
        put(AwgEditorField.JMAX, jmax.toString())
        put(AwgEditorField.S1, s1.toString())
        put(AwgEditorField.S2, s2.toString())
        put(AwgEditorField.S3, s3.toString())
        put(AwgEditorField.S4, s4.toString())
        put(AwgEditorField.H1, h1)
        put(AwgEditorField.H2, h2)
        put(AwgEditorField.H3, h3)
        put(AwgEditorField.H4, h4)
        put(AwgEditorField.I1, i1)
        put(AwgEditorField.I2, i2)
        put(AwgEditorField.I3, i3)
        put(AwgEditorField.I4, i4)
        put(AwgEditorField.I5, i5)
        put(AwgEditorField.HEADER_PROTECTION_KEY, v3.headerProtectionKey.value)
        put(AwgEditorField.CONTENT_PADDING_ADDITION, v3.contentPaddingAddition)
        put(AwgEditorField.REKEY_AFTER_TIME, v3.rekeyAfterTime)
        put(AwgEditorField.REKEY_TIMEOUT, v3.rekeyTimeout)
        put(AwgEditorField.REJECT_AFTER_TIME, v3.rejectAfterTime)
        put(AwgEditorField.KEEPALIVE_TIMEOUT, v3.keepaliveTimeout)
        put(AwgEditorField.MAX_HANDSHAKE_ATTEMPTS, v3.maxHandshakeAttempts)
        put(AwgEditorField.RANDOM_TRAILERS, v3.randomTrailers.toString())
        put(AwgEditorField.DISABLE_COOKIES, v3.disableCookies.toString())
    }
