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

/**
 * Audit P1-1 honest-degradation signal: true when the foreign upstream relay died AFTER
 * connecting. The base proxy/VPN runtime is intentionally kept alive (traffic now egresses
 * direct), so the actuator must read Degraded rather than a dishonest secure lock. Driven by
 * the explicit [ServiceTelemetrySnapshot.relayFailed] failure event — never inferred from the
 * absence of live sessions, which subprocess relays never populate even while working. Kept
 * here (the relay-aware actuator helper file) so the MainStateResolvers aggregator does not
 * take on the relay feature family.
 */
internal fun isForeignExitLost(telemetry: ServiceTelemetrySnapshot): Boolean = telemetry.relayFailed

/**
 * Trailing actuator label: "Relay" for a live foreign-relay exit, "Direct"
 * otherwise.
 *
 * This chip answers "what kind of exit", while the headline beside it uses
 * "secure line" to answer "is the tunnel up". Labelling the exit "Secure" made
 * one word carry both questions, so a disengaged line rendered the headline
 * "Secure line disengaged" next to a badge reading "Secure". Naming the exit by
 * what it actually is keeps the two questions distinguishable.
 */
internal fun actuatorTrailingLabel(
    egressBacked: Boolean,
    stringResolver: StringResolver,
): String =
    stringResolver.getString(
        if (egressBacked) {
            R.string.home_connection_actuator_relay
        } else {
            R.string.home_connection_actuator_direct
        },
    )
