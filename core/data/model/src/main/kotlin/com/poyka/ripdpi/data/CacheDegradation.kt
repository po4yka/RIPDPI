package com.poyka.ripdpi.data

/**
 * Typed reason why a cached snapshot could not be loaded.
 *
 * Replaces the silent getOrNull / getOrDefault pattern so operators can distinguish
 * "empty by design" from "cache damaged."
 */
sealed class CacheDegradation {
    /** No cache file exists at the expected path. */
    data object Missing : CacheDegradation()

    /**
     * The cache file exists but was written by an incompatible schema version.
     *
     * @param storedVersion the schema version found in the envelope.
     * @param expectedVersion the highest version this reader understands.
     */
    data class SchemaMismatch(
        val storedVersion: Int,
        val expectedVersion: Int,
    ) : CacheDegradation()

    /**
     * The envelope's cryptographic signature did not pass verification.
     *
     * @param detail human-readable explanation (key ID, algorithm, etc.).
     */
    data class SignatureInvalid(
        val detail: String,
    ) : CacheDegradation()

    /**
     * The cache file exists but its content cannot be parsed.
     *
     * @param reason short human-readable description of the parse error.
     */
    data class Corrupt(
        val reason: String,
    ) : CacheDegradation()
}

/** Wire value used when emitting telemetry for each degradation variant. */
val CacheDegradation.telemetryCode: String
    get() =
        when (this) {
            is CacheDegradation.Missing -> "missing"
            is CacheDegradation.SchemaMismatch -> "schema_mismatch"
            is CacheDegradation.SignatureInvalid -> "signature_invalid"
            is CacheDegradation.Corrupt -> "corrupt"
        }
