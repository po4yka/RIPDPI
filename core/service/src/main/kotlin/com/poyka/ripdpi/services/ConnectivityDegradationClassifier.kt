package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NetworkFingerprint

/**
 * The distinct connectivity verdicts a degraded VPN session can resolve to.
 *
 * A degraded tunnel can look identical from the user's seat — "nothing loads" —
 * whether the network is a captive portal, the operator runs a foreign-endpoint
 * whitelist, the device simply has no link, or the VPN is just slow. Conflating
 * these is dangerous: a captive portal must NOT trigger a general DNS/direct
 * bypass, and a whitelist shutdown must be reported as such rather than as a
 * broken app. This classifier keeps the four states (plus [Healthy]) apart so
 * the UI and the fleet diagnostics can react correctly.
 */
enum class ConnectivityDegradation {
    /** Tunnel and reachability look fine; no degradation to report. */
    Healthy,

    /**
     * The OS flagged a captive portal (or an unvalidated network whose only
     * reachable surface is the portal). Temporary, scoped portal-login assist is
     * appropriate; a general bypass is not.
     */
    CaptivePortalSuspected,

    /**
     * Every foreign endpoint failed while expected local / in-country (RU)
     * services stayed reachable — the signature of an operator whitelist or a
     * national "whitelist mode" shutdown rather than a broken VPN.
     */
    WhitelistModeSuspected,

    /** No transport at all: the device has no usable network link. */
    NoConnectivity,

    /**
     * The VPN tunnel itself is degraded (timeouts, resets, slow paths) on an
     * otherwise validated, non-whitelisted network — ordinary VPN trouble.
     */
    NormalVpnDegradation,
}

/**
 * Reachability evidence gathered for one classification pass.
 *
 * @param foreignEndpointsTried number of out-of-country endpoints probed.
 * @param foreignEndpointsReachable how many of those answered.
 * @param localServicesTried number of expected local / RU services probed.
 * @param localServicesReachable how many of those answered.
 * @param tunnelEstablished whether the VPN tunnel came up at all.
 */
data class ReachabilityEvidence(
    val foreignEndpointsTried: Int,
    val foreignEndpointsReachable: Int,
    val localServicesTried: Int,
    val localServicesReachable: Int,
    val tunnelEstablished: Boolean,
) {
    init {
        require(foreignEndpointsTried >= 0) { "foreignEndpointsTried must be >= 0" }
        require(foreignEndpointsReachable in 0..foreignEndpointsTried) {
            "foreignEndpointsReachable must be within 0..foreignEndpointsTried"
        }
        require(localServicesTried >= 0) { "localServicesTried must be >= 0" }
        require(localServicesReachable in 0..localServicesTried) {
            "localServicesReachable must be within 0..localServicesTried"
        }
    }

    /** True when at least one foreign endpoint was probed and none answered. */
    val allForeignEndpointsFailed: Boolean
        get() = foreignEndpointsTried > 0 && foreignEndpointsReachable == 0

    /** True when at least one local service was probed and every one answered. */
    val allLocalServicesReachable: Boolean
        get() = localServicesTried > 0 && localServicesReachable == localServicesTried
}

/**
 * Pure, side-effect-free classifier that turns a [NetworkFingerprint] plus
 * [ReachabilityEvidence] into a single [ConnectivityDegradation] verdict.
 *
 * Deliberately Android-free and dependency-free so it can be unit-tested on the
 * JVM and reused from both the VPN service runtime and the fleet diagnostics.
 *
 * **Privacy:** the classifier only consumes aggregate counters and the
 * already-sanitised [NetworkFingerprint]; it never sees, stores, or returns a
 * user browsing destination.
 */
object ConnectivityDegradationClassifier {
    /**
     * Resolves the connectivity verdict.
     *
     * Order of precedence — most specific / most actionable first:
     *  1. [ConnectivityDegradation.NoConnectivity] — no transport at all.
     *  2. [ConnectivityDegradation.CaptivePortalSuspected] — OS captive flag, or
     *     an unvalidated network where only local services answer.
     *  3. [ConnectivityDegradation.WhitelistModeSuspected] — every foreign
     *     endpoint failed while all expected local services stayed reachable.
     *  4. [ConnectivityDegradation.NormalVpnDegradation] — tunnel down or some
     *     foreign reachability lost on an otherwise healthy network.
     *  5. [ConnectivityDegradation.Healthy] — nothing to report.
     */
    @Suppress("ReturnCount")
    fun classify(
        fingerprint: NetworkFingerprint,
        evidence: ReachabilityEvidence,
    ): ConnectivityDegradation {
        if (fingerprint.transport.isBlank() || fingerprint.transport.equals("none", ignoreCase = true)) {
            return ConnectivityDegradation.NoConnectivity
        }

        if (fingerprint.captivePortalDetected) {
            return ConnectivityDegradation.CaptivePortalSuspected
        }

        // An unvalidated network whose only working surface is local services
        // is, in practice, an undeclared captive portal: the portal host and
        // the gateway answer, the wider internet does not.
        if (
            !fingerprint.networkValidated &&
            evidence.allLocalServicesReachable &&
            evidence.allForeignEndpointsFailed
        ) {
            return ConnectivityDegradation.CaptivePortalSuspected
        }

        if (evidence.allForeignEndpointsFailed && evidence.allLocalServicesReachable) {
            return ConnectivityDegradation.WhitelistModeSuspected
        }

        if (!evidence.tunnelEstablished || evidence.foreignEndpointsReachable < evidence.foreignEndpointsTried) {
            return ConnectivityDegradation.NormalVpnDegradation
        }

        return ConnectivityDegradation.Healthy
    }
}
