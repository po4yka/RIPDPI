package com.poyka.ripdpi.ui.screens.hysteriav1

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayHysteriaV1AuthTypeBase64
import com.poyka.ripdpi.data.RelayHysteriaV1AuthTypeString
import com.poyka.ripdpi.data.RelayHysteriaV1DownMbpsDefault
import com.poyka.ripdpi.data.RelayHysteriaV1ProtocolFaketcp
import com.poyka.ripdpi.data.RelayHysteriaV1ProtocolUdp
import com.poyka.ripdpi.data.RelayHysteriaV1ProtocolWechatVideo
import com.poyka.ripdpi.data.RelayHysteriaV1UpMbpsDefault
import java.util.UUID

/** Lower bound (inclusive) of a valid TCP/UDP port. */
private const val MinPort = 1

/** Upper bound (inclusive) of a valid TCP/UDP port. */
private const val MaxPort = 65535

/** The auth-payload encodings the native `ripdpi-hysteria-v1` backend accepts. */
val HysteriaV1AuthTypeOptions: List<String> =
    listOf(RelayHysteriaV1AuthTypeString, RelayHysteriaV1AuthTypeBase64)

/** The transport protocols the native `ripdpi-hysteria-v1` backend accepts. */
val HysteriaV1ProtocolOptions: List<String> =
    listOf(
        RelayHysteriaV1ProtocolUdp,
        RelayHysteriaV1ProtocolWechatVideo,
        RelayHysteriaV1ProtocolFaketcp,
    )

/**
 * Every editable field of the Hysteria v1 profile editor.
 *
 * [validate] returns the parsed value when the raw text is legal for the field,
 * or `null` otherwise.
 */
enum class HysteriaV1EditorField {
    DISPLAY_NAME,
    SERVER,
    SERVER_PORT,
    AUTH_PAYLOAD,
    OBFUSCATION,
    UP_MBPS,
    DOWN_MBPS,
    SNI,
    ALPN,
    ;

    /** Validates [raw] for this field, returning the parsed value when legal or `null` when not. */
    fun validate(raw: String): Any? =
        when (this) {
            SERVER_PORT -> raw.toIntOrNull()?.takeIf { it in MinPort..MaxPort }

            UP_MBPS -> raw.toIntOrNull()?.takeIf { it > 0 }

            DOWN_MBPS -> raw.toIntOrNull()?.takeIf { it > 0 }

            AUTH_PAYLOAD -> raw.takeIf { it.isNotBlank() }

            SERVER -> raw.takeIf { it.isNotBlank() }

            // Display name, obfuscation, SNI, and ALPN are free-form / optional text.
            else -> raw
        }
}

/**
 * Immutable snapshot of the Hysteria v1 profile editor.
 *
 * [rawTextByField] keeps the user-typed text per field so an in-progress invalid
 * edit is not silently discarded; [authType] and [protocol] are picker-backed
 * whitelisted selections. Hysteria v1 is a **legacy** protocol — the editor
 * surfaces a migration hint toward the active Hysteria2 kind.
 */
data class HysteriaV1ProfileEditorState(
    val rawTextByField: Map<HysteriaV1EditorField, String> = emptyMap(),
    val authType: String = RelayHysteriaV1AuthTypeString,
    val protocol: String = RelayHysteriaV1ProtocolUdp,
) {
    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: HysteriaV1EditorField): String = rawTextByField[field].orEmpty()

    /** `true` when [field]'s raw text is non-empty but fails [HysteriaV1EditorField.validate]. */
    fun hasFieldError(field: HysteriaV1EditorField): Boolean {
        val raw = rawTextByField[field].orEmpty()
        return raw.isNotEmpty() && field.validate(raw) == null
    }

    /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
    fun updateField(
        field: HysteriaV1EditorField,
        raw: String,
    ): HysteriaV1ProfileEditorState = copy(rawTextByField = rawTextByField + (field to raw))

    /** Selects the auth-type [value] from the whitelist; an unknown value is ignored. */
    fun selectAuthType(value: String): HysteriaV1ProfileEditorState =
        if (value in HysteriaV1AuthTypeOptions) copy(authType = value) else this

    /** Selects the protocol [value] from the whitelist; an unknown value is ignored. */
    fun selectProtocol(value: String): HysteriaV1ProfileEditorState =
        if (value in HysteriaV1ProtocolOptions) copy(protocol = value) else this

    /**
     * `true` when every required field (server, port, auth payload) validates and
     * the up/down bandwidth knobs are both present and strictly positive. The
     * bandwidth defaults apply only when the fields are left blank; an out-of-range
     * typed value is blocking.
     */
    val isComplete: Boolean
        get() =
            HysteriaV1EditorField.SERVER.validate(rawText(HysteriaV1EditorField.SERVER)) != null &&
                HysteriaV1EditorField.SERVER_PORT.validate(rawText(HysteriaV1EditorField.SERVER_PORT)) != null &&
                HysteriaV1EditorField.AUTH_PAYLOAD.validate(rawText(HysteriaV1EditorField.AUTH_PAYLOAD)) != null &&
                !hasFieldError(HysteriaV1EditorField.UP_MBPS) &&
                !hasFieldError(HysteriaV1EditorField.DOWN_MBPS)

    /**
     * Builds a [ProxyProfile.HysteriaV1] from the current editor state, or `null`
     * when the required fields do not validate.
     */
    fun toProfile(): ProxyProfile.HysteriaV1? {
        if (!isComplete) return null
        val server = rawText(HysteriaV1EditorField.SERVER).trim()
        val port = rawText(HysteriaV1EditorField.SERVER_PORT).toIntOrNull() ?: return null
        val authPayload = rawText(HysteriaV1EditorField.AUTH_PAYLOAD)
        val obfuscation = rawText(HysteriaV1EditorField.OBFUSCATION).trim().ifEmpty { null }
        val upMbps = rawText(HysteriaV1EditorField.UP_MBPS).toIntOrNull() ?: RelayHysteriaV1UpMbpsDefault
        val downMbps = rawText(HysteriaV1EditorField.DOWN_MBPS).toIntOrNull() ?: RelayHysteriaV1DownMbpsDefault
        val sni = rawText(HysteriaV1EditorField.SNI).trim().ifEmpty { null }
        val alpn = rawText(HysteriaV1EditorField.ALPN).trim().ifEmpty { null }
        val displayName = rawText(HysteriaV1EditorField.DISPLAY_NAME).trim().ifEmpty { server }
        return ProxyProfile.HysteriaV1(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            groupId = "",
            server = server,
            serverPort = port,
            authType = authType,
            authPayload = authPayload,
            obfuscation = obfuscation,
            protocol = protocol,
            upMbps = upMbps,
            downMbps = downMbps,
            sni = sni,
            alpn = alpn,
        )
    }

    companion object {
        /** A fresh editor: empty fields, `string` auth-type, and the `udp` transport protocol. */
        fun initial(): HysteriaV1ProfileEditorState = HysteriaV1ProfileEditorState()
    }
}
