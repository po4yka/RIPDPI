package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Kotlin counterpart of the Rust `NativeBridgeError` payload that the
 * native bridge appends to the exception message after the
 * [SENTINEL] line. The wire shape is documented in
 * `native/rust/crates/ripdpi-android-bridge-support/src/native_bridge_error.rs`.
 *
 * The payload is **optional**: callers that ignore typed errors keep
 * reading `Throwable.getMessage()` exactly as before — the human-readable
 * prefix is the first line of the decorated message and never changes
 * for a given Java exception class. Typed callers call [parse] on the
 * exception message (or any string suspected of carrying a payload) to
 * recover the structured information.
 *
 * Forward-compatibility: the parser uses `ignoreUnknownKeys = true`, so
 * future additive fields produced by a newer native side are accepted
 * silently. A bump of [schemaVersion] documents a **breaking** shape
 * change; additive fields stay on the same version.
 *
 * @property schemaVersion currently always 1.
 * @property domain one of `proxy`, `tunnel`, `relay`, `diagnostics`,
 *   `root`, `telemetry`.
 * @property code short stable identifier (e.g. `start_handle_invalid`).
 * @property message human-readable explanation; mirrors the leading
 *   line of the exception message.
 * @property causeClass Java class name of the conceptual cause, when
 *   the native side knows one.
 * @property handleState runtime state slot the operation was rejected
 *   from (`idle`, `running`, `invalid_or_unknown`, ...).
 * @property retryable `true` if the caller can safely retry without
 *   changing inputs.
 */
@Serializable
data class NativeBridgeErrorPayload(
    val schemaVersion: Int,
    val domain: String,
    val code: String,
    val message: String,
    val causeClass: String? = null,
    val handleState: String? = null,
    val retryable: Boolean = false,
) {
    companion object {
        /** Wire-protocol separator between the human message and the JSON
         * trailer. Keep in sync with `NATIVE_BRIDGE_ERROR_SENTINEL` on the
         * Rust side. */
        const val SENTINEL: String = "---ripdpi-native-bridge-error---"

        // Wire is camelCase (the kotlinx-serialization default), so no
        // namingStrategy override. `ignoreUnknownKeys` is the
        // forward-compat guarantee.
        private val json: Json =
            RipDpiJson

        /**
         * Recover a typed payload from an exception message, log line, or
         * any string suspected of carrying one. Returns `null` when:
         * - [source] is null or empty;
         * - the sentinel line is absent (legacy / non-typed throw);
         * - the JSON trailer is malformed or has wrong types for known
         *   fields.
         *
         * Unknown future keys in the JSON do not cause failure — they
         * are silently ignored.
         */
        @Suppress("ReturnCount") // guard clauses: each malformed-input case short-circuits before decode
        fun parse(source: String?): NativeBridgeErrorPayload? {
            if (source.isNullOrEmpty()) return null
            val sentinelIndex = source.indexOf(SENTINEL)
            if (sentinelIndex < 0) return null
            val trailer = source.substring(sentinelIndex + SENTINEL.length).trimStart('\n', '\r', ' ', '\t')
            if (trailer.isEmpty()) return null
            return runCatching { json.decodeFromString(serializer(), trailer) }.getOrNull()
        }

        /**
         * Strip the typed-payload trailer from a decorated message,
         * returning just the human-readable prefix that legacy callers
         * see. Useful for log lines that want to render the same text
         * as before this feature landed.
         */
        fun humanPrefix(source: String?): String? {
            if (source == null) return null
            val sentinelIndex = source.indexOf(SENTINEL)
            return if (sentinelIndex < 0) source else source.substring(0, sentinelIndex).trimEnd('\n', '\r')
        }
    }
}
