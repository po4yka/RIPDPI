package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings

/**
 * Honest egress disclosure for the connection actuator (audit P0-2): the "Secure"
 * trailing label and "Secure line locked" status must only appear when a foreign
 * relay genuinely backs the live egress. The signal is RUNTIME relay telemetry, not
 * the configured `relayEnabled` flag, so a relay that failed and fell back to direct
 * correctly reads as direct. Extracted from MainStateResolvers so that aggregator does
 * not take on the relay feature family. Mirrors buildRelayCapabilityObservation's
 * running/healthy + active-session heuristic.
 */
internal fun isForeignExitLive(
    settings: AppSettings,
    telemetry: ServiceTelemetrySnapshot,
): Boolean {
    if (!settings.toConfigDraft().relayEnabled) {
        return false
    }
    val relay = telemetry.relayTelemetry
    val live =
        relay.state.equals("running", ignoreCase = true) ||
            relay.health.equals("healthy", ignoreCase = true)
    // Gate on CURRENTLY-active sessions only: totalSessions is a monotonic lifetime
    // counter (never decremented), so it would keep reporting "relay-backed" long
    // after the foreign exit dropped — exactly the false-"Secure" this guard prevents.
    return live && relay.activeSessions > 0
}

/** Trailing actuator label: "Secure" for a live foreign-relay exit, "Direct" otherwise. */
internal fun actuatorTrailingLabel(
    egressBacked: Boolean,
    stringResolver: StringResolver,
): String =
    stringResolver.getString(
        if (egressBacked) {
            R.string.home_connection_actuator_secure
        } else {
            R.string.home_connection_actuator_direct
        },
    )
