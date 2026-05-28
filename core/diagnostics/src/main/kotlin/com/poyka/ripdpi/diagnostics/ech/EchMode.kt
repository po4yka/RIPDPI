package com.poyka.ripdpi.diagnostics.ech

/**
 * Per-domain ECH policy mode for Android 17 (API 37) NetworkSecurityConfig
 * `<domainEncryption>` element.
 */
enum class EchMode {
    /** Hard-require ECH for owned-stack or otherwise ECH-confirmed domains. */
    Enabled,

    /** Disable ECH negotiation for this domain. */
    Disabled,

    /** Attempt ECH but fall back transparently on failure. */
    Opportunistic,
}
