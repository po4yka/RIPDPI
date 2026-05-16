package com.poyka.ripdpi.services.lifecycle

/**
 * Sealed class representing the fail-closed VPN lifecycle states.
 *
 * Transitions:
 *   Idle → Starting → Connected
 *   Starting → Blocked (protect failure, TUN establishment failure)
 *   Connected → Reconnecting (core crash, network change)
 *   Reconnecting → Connected (health ok) | Blocked (give-up)
 *   Connected / Reconnecting / Blocked → Revoked (onRevoke)
 *   Any → Idle (after full stop)
 */
sealed class VpnLifecycleState {
    data object Idle : VpnLifecycleState()

    data object Starting : VpnLifecycleState()

    data object Connected : VpnLifecycleState()

    data object Reconnecting : VpnLifecycleState()

    /** Terminal blocked state — traffic MUST be blocked while in this state. */
    data class Blocked(
        val reason: BlockedReason,
    ) : VpnLifecycleState()

    data object Revoked : VpnLifecycleState()
}

/** Discriminates the cause of a [VpnLifecycleState.Blocked] transition. */
enum class BlockedReason {
    ProtectFailed,
    TunEstablishFailed,
    CoreCrash,
}
