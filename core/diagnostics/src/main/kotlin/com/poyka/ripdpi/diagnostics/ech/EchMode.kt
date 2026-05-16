package com.poyka.ripdpi.diagnostics.ech

/**
 * Per-domain ECH policy mode for Android 17 (API 37) NetworkSecurityConfig
 * `<domainEncryption>` element.
 */
enum class EchMode {
    /** Hard-require ECH — use for owned-stack / Reality endpoints. */
    Enabled,

    /** Disable ECH negotiation for this domain. */
    Disabled,

    /** Attempt ECH but fall back transparently on failure. */
    Opportunistic,
}
