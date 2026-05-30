package com.poyka.ripdpi.ui.screens.vmess

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayVmessSecurityAes128Gcm
import com.poyka.ripdpi.data.RelayVmessSecurityChacha20Poly1305
import com.poyka.ripdpi.data.RelayVmessTransportGrpc
import com.poyka.ripdpi.data.RelayVmessTransportH2
import com.poyka.ripdpi.data.RelayVmessTransportTcp
import com.poyka.ripdpi.data.RelayVmessTransportWs
import java.util.UUID

/** Lower bound (inclusive) of a valid TCP/UDP port. */
private const val MinPort = 1

/** Upper bound (inclusive) of a valid TCP/UDP port. */
private const val MaxPort = 65535

/** Canonical VMess UUID v4 textual shape (8-4-4-4-12 hex with the version-4 / RFC-4122 variant nibbles). */
private val UuidV4Regex =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

/** The AEAD ciphers the native `ripdpi-vmess` backend accepts. */
val VmessSecurityOptions: List<String> =
    listOf(RelayVmessSecurityAes128Gcm, RelayVmessSecurityChacha20Poly1305)

/** The transports the native `ripdpi-vmess` backend accepts. */
val VmessTransportOptions: List<String> =
    listOf(RelayVmessTransportTcp, RelayVmessTransportWs, RelayVmessTransportH2, RelayVmessTransportGrpc)

/**
 * Every editable field of the VMess profile editor.
 *
 * VMess is a **legacy** outbound; `alterId` is fixed at `0` (RIPDPI only speaks
 * the AEAD handshake) and is therefore not an editable field. [validate] returns
 * the parsed value when the raw text is legal for the field, or `null` otherwise.
 */
enum class VmessEditorField {
    DISPLAY_NAME,
    SERVER,
    SERVER_PORT,
    UUID,
    WS_PATH,
    WS_HOST,
    H2_PATH,
    H2_HOST,
    GRPC_SERVICE,
    ;

    /** Validates [raw] for this field, returning the parsed value when legal or `null` when not. */
    fun validate(raw: String): Any? =
        when (this) {
            SERVER_PORT -> raw.toIntOrNull()?.takeIf { it in MinPort..MaxPort }

            UUID -> raw.takeIf { UuidV4Regex.matches(it) }

            SERVER -> raw.takeIf { it.isNotBlank() }

            // Display name and the optional transport params are free-form text.
            else -> raw
        }
}

/**
 * Immutable snapshot of the VMess profile editor.
 *
 * [rawTextByField] keeps the user-typed text per field so an in-progress invalid
 * edit is not silently discarded; [security] and [transport] are picker-backed
 * whitelisted selections. [legacy] is always `true` — VMess is surfaced as a
 * legacy protocol.
 */
data class VmessProfileEditorState(
    val rawTextByField: Map<VmessEditorField, String> = emptyMap(),
    val security: String = RelayVmessSecurityAes128Gcm,
    val transport: String = RelayVmessTransportTcp,
    val legacy: Boolean = true,
) {
    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: VmessEditorField): String = rawTextByField[field].orEmpty()

    /** `true` when [field]'s raw text is non-empty but fails [VmessEditorField.validate]. */
    fun hasFieldError(field: VmessEditorField): Boolean {
        val raw = rawTextByField[field].orEmpty()
        return raw.isNotEmpty() && field.validate(raw) == null
    }

    /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
    fun updateField(
        field: VmessEditorField,
        raw: String,
    ): VmessProfileEditorState = copy(rawTextByField = rawTextByField + (field to raw))

    /** Selects the cipher [value] from the whitelist; an unknown value is ignored. */
    fun selectSecurity(value: String): VmessProfileEditorState =
        if (value in VmessSecurityOptions) copy(security = value) else this

    /** Selects the transport [value] from the whitelist; an unknown value is ignored. */
    fun selectTransport(value: String): VmessProfileEditorState =
        if (value in VmessTransportOptions) copy(transport = value) else this

    /**
     * `true` when every required field (server, port, UUID) validates. The
     * optional transport params are never blocking.
     */
    val isComplete: Boolean
        get() =
            VmessEditorField.SERVER.validate(rawText(VmessEditorField.SERVER)) != null &&
                VmessEditorField.SERVER_PORT.validate(rawText(VmessEditorField.SERVER_PORT)) != null &&
                VmessEditorField.UUID.validate(rawText(VmessEditorField.UUID)) != null

    /**
     * Builds a [ProxyProfile.Vmess] from the current editor state, or `null` when
     * the required fields do not validate. The optional transport params are
     * attached only for the matching transport.
     */
    fun toProfile(): ProxyProfile.Vmess? {
        if (!isComplete) return null
        val server = rawText(VmessEditorField.SERVER).trim()
        val port = rawText(VmessEditorField.SERVER_PORT).toIntOrNull() ?: return null
        val uuid = rawText(VmessEditorField.UUID).trim()
        val displayName = rawText(VmessEditorField.DISPLAY_NAME).trim().ifEmpty { server }
        return ProxyProfile.Vmess(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            groupId = "",
            server = server,
            serverPort = port,
            uuid = uuid,
            security = security,
            transport = transport,
            wsPath = rawText(VmessEditorField.WS_PATH).ifBlank { null }.takeIf { transport == RelayVmessTransportWs },
            wsHost = rawText(VmessEditorField.WS_HOST).ifBlank { null }.takeIf { transport == RelayVmessTransportWs },
            h2Path = rawText(VmessEditorField.H2_PATH).ifBlank { null }.takeIf { transport == RelayVmessTransportH2 },
            h2Host = rawText(VmessEditorField.H2_HOST).ifBlank { null }.takeIf { transport == RelayVmessTransportH2 },
            grpcService =
                rawText(VmessEditorField.GRPC_SERVICE).ifBlank { null }.takeIf { transport == RelayVmessTransportGrpc },
            legacy = true,
        )
    }

    companion object {
        /** A fresh editor: empty fields, the default cipher and TCP transport. */
        fun initial(): VmessProfileEditorState = VmessProfileEditorState()
    }
}
