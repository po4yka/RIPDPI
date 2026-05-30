package com.poyka.ripdpi.ui.screens.ssh

import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey

/** Lower bound (inclusive) of a valid TCP port. */
private const val MinPort = 1

/** Upper bound (inclusive) of a valid TCP port. */
private const val MaxPort = 65535

/** The auth selectors the native `ripdpi-ssh` backend accepts. */
val SshAuthTypeOptions: List<String> =
    listOf(RelaySshAuthTypePassword, RelaySshAuthTypePrivateKey)

/**
 * Every editable field of the SSH profile editor.
 *
 * [validate] returns the parsed value when the raw text is legal for the field,
 * or `null` otherwise. Whether [PASSWORD] or [PRIVATE_KEY] is required depends on
 * the selected auth type, so those two are validated by [SshProfileEditorState]
 * rather than unconditionally here.
 */
enum class SshEditorField {
    DISPLAY_NAME,
    SERVER,
    SERVER_PORT,
    USERNAME,
    PASSWORD,
    PRIVATE_KEY,
    PRIVATE_KEY_PASSPHRASE,
    HOST_KEY_FINGERPRINT,
    ;

    /** Validates [raw] for this field, returning the parsed value when legal or `null` when not. */
    fun validate(raw: String): Any? =
        when (this) {
            SERVER_PORT -> raw.toIntOrNull()?.takeIf { it in MinPort..MaxPort }

            SERVER -> raw.takeIf { it.isNotBlank() }

            USERNAME -> raw.takeIf { it.isNotBlank() }

            // Display name, both secrets, the passphrase and the pinned
            // fingerprint are free-form (the secrets are required-conditionally,
            // gated in SshProfileEditorState.isComplete).
            else -> raw
        }
}

/**
 * Immutable snapshot of the SSH profile editor.
 *
 * [rawTextByField] keeps the user-typed text per field so an in-progress invalid
 * edit is not silently discarded; [authType] is a picker-backed whitelisted
 * selection. [privateKeyRevealed] / [passphraseRevealed] gate the secret fields
 * behind the biometric-reveal pattern used by the AmneziaWG editor; both default
 * to hidden. SSH is an editor-only relay kind: it has no URI / subscription
 * import path, so this state intentionally produces no `ProxyProfile`.
 */
data class SshProfileEditorState(
    val rawTextByField: Map<SshEditorField, String> = emptyMap(),
    val authType: String = RelaySshAuthTypePassword,
    val strictHostKey: Boolean = false,
    val privateKeyRevealed: Boolean = false,
    val passphraseRevealed: Boolean = false,
) {
    /** Raw user-typed text for [field] (empty when never edited). */
    fun rawText(field: SshEditorField): String = rawTextByField[field].orEmpty()

    /** `true` when [field]'s raw text is non-empty but fails [SshEditorField.validate]. */
    fun hasFieldError(field: SshEditorField): Boolean {
        val raw = rawTextByField[field].orEmpty()
        return raw.isNotEmpty() && field.validate(raw) == null
    }

    /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
    fun updateField(
        field: SshEditorField,
        raw: String,
    ): SshProfileEditorState = copy(rawTextByField = rawTextByField + (field to raw))

    /** Selects the auth type [value] from the whitelist; an unknown value is ignored. */
    fun selectAuthType(value: String): SshProfileEditorState =
        if (value in SshAuthTypeOptions) copy(authType = value) else this

    /** Sets whether the connection rejects a host-key mismatch (strict TOFU). */
    fun setStrictHostKey(value: Boolean): SshProfileEditorState = copy(strictHostKey = value)

    /** Reveals the private-key field after the biometric gate authorizes it. */
    fun revealPrivateKey(): SshProfileEditorState = copy(privateKeyRevealed = true)

    /** Reveals the passphrase field after the biometric gate authorizes it. */
    fun revealPassphrase(): SshProfileEditorState = copy(passphraseRevealed = true)

    /** Re-hides both secret fields (e.g. when the screen is left). */
    fun relockSecrets(): SshProfileEditorState = copy(privateKeyRevealed = false, passphraseRevealed = false)

    /**
     * `true` when the selected-auth credential is supplied: the password for
     * [RelaySshAuthTypePassword], the private key for [RelaySshAuthTypePrivateKey].
     * The passphrase is always optional.
     */
    private val credentialComplete: Boolean
        get() =
            when (authType) {
                RelaySshAuthTypePrivateKey -> rawText(SshEditorField.PRIVATE_KEY).isNotBlank()
                else -> rawText(SshEditorField.PASSWORD).isNotBlank()
            }

    /**
     * `true` when every required field (server, port, username) validates and the
     * auth-selected credential is present. The pinned host-key fingerprint and the
     * passphrase are optional.
     */
    val isComplete: Boolean
        get() =
            SshEditorField.SERVER.validate(rawText(SshEditorField.SERVER)) != null &&
                SshEditorField.SERVER_PORT.validate(rawText(SshEditorField.SERVER_PORT)) != null &&
                SshEditorField.USERNAME.validate(rawText(SshEditorField.USERNAME)) != null &&
                credentialComplete

    companion object {
        /** A fresh editor: empty fields, password auth, and a lenient (non-strict) host-key policy. */
        fun initial(): SshProfileEditorState = SshProfileEditorState()
    }
}
