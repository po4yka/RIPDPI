package com.poyka.ripdpi.ui.screens.trojango

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayTrojanGoInnerCipherAes256Gcm
import com.poyka.ripdpi.data.RelayTrojanGoInnerCipherChacha20IetfPoly1305
import com.poyka.ripdpi.data.RelayTrojanGoInnerCipherNone
import com.poyka.ripdpi.data.RelayTrojanGoMuxOff
import com.poyka.ripdpi.data.RelayTrojanGoMuxSmuxV1
import java.util.UUID

/** Lower bound (inclusive) of a valid TCP/UDP port. */
private const val MinPort = 1

/** Upper bound (inclusive) of a valid TCP/UDP port. */
private const val MaxPort = 65535

/** The SMUX multiplexing modes the native `ripdpi-trojan-go` backend accepts. */
val TrojanGoMuxOptions: List<String> =
    listOf(RelayTrojanGoMuxOff, RelayTrojanGoMuxSmuxV1)

/** The optional Shadowsocks-AEAD inner ciphers the native `ripdpi-trojan-go` backend accepts. */
val TrojanGoInnerCipherOptions: List<String> =
    listOf(
        RelayTrojanGoInnerCipherNone,
        RelayTrojanGoInnerCipherAes256Gcm,
        RelayTrojanGoInnerCipherChacha20IetfPoly1305,
    )

/**
 * Every editable field of the Trojan-Go profile editor.
 *
 * Trojan-Go is a **legacy** outbound. [validate] returns the parsed value when
 * the raw text is legal for the field, or `null` otherwise.
 */
enum class TrojanGoEditorField {
    DISPLAY_NAME,
    SERVER,
    SERVER_PORT,
    PASSWORD,
    SNI,
    WS_PATH,
    WS_HOST,
    ;

    /** Validates [raw] for this field, returning the parsed value when legal or `null` when not. */
    fun validate(raw: String): Any? =
        when (this) {
            SERVER_PORT -> raw.toIntOrNull()?.takeIf { it in MinPort..MaxPort }

            PASSWORD -> raw.takeIf { it.isNotBlank() }

            SERVER -> raw.takeIf { it.isNotBlank() }

            // Display name, SNI, and the optional WS params are free-form text.
            else -> raw
        }
}

/**
 * Immutable snapshot of the Trojan-Go profile editor.
 *
 * [rawTextByField] keeps the user-typed text per field so an in-progress invalid
 * edit is not silently discarded; [mux] and [innerCipher] are picker-backed
 * whitelisted selections. [legacy] is always `true` — Trojan-Go is surfaced as a
 * legacy protocol.
 */
data class TrojanGoProfileEditorState(
    val rawTextByField: Map<TrojanGoEditorField, String> = emptyMap(),
    val mux: String = RelayTrojanGoMuxOff,
    val innerCipher: String = RelayTrojanGoInnerCipherNone,
    val legacy: Boolean = true,
) {
    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: TrojanGoEditorField): String = rawTextByField[field].orEmpty()

    /** `true` when [field]'s raw text is non-empty but fails [TrojanGoEditorField.validate]. */
    fun hasFieldError(field: TrojanGoEditorField): Boolean {
        val raw = rawTextByField[field].orEmpty()
        return raw.isNotEmpty() && field.validate(raw) == null
    }

    /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
    fun updateField(
        field: TrojanGoEditorField,
        raw: String,
    ): TrojanGoProfileEditorState = copy(rawTextByField = rawTextByField + (field to raw))

    /** Selects the mux [value] from the whitelist; an unknown value is ignored. */
    fun selectMux(value: String): TrojanGoProfileEditorState =
        if (value in TrojanGoMuxOptions) copy(mux = value) else this

    /** Selects the inner cipher [value] from the whitelist; an unknown value is ignored. */
    fun selectInnerCipher(value: String): TrojanGoProfileEditorState =
        if (value in TrojanGoInnerCipherOptions) copy(innerCipher = value) else this

    /**
     * `true` when every required field (server, port, password) validates. The
     * optional SNI / WS params are never blocking.
     */
    val isComplete: Boolean
        get() =
            TrojanGoEditorField.SERVER.validate(rawText(TrojanGoEditorField.SERVER)) != null &&
                TrojanGoEditorField.SERVER_PORT.validate(rawText(TrojanGoEditorField.SERVER_PORT)) != null &&
                TrojanGoEditorField.PASSWORD.validate(rawText(TrojanGoEditorField.PASSWORD)) != null

    /**
     * Builds a [ProxyProfile.TrojanGo] from the current editor state, or `null`
     * when the required fields do not validate.
     */
    fun toProfile(): ProxyProfile.TrojanGo? {
        if (!isComplete) return null
        val server = rawText(TrojanGoEditorField.SERVER).trim()
        val port = rawText(TrojanGoEditorField.SERVER_PORT).toIntOrNull() ?: return null
        val password = rawText(TrojanGoEditorField.PASSWORD)
        val displayName = rawText(TrojanGoEditorField.DISPLAY_NAME).trim().ifEmpty { server }
        return ProxyProfile.TrojanGo(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            groupId = "",
            server = server,
            serverPort = port,
            password = password,
            sni = rawText(TrojanGoEditorField.SNI).ifBlank { null },
            wsPath = rawText(TrojanGoEditorField.WS_PATH).ifBlank { null },
            wsHost = rawText(TrojanGoEditorField.WS_HOST).ifBlank { null },
            mux = mux,
            innerCipher = innerCipher,
            legacy = true,
        )
    }

    companion object {
        /** A fresh editor: empty fields, no mux, and the plain inner protocol. */
        fun initial(): TrojanGoProfileEditorState = TrojanGoProfileEditorState()
    }
}
