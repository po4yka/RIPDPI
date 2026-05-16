package com.poyka.ripdpi.services

import java.net.InetAddress

/**
 * Discriminated union representing whether a captive portal has been identified.
 *
 * Entry into captive-assist mode requires an explicit [Detected] signal carrying
 * the portal hostname. [NotDetected] is a typed rejection — passing it to
 * [CaptiveDnsAssistController.enter] is a no-op and the controller stays in
 * [CaptivePortalState.StrictDns].
 */
sealed class CaptivePortalDetection {
    /** Android or diagnostics confirmed a captive portal at [portalHost]. */
    data class Detected(
        val portalHost: String,
    ) : CaptivePortalDetection()

    /** No captive portal identified; strict DNS must remain in force. */
    object NotDetected : CaptivePortalDetection()
}

/**
 * The three DNS-policy states the controller can be in.
 *
 * - [StrictDns]: normal operation; no captive assist active.
 * - [CaptiveAssist]: temporary, portal-scoped DNS assist is active.
 * - [Timeout]: reserved for future explicit timeout tracking; transitions to [StrictDns].
 */
enum class CaptivePortalState {
    StrictDns,
    CaptiveAssist,
    Timeout,
}

/**
 * Pure, thread-compatible controller for the captive-portal DNS assist lifecycle.
 *
 * **Contract:**
 * - [enter] with [CaptivePortalDetection.Detected] → [CaptivePortalState.CaptiveAssist].
 * - [enter] with [CaptivePortalDetection.NotDetected] → stays [CaptivePortalState.StrictDns].
 * - [resolve] returns a non-null [InetAddress] only for the exact portal host while in
 *   [CaptivePortalState.CaptiveAssist]; all other hosts return null so they are routed
 *   through strict DNS policy.
 * - [markSuccess] / [markTimeout] → [CaptivePortalState.StrictDns], portal host cleared.
 * - [uiFlag] returns `"dns_temporarily_not_private"` in [CaptivePortalState.CaptiveAssist],
 *   null otherwise.
 *
 * Android-free and dependency-free so it can be unit-tested on the JVM.
 */
class CaptiveDnsAssistController {
    private var currentState: CaptivePortalState = CaptivePortalState.StrictDns
    private var portalHost: String? = null

    companion object {
        /** IPv4 loopback bytes used as a placeholder address when DNS lookup is not available in tests. */
        private val loopbackAddress: ByteArray = byteArrayOf(127, 0, 0, 1)
    }

    /** Returns the current [CaptivePortalState]. */
    fun state(): CaptivePortalState = currentState

    /** Returns the active portal host, or null if not in [CaptivePortalState.CaptiveAssist]. */
    fun activePortalHost(): String? = portalHost

    /**
     * Attempts to enter captive-assist mode.
     *
     * Only [CaptivePortalDetection.Detected] transitions state; [CaptivePortalDetection.NotDetected]
     * is silently ignored so callers never accidentally weaken DNS policy.
     */
    fun enter(detection: CaptivePortalDetection) {
        when (detection) {
            is CaptivePortalDetection.Detected -> {
                portalHost = detection.portalHost
                currentState = CaptivePortalState.CaptiveAssist
            }

            CaptivePortalDetection.NotDetected -> {
                // Explicit rejection: do not enter captive mode.
            }
        }
    }

    /**
     * Resolves [host] only when it exactly matches the active portal host (case-insensitive)
     * and the controller is in [CaptivePortalState.CaptiveAssist].
     *
     * All other queries return null — they must be handled by the strict DNS path.
     */
    fun resolve(host: String): InetAddress? {
        val activeHost = portalHost.takeIf { currentState == CaptivePortalState.CaptiveAssist } ?: return null
        return if (host.trim().lowercase() == activeHost.trim().lowercase()) {
            runCatching { InetAddress.getByName(activeHost) }.getOrNull()
                ?: InetAddress.getByAddress(activeHost, loopbackAddress)
        } else {
            null
        }
    }

    /**
     * Returns true if [host] is exactly the active portal host while in captive-assist mode.
     *
     * Use this to decide whether a DNS query may be handled by the captive network's resolver
     * rather than the strict encrypted DNS path.
     */
    fun isPortalHost(host: String): Boolean {
        val activeHost = portalHost.takeIf { currentState == CaptivePortalState.CaptiveAssist } ?: return false
        return host.trim().lowercase() == activeHost.trim().lowercase()
    }

    /**
     * Returns true if captive assist covers [host] — i.e. the host is the exact portal host
     * while in [CaptivePortalState.CaptiveAssist].
     *
     * The inverse (`captiveAssistCoversHost` returning false) guarantees strict DNS applies.
     */
    fun captiveAssistCoversHost(host: String): Boolean = isPortalHost(host)

    /**
     * Records a successful portal login and returns the controller to [CaptivePortalState.StrictDns].
     * No-op when already in [CaptivePortalState.StrictDns].
     */
    fun markSuccess() {
        returnToStrict()
    }

    /**
     * Records a portal-assist timeout and returns the controller to [CaptivePortalState.StrictDns].
     * No-op when already in [CaptivePortalState.StrictDns].
     */
    fun markTimeout() {
        returnToStrict()
    }

    /**
     * Returns the UI flag string when DNS privacy is temporarily reduced for portal login,
     * null otherwise.
     */
    fun uiFlag(): String? =
        if (currentState == CaptivePortalState.CaptiveAssist) "dns_temporarily_not_private" else null

    private fun returnToStrict() {
        currentState = CaptivePortalState.StrictDns
        portalHost = null
    }
}
