package com.poyka.ripdpi.ui.screens.anytls

import com.poyka.ripdpi.data.ProxyProfile
import java.util.UUID

/** Lower bound (inclusive) of a valid TCP/UDP port. */
private const val MinPort = 1

/** Upper bound (inclusive) of a valid TCP/UDP port. */
private const val MaxPort = 65535

/**
 * Every editable field of the AnyTLS profile editor.
 *
 * AnyTLS is a **current** outbound — it runs entirely over a TLS session, so the
 * editable surface is the endpoint, the AnyTLS password credential, and the
 * optional TLS SNI override. [validate] returns the parsed value when the raw
 * text is legal for the field, or `null` otherwise.
 */
enum class AnyTlsEditorField {
    DISPLAY_NAME,
    SERVER,
    SERVER_PORT,
    PASSWORD,
    SNI,
    ;

    /** Validates [raw] for this field, returning the parsed value when legal or `null` when not. */
    fun validate(raw: String): Any? =
        when (this) {
            SERVER_PORT -> raw.toIntOrNull()?.takeIf { it in MinPort..MaxPort }

            PASSWORD -> raw.takeIf { it.isNotBlank() }

            SERVER -> raw.takeIf { it.isNotBlank() }

            // Display name and the optional SNI override are free-form text.
            else -> raw
        }
}

/**
 * Immutable snapshot of the AnyTLS profile editor.
 *
 * [rawTextByField] keeps the user-typed text per field so an in-progress invalid
 * edit is not silently discarded. AnyTLS authenticates with a single password
 * credential; the SNI override is optional and the AnyTLS session always speaks
 * TLS, so no transport/cipher pickers are surfaced.
 */
data class AnyTlsProfileEditorState(
    val rawTextByField: Map<AnyTlsEditorField, String> = emptyMap(),
) {
    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: AnyTlsEditorField): String = rawTextByField[field].orEmpty()

    /** `true` when [field]'s raw text is non-empty but fails [AnyTlsEditorField.validate]. */
    fun hasFieldError(field: AnyTlsEditorField): Boolean {
        val raw = rawTextByField[field].orEmpty()
        return raw.isNotEmpty() && field.validate(raw) == null
    }

    /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
    fun updateField(
        field: AnyTlsEditorField,
        raw: String,
    ): AnyTlsProfileEditorState = copy(rawTextByField = rawTextByField + (field to raw))

    /**
     * `true` when every required field (server, port, password) validates. The
     * optional SNI override is never blocking.
     */
    val isComplete: Boolean
        get() =
            AnyTlsEditorField.SERVER.validate(rawText(AnyTlsEditorField.SERVER)) != null &&
                AnyTlsEditorField.SERVER_PORT.validate(rawText(AnyTlsEditorField.SERVER_PORT)) != null &&
                AnyTlsEditorField.PASSWORD.validate(rawText(AnyTlsEditorField.PASSWORD)) != null

    /**
     * Builds a [ProxyProfile.AnyTls] from the current editor state, or `null`
     * when the required fields do not validate. A blank SNI override falls back
     * to the server host so the emitted [ProxyProfile.AnyTls.serverName] is never
     * empty.
     */
    fun toProfile(): ProxyProfile.AnyTls? {
        if (!isComplete) return null
        val server = rawText(AnyTlsEditorField.SERVER).trim()
        val port = rawText(AnyTlsEditorField.SERVER_PORT).toIntOrNull() ?: return null
        val password = rawText(AnyTlsEditorField.PASSWORD)
        val displayName = rawText(AnyTlsEditorField.DISPLAY_NAME).trim().ifEmpty { server }
        val serverName = rawText(AnyTlsEditorField.SNI).trim().ifEmpty { server }
        return ProxyProfile.AnyTls(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            groupId = "",
            server = server,
            serverPort = port,
            serverName = serverName,
            password = password,
        )
    }

    companion object {
        /** A fresh editor: empty fields. */
        fun initial(): AnyTlsProfileEditorState = AnyTlsProfileEditorState()
    }
}
