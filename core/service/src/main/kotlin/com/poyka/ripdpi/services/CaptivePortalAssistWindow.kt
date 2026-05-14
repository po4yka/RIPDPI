package com.poyka.ripdpi.services

/**
 * A time- and scope-bounded permission to reach a captive-portal login surface.
 *
 * When the OS reports a captive portal, the VPN must let the user complete the
 * portal login WITHOUT becoming a general DNS/direct bypass. This value type
 * encodes exactly that contract:
 *
 *  - access is allowed **only** for the portal host and the captive network's
 *    gateway / local subnet (see [allowsHost]);
 *  - it is pinned to the network it was opened on ([networkScopeKey]) so it
 *    cannot leak onto the next network after a handover;
 *  - it **expires automatically** after [ttlMillis] ([isActiveAt]); the runtime
 *    must re-enter strict DNS once it lapses.
 *
 * The type is immutable and Android-free so it can be unit-tested on the JVM.
 */
data class CaptivePortalAssistWindow(
    /** Hostname of the portal login page (e.g. `login.hotel-wifi.example`). */
    val portalHost: String,
    /** Local subnet hosts the portal needs — gateway, portal IP, redirect IP. */
    val portalNetworkHosts: Set<String>,
    /** Scope key of the network this window was opened on; never leaks across networks. */
    val networkScopeKey: String,
    /** Wall-clock millis at which assist was granted. */
    val openedAtMillis: Long,
    /** How long the grant stays valid, in millis. */
    val ttlMillis: Long,
) {
    init {
        require(portalHost.isNotBlank()) { "portalHost must not be blank" }
        require(networkScopeKey.isNotBlank()) { "networkScopeKey must not be blank" }
        require(openedAtMillis >= 0L) { "openedAtMillis must be >= 0" }
        require(ttlMillis > 0L) { "ttlMillis must be > 0" }
    }

    /** Wall-clock millis at which this window stops being valid. */
    val expiresAtMillis: Long
        get() = openedAtMillis + ttlMillis

    /** True while [nowMillis] is within the grant window. */
    fun isActiveAt(nowMillis: Long): Boolean = nowMillis in openedAtMillis until expiresAtMillis

    /**
     * True when [host] on [networkScopeKey] may be reached under this assist
     * window at [nowMillis]. Everything else — any other host, any other
     * network, any time outside the window — is denied, so portal assist can
     * never widen into general browsing.
     */
    fun allowsHost(
        host: String,
        networkScopeKey: String,
        nowMillis: Long,
    ): Boolean {
        val normalized = host.trim().lowercase()
        val allowedHosts = portalNetworkHosts.map { it.trim().lowercase() } + portalHost.trim().lowercase()
        return isActiveAt(nowMillis) &&
            networkScopeKey == this.networkScopeKey &&
            normalized.isNotEmpty() &&
            normalized in allowedHosts
    }

    companion object {
        /** Default portal-assist lifetime: long enough to log in, short enough to fail closed. */
        const val DEFAULT_TTL_MILLIS: Long = 5L * 60L * 1_000L

        /**
         * Opens a default-TTL assist window for [portalHost] on [networkScopeKey].
         */
        fun open(
            portalHost: String,
            portalNetworkHosts: Set<String>,
            networkScopeKey: String,
            openedAtMillis: Long,
            ttlMillis: Long = DEFAULT_TTL_MILLIS,
        ): CaptivePortalAssistWindow =
            CaptivePortalAssistWindow(
                portalHost = portalHost,
                portalNetworkHosts = portalNetworkHosts,
                networkScopeKey = networkScopeKey,
                openedAtMillis = openedAtMillis,
                ttlMillis = ttlMillis,
            )
    }
}
