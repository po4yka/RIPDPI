package com.poyka.ripdpi.diagnostics.shared

/**
 * Neutral DNS classification consumed by both transparent-mode and
 * owned-stack-mode diagnostic arms.
 *
 * This enum mirrors the wire values from the engine contract and
 * [com.poyka.ripdpi.data.DirectDnsClassification] but lives in the
 * neutral shared package so that neither mode arm needs to depend on
 * the other's module.
 *
 * Intentionally carries no platform HTTP or VpnService references.
 */
enum class DnsClassification {
    /** DNS resolution returns honest, unmodified records. */
    CLEAN,

    /** DNS resolution returns forged / sinkholed records. */
    POISONED,

    /** Clean DNS but records diverge between resolvers. */
    DIVERGENT,

    /** HTTPS RR contains an ECH config — owned-stack can use it. */
    ECH_CAPABLE,

    /** No HTTPS resource record present in DNS response. */
    NO_HTTPS_RR,
}
