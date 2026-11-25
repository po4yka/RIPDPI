package com.poyka.ripdpi.data.xray

/**
 * Enumerates the first supported VPN provider kinds.
 *
 * - [Native] — the original RIPDPI native Rust/WireGuard provider managed by
 *   `:core:service` directly.
 * - [Xray] — reserved for the embedded Xray-core provider routed through a
 *   local inbound. The adapter/runtime work is tracked separately and is not
 *   implemented in `:core:engine` yet.
 *
 * Both provider kinds share the same [VpnProviderState] machine in the model.
 * The VPN service layer does not yet select or run the Xray provider.
 */
enum class VpnProviderKind {
    Native,
    Xray,
}

/**
 * Shared VPN provider lifecycle states used by both [VpnProviderKind] paths.
 *
 * Valid transitions:
 * ```
 * Stopped -> Starting -> Running -> Stopping -> Stopped
 * Starting -> Stopped  (error / abort)
 * ```
 */
enum class VpnProviderState {
    Stopped,
    Starting,
    Running,
    Stopping,
    ;

    /**
     * Returns `true` if transitioning from `this` state to [next] is permitted.
     *
     * The transition table encodes the linear lifecycle plus the abort edge
     * `Starting -> Stopped` that covers setup failures before [Running] is reached.
     */
    fun canTransitionTo(next: VpnProviderState): Boolean =
        when (this) {
            Stopped -> next == Starting
            Starting -> next == Running || next == Stopped
            Running -> next == Stopping
            Stopping -> next == Stopped
        }
}

data class XrayProviderConfig(
    val localInboundPort: Int = 10808,
)
