package com.poyka.ripdpi.ui.screens.mieru

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayMieruMtuDefault
import com.poyka.ripdpi.data.RelayMieruMtuMax
import com.poyka.ripdpi.data.RelayMieruMtuMin
import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruMultiplexingLow
import com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle
import com.poyka.ripdpi.data.RelayMieruMultiplexingOff
import com.poyka.ripdpi.data.RelayMieruProtocolTcp
import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import java.util.UUID

/** Lower bound (inclusive) of a valid TCP/UDP port. */
private const val MinPort = 1

/** Upper bound (inclusive) of a valid TCP/UDP port. */
private const val MaxPort = 65535

/** The transport protocols the native `ripdpi-mieru` backend accepts. */
val MieruProtocolOptions: List<String> =
    listOf(RelayMieruProtocolTcp, RelayMieruProtocolUdp)

/** The multiplexing levels the native `ripdpi-mieru` backend accepts. */
val MieruMultiplexingOptions: List<String> =
    listOf(
        RelayMieruMultiplexingOff,
        RelayMieruMultiplexingLow,
        RelayMieruMultiplexingMiddle,
        RelayMieruMultiplexingHigh,
    )

/**
 * Every editable field of the Mieru profile editor.
 *
 * [validate] returns the parsed value when the raw text is legal for the field,
 * or `null` otherwise.
 */
enum class MieruEditorField {
    DISPLAY_NAME,
    SERVER,
    SERVER_PORT,
    USERNAME,
    PASSWORD,
    MTU,
    ;

    /** Validates [raw] for this field, returning the parsed value when legal or `null` when not. */
    fun validate(raw: String): Any? =
        when (this) {
            SERVER_PORT -> raw.toIntOrNull()?.takeIf { it in MinPort..MaxPort }

            MTU -> raw.toIntOrNull()?.takeIf { it in RelayMieruMtuMin..RelayMieruMtuMax }

            USERNAME -> raw.takeIf { it.isNotBlank() }

            PASSWORD -> raw.takeIf { it.isNotBlank() }

            SERVER -> raw.takeIf { it.isNotBlank() }

            // Display name is free-form text.
            else -> raw
        }
}

/**
 * Immutable snapshot of the Mieru profile editor.
 *
 * [rawTextByField] keeps the user-typed text per field so an in-progress invalid
 * edit is not silently discarded; [protocol] and [multiplexing] are picker-backed
 * whitelisted selections. Unlike Trojan-Go, Mieru is **not** a legacy protocol.
 */
data class MieruProfileEditorState(
    val rawTextByField: Map<MieruEditorField, String> = emptyMap(),
    val protocol: String = RelayMieruProtocolTcp,
    val multiplexing: String = RelayMieruMultiplexingMiddle,
) {
    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: MieruEditorField): String = rawTextByField[field].orEmpty()

    /** `true` when [field]'s raw text is non-empty but fails [MieruEditorField.validate]. */
    fun hasFieldError(field: MieruEditorField): Boolean {
        val raw = rawTextByField[field].orEmpty()
        return raw.isNotEmpty() && field.validate(raw) == null
    }

    /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
    fun updateField(
        field: MieruEditorField,
        raw: String,
    ): MieruProfileEditorState = copy(rawTextByField = rawTextByField + (field to raw))

    /** Selects the protocol [value] from the whitelist; an unknown value is ignored. */
    fun selectProtocol(value: String): MieruProfileEditorState =
        if (value in MieruProtocolOptions) copy(protocol = value) else this

    /** Selects the multiplexing [value] from the whitelist; an unknown value is ignored. */
    fun selectMultiplexing(value: String): MieruProfileEditorState =
        if (value in MieruMultiplexingOptions) copy(multiplexing = value) else this

    /**
     * `true` when every required field (server, port, username, password)
     * validates. The MTU defaults to [RelayMieruMtuDefault] when left blank, so it
     * is only blocking when the user typed an out-of-range value.
     */
    val isComplete: Boolean
        get() =
            MieruEditorField.SERVER.validate(rawText(MieruEditorField.SERVER)) != null &&
                MieruEditorField.SERVER_PORT.validate(rawText(MieruEditorField.SERVER_PORT)) != null &&
                MieruEditorField.USERNAME.validate(rawText(MieruEditorField.USERNAME)) != null &&
                MieruEditorField.PASSWORD.validate(rawText(MieruEditorField.PASSWORD)) != null &&
                !hasFieldError(MieruEditorField.MTU)

    /**
     * Builds a [ProxyProfile.Mieru] from the current editor state, or `null` when
     * the required fields do not validate.
     */
    fun toProfile(): ProxyProfile.Mieru? {
        if (!isComplete) return null
        val server = rawText(MieruEditorField.SERVER).trim()
        val port = rawText(MieruEditorField.SERVER_PORT).toIntOrNull() ?: return null
        val username = rawText(MieruEditorField.USERNAME)
        val password = rawText(MieruEditorField.PASSWORD)
        val mtu = rawText(MieruEditorField.MTU).toIntOrNull() ?: RelayMieruMtuDefault
        val displayName = rawText(MieruEditorField.DISPLAY_NAME).trim().ifEmpty { server }
        return ProxyProfile.Mieru(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            groupId = "",
            server = server,
            serverPort = port,
            username = username,
            password = password,
            protocol = protocol,
            multiplexing = multiplexing,
            mtu = mtu,
        )
    }

    companion object {
        /** A fresh editor: empty fields, TCP transport, and the Mieru-default `middle` multiplexing. */
        fun initial(): MieruProfileEditorState = MieruProfileEditorState()
    }
}
