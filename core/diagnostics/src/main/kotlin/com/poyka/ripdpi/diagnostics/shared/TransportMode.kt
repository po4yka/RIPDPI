package com.poyka.ripdpi.diagnostics.shared

/**
 * Discriminates the two product modes at the type level.
 *
 * - [Transparent]: traffic originates from third-party apps and flows through
 *   the TUN interface; the VPN service calls VpnService.protect() on every
 *   upstream socket. ECH and owned-stack HTTP client code are NOT available
 *   on this path.
 *
 * - [OwnedStack]: traffic originates from our own browser/SDK components
 *   through platform HttpEngine or native owned-TLS fallback. VpnService.protect()
 *   is not used; the SDK controls the full network stack.
 *
 * The sealed modifier guarantees that when-expressions over this type are
 * exhaustive, which is the primary compile-time boundary enforcement
 * mechanism described in docs/architecture/transparent-vs-owned-stack-boundary.md.
 */
sealed class TransportMode {
    /** TUN + VpnService.protect path — handles arbitrary third-party traffic. */
    data object Transparent : TransportMode()

    /** Platform HttpEngine or native owned-TLS path — handles traffic from our own SDK only. */
    data object OwnedStack : TransportMode()
}
