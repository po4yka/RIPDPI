package com.poyka.ripdpi.data

/**
 * Opaque, immutable evidence of physical network continuity within this process.
 *
 * Compare tokens for equality only. Implementations must invalidate equality after a network
 * change, including a return to the previous network. Do not serialize tokens or expose network
 * identifiers through them.
 */
interface DiagnosticsNetworkEpoch

/** Provides callback evidence independently of the VPN service lifecycle. */
fun interface DiagnosticsNetworkEpochProvider {
    /** Returns null when physical network continuity cannot be established. */
    fun capture(): DiagnosticsNetworkEpoch?
}
