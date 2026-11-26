package com.poyka.ripdpi.ui.screens.awg

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.awg.AwgActivationInputException
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.applyCohortPreset
import com.poyka.ripdpi.data.awg.requireRuntimeReady
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
    @param:StringRes val label: Int,
) {
    // Identity / standard WireGuard fields — always editable.
    SERVER(isObfuscation = false, label = R.string.awg_field_server),
    SERVER_PORT(isObfuscation = false, label = R.string.awg_field_server_port),
    INTERFACE_PRIVATE_KEY(isObfuscation = false, label = R.string.awg_field_private_key),
    ADDRESS(isObfuscation = false, label = R.string.awg_field_address),
    DNS(isObfuscation = false, label = R.string.awg_field_dns),
    MTU(isObfuscation = false, label = R.string.awg_field_mtu),
    PEER_PUBLIC_KEY(isObfuscation = false, label = R.string.awg_field_peer_public_key),
    PEER_ENDPOINT(isObfuscation = false, label = R.string.awg_field_peer_endpoint),
    ALLOWED_IPS(isObfuscation = false, label = R.string.awg_field_allowed_ips),
    PRESHARED_KEY(isObfuscation = false, label = R.string.awg_field_preshared_key),
    PERSISTENT_KEEPALIVE(isObfuscation = false, label = R.string.awg_field_persistent_keepalive),

    // WG-over-WebSocket carrier URL -- a first-class AwgProfileForm column,
    // only consulted (and required by [AmneziaWgEditorState.isActivatable])
    // when [AwgProfileForm.carrier] is [AwgActivationRequest.CARRIER_WS].
    // Carrier selection itself is a picker, not a text field -- see
    // [AmneziaWgEditorState.selectCarrier].
    CARRIER_WS_URL(isObfuscation = false, label = R.string.awg_field_carrier_ws_url),

    // Obfuscation fields — locked when a cohort preset is selected.
    JC(isObfuscation = true, label = R.string.awg_field_jc),
    JMIN(isObfuscation = true, label = R.string.awg_field_jmin),
    JMAX(isObfuscation = true, label = R.string.awg_field_jmax),
    S1(isObfuscation = true, label = R.string.awg_field_s1),
    S2(isObfuscation = true, label = R.string.awg_field_s2),
    S3(isObfuscation = true, label = R.string.awg_field_s3),
    S4(isObfuscation = true, label = R.string.awg_field_s4),
    H1(isObfuscation = true, label = R.string.awg_field_h1),
    H2(isObfuscation = true, label = R.string.awg_field_h2),
    H3(isObfuscation = true, label = R.string.awg_field_h3),
    H4(isObfuscation = true, label = R.string.awg_field_h4),
    I1(isObfuscation = true, label = R.string.awg_field_i1),
    I2(isObfuscation = true, label = R.string.awg_field_i2),
    I3(isObfuscation = true, label = R.string.awg_field_i3),
    I4(isObfuscation = true, label = R.string.awg_field_i4),
    I5(isObfuscation = true, label = R.string.awg_field_i5),
    HEADER_PROTECTION_KEY(isObfuscation = true, isV3 = true, label = R.string.awg_field_header_protection_key),
    CONTENT_PADDING_ADDITION(isObfuscation = true, isV3 = true, label = R.string.awg_field_content_padding_addition),
    REKEY_AFTER_TIME(isObfuscation = true, isV3 = true, label = R.string.awg_field_rekey_after_time),
    REKEY_TIMEOUT(isObfuscation = true, isV3 = true, label = R.string.awg_field_rekey_timeout),
    REJECT_AFTER_TIME(isObfuscation = true, isV3 = true, label = R.string.awg_field_reject_after_time),
    KEEPALIVE_TIMEOUT(isObfuscation = true, isV3 = true, label = R.string.awg_field_keepalive_timeout),
    MAX_HANDSHAKE_ATTEMPTS(isObfuscation = true, isV3 = true, label = R.string.awg_field_max_handshake_attempts),
    RANDOM_TRAILERS(isObfuscation = true, isV3 = true, label = R.string.awg_field_random_trailers),
    DISABLE_COOKIES(isObfuscation = true, isV3 = true, label = R.string.awg_field_disable_cookies),
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
 * obfuscation group. [subscriptionOwned] is `true` for a profile a subscription refresh rewrites: only the
 * [subscriptionUserFields] stay editable.
 */
data class AmneziaWgEditorState(
    val form: AwgProfileForm,
    val rawTextByField: Map<AwgEditorField, String>,
    val obfuscationLocked: Boolean,
    val subscriptionOwned: Boolean = false,
) {
    override fun toString(): String =
        "AmneziaWgEditorState(form=$form, editedFields=${rawTextByField.keys}, " +
            "obfuscationLocked=$obfuscationLocked, subscriptionOwned=$subscriptionOwned)"

    /** `true` when a subscription refresh owns [field], so the editor shows it read-only. */
    fun isManaged(field: AwgEditorField): Boolean = subscriptionOwned && field !in subscriptionUserFields

    /** A pasted `.conf` for a subscription profile fills only the fields the user owns. */
    fun withUserFieldsFrom(pasted: AmneziaWgEditorState): AmneziaWgEditorState =
        subscriptionUserFields.fold(this) { state, field ->
            pasted.rawTextByField[field]?.takeIf { it.isNotBlank() }?.let { state.updateField(field, it) } ?: state
        }

    fun hasV3Edits(): Boolean = form.v3.isSet || AwgEditorField.entries.any { it.isV3 && hasFieldError(it) }

    /**
     * The field this one conflicts with, or `null`: a `Jmin` above `Jmax`, or a junk or padding size above the
     * tunnel MTU, which fragments on the wire.
     */
    fun conflictingField(field: AwgEditorField): AwgEditorField? {
        val mtu = effectiveMtu()
        val junkActive = form.jc > 0
        // The wire profile rejects an inverted range even when no junk packets are sent.
        val junkRangeBroken = form.jmin > form.jmax
        return when (field) {
            AwgEditorField.JMIN -> {
                AwgEditorField.JMAX.takeIf { junkRangeBroken }
            }

            AwgEditorField.JMAX -> {
                when {
                    junkRangeBroken -> AwgEditorField.JMIN
                    junkActive && form.jmax > mtu -> AwgEditorField.MTU
                    else -> null
                }
            }

            AwgEditorField.S1 -> {
                AwgEditorField.MTU.takeIf { form.s1 > mtu }
            }

            AwgEditorField.S2 -> {
                AwgEditorField.MTU.takeIf { form.s2 > mtu }
            }

            AwgEditorField.S3 -> {
                AwgEditorField.MTU.takeIf { form.s3 > mtu }
            }

            AwgEditorField.S4 -> {
                AwgEditorField.MTU.takeIf { form.s4 > mtu }
            }

            else -> {
                null
            }
        }
    }

    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: AwgEditorField): String = rawTextByField[field].orEmpty()

    /** Invalid raw text or a prefix that cannot hold the configured header-protection nonce. */
    fun hasFieldError(field: AwgEditorField): Boolean {
        val raw = rawTextByField[field] ?: return hasHeaderProtectionPrefixError(field)
        val emptyRoutes = field == AwgEditorField.ALLOWED_IPS && raw.isBlank()
        val badRuntimeInput =
            !field.isObfuscation && (activationError as? AwgActivationInputException)?.field?.editorField() == field
        return emptyRoutes || (
            raw.isNotEmpty() && field.validate(
                raw,
            ) == null
        ) || hasHeaderProtectionPrefixError(field) ||
            badRuntimeInput
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
        if ((field.isObfuscation && obfuscationLocked) || isManaged(field)) return this
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
        if (subscriptionOwned) return this
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
        if (subscriptionOwned) {
            this
        } else {
            copy(
                form = form.copy(cohortId = AwgProfileForm.CUSTOM_COHORT_ID),
                obfuscationLocked = false,
            )
        }

    /**
     * Selects the transport [carrier] ([AwgActivationRequest.CARRIER_UDP] or
     * [AwgActivationRequest.CARRIER_WS]). A picker choice, not a text-field edit --
     * mirrors [selectCohort] / [selectCustom] rather than going through [updateField].
     * [AwgProfileForm.carrierWsUrl] is left untouched so switching back and forth
     * does not discard an already-typed URL.
     */
    fun selectCarrier(carrier: String): AmneziaWgEditorState = copy(form = form.copy(carrier = carrier))

    private val activationError: IllegalArgumentException? by lazy {
        try {
            toActivationRequest(profileId = "").requireRuntimeReady()
            null
        } catch (error: IllegalArgumentException) {
            error
        }
    }

    /** The field the runtime's input check rejects first, or `null`. */
    fun runtimeInputField(): AwgEditorField? = (activationError as? AwgActivationInputException)?.field?.editorField()

    /** Whether the current draft can pass the runtime's local input checks. */
    fun isActivatable(): Boolean = activationError == null

    fun blockingFields(): List<AwgEditorField> =
        if (isActivatable()) {
            emptyList()
        } else {
            AwgEditorField.entries.filter { field ->
                (field in AwgRequiredFields && rawText(field).isBlank()) ||
                    hasFieldError(field) ||
                    conflictingField(field) != null ||
                    field == runtimeInputField()
            }
        }

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
        val noConflicts = AwgEditorField.entries.none { conflictingField(it) != null }
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
        return noConflicts && wireProfileValid && fieldsValid
    }

    /** The runtime input check reports invalid explicit MTU values separately. */
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

private val AwgRequiredFields =
    setOf(
        AwgEditorField.SERVER,
        AwgEditorField.SERVER_PORT,
        AwgEditorField.INTERFACE_PRIVATE_KEY,
        AwgEditorField.ADDRESS,
        AwgEditorField.PEER_PUBLIC_KEY,
    )

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

/**
 * The fields a subscription refresh leaves to the user, as `AwgProfileRepository` merges a refresh: the private key
 * import leaves empty, the DNS and route policy, and the WebSocket carrier URL. The refresh rewrites every other field.
 */
val subscriptionUserFields: Set<AwgEditorField> =
    setOf(
        AwgEditorField.INTERFACE_PRIVATE_KEY,
        AwgEditorField.DNS,
        AwgEditorField.ALLOWED_IPS,
        AwgEditorField.CARRIER_WS_URL,
    )
