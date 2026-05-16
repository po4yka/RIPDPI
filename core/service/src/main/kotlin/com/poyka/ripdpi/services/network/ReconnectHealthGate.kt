package com.poyka.ripdpi.services.network

/** Possible states of the reconnect health gate. */
enum class ReconnectGateState {
    /** Tunnel is up; health checks have passed. */
    Connected,

    /** A network transition occurred; waiting for a HealthOk ack before allowing Connected. */
    Reconnecting,
}

/** Result returned by [ReconnectHealthGate.requestDirectFallback]. */
enum class DirectFallbackResult {
    Allowed,
    Blocked,
}

/**
 * State machine that guards the Connected state during network transitions.
 *
 * Any transition (Wi-Fi↔LTE, sleep/wake, captive-portal clearance) moves the gate to
 * [ReconnectGateState.Reconnecting]. The tunnel may not be declared Connected until
 * [onHealthOk] is called. Direct-fallback requests are blocked while Reconnecting.
 */
class ReconnectHealthGate {
    var state: ReconnectGateState = ReconnectGateState.Connected
        private set

    /** Called when a network type transition occurs (e.g. Wi-Fi to cellular). */
    fun onNetworkTransition(
        from: String,
        to: String,
    ) {
        if (from != to) {
            state = ReconnectGateState.Reconnecting
        }
    }

    /** Called when the device wakes from sleep (network connectivity may have changed). */
    fun onSleepWake() {
        state = ReconnectGateState.Reconnecting
    }

    /** Called when a captive portal is cleared and the network is validated. */
    fun onCaptivePortalCleared() {
        state = ReconnectGateState.Reconnecting
    }

    /**
     * Acknowledges a successful health check.
     * Transitions from Reconnecting to Connected; no-op if already Connected.
     */
    fun onHealthOk() {
        if (state == ReconnectGateState.Reconnecting) {
            state = ReconnectGateState.Connected
        }
    }

    /** Returns true only when the gate is in [ReconnectGateState.Connected]. */
    fun isTunnelConnected(): Boolean = state == ReconnectGateState.Connected

    /**
     * Returns [DirectFallbackResult.Blocked] during [ReconnectGateState.Reconnecting] to prevent
     * leaking traffic on the direct path before health checks pass.
     */
    fun requestDirectFallback(): DirectFallbackResult =
        if (state == ReconnectGateState.Reconnecting) {
            DirectFallbackResult.Blocked
        } else {
            DirectFallbackResult.Allowed
        }
}
