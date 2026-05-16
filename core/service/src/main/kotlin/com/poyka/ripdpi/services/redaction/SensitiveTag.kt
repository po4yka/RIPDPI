package com.poyka.ripdpi.services.redaction

/** Exhaustive taxonomy of secret field categories that must never appear in diagnostics output. */
enum class SensitiveTag {
    Uuid,
    ShortId,
    Password,
    SubscriptionToken,
    Endpoint,
}
