package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Captive-portal-assist and whitelist/shutdown classification tests.
 *
 * These exercise [ConnectivityDegradationClassifier] and
 * [CaptivePortalAssistWindow] so that temporary local portal access never
 * becomes a general DNS/direct bypass, and so the diagnostics surface keeps
 * captive portal, whitelist-suspected, no-connectivity, and ordinary VPN
 * degradation strictly apart.
 *
 * Privacy: every fixture below uses synthetic hosts and aggregate counters;
 * no test records or asserts on a real user browsing destination.
 */
class CaptivePortalAndWhitelistModeTest {
    // -- fixtures ---------------------------------------------------------

    private fun wifi(
        validated: Boolean,
        captive: Boolean,
        ssid: String = "ripdpi-lab",
    ): NetworkFingerprint =
        NetworkFingerprint(
            transport = "wifi",
            networkValidated = validated,
            captivePortalDetected = captive,
            privateDnsMode = "system",
            dnsServers = listOf("1.1.1.1"),
            wifi =
                WifiNetworkIdentityTuple(
                    ssid = ssid,
                    bssid = "aa:bb:cc:dd:ee:ff",
                    gateway = "192.0.2.1",
                ),
        )

    private fun noTransport(): NetworkFingerprint =
        NetworkFingerprint(
            transport = "none",
            networkValidated = false,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = emptyList(),
        )

    private val healthyEvidence =
        ReachabilityEvidence(
            foreignEndpointsTried = 3,
            foreignEndpointsReachable = 3,
            localServicesTried = 2,
            localServicesReachable = 2,
            tunnelEstablished = true,
        )

    // -- ConnectivityDegradationClassifier --------------------------------

    @Test
    fun `wifi with vpn off and everything reachable is healthy`() {
        // Wi-Fi, VPN off (lockdown off): all probes pass -> nothing to report.
        val verdict =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = true, captive = false),
                healthyEvidence,
            )
        assertEquals(ConnectivityDegradation.Healthy, verdict)
    }

    @Test
    fun `os captive portal flag classifies as captive portal suspected`() {
        // Always-on + Block style: OS reports captive portal, do not bypass.
        val verdict =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = false, captive = true),
                ReachabilityEvidence(
                    foreignEndpointsTried = 3,
                    foreignEndpointsReachable = 0,
                    localServicesTried = 1,
                    localServicesReachable = 1,
                    tunnelEstablished = false,
                ),
            )
        assertEquals(ConnectivityDegradation.CaptivePortalSuspected, verdict)
    }

    @Test
    fun `unvalidated network where only local services answer is treated as captive`() {
        // No explicit OS flag, but only the portal/gateway answers: undeclared portal.
        val verdict =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = false, captive = false),
                ReachabilityEvidence(
                    foreignEndpointsTried = 4,
                    foreignEndpointsReachable = 0,
                    localServicesTried = 2,
                    localServicesReachable = 2,
                    tunnelEstablished = false,
                ),
            )
        assertEquals(ConnectivityDegradation.CaptivePortalSuspected, verdict)
    }

    @Test
    fun `all foreign endpoints failing while local services reachable is whitelist mode`() {
        // Validated network, but every foreign endpoint fails and RU/local stay
        // reachable -> operator whitelist / national whitelist-mode shutdown.
        val verdict =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = true, captive = false),
                ReachabilityEvidence(
                    foreignEndpointsTried = 5,
                    foreignEndpointsReachable = 0,
                    localServicesTried = 3,
                    localServicesReachable = 3,
                    tunnelEstablished = true,
                ),
            )
        assertEquals(ConnectivityDegradation.WhitelistModeSuspected, verdict)
    }

    @Test
    fun `no transport classifies as no connectivity not whitelist`() {
        val verdict =
            ConnectivityDegradationClassifier.classify(
                noTransport(),
                ReachabilityEvidence(
                    foreignEndpointsTried = 0,
                    foreignEndpointsReachable = 0,
                    localServicesTried = 0,
                    localServicesReachable = 0,
                    tunnelEstablished = false,
                ),
            )
        assertEquals(ConnectivityDegradation.NoConnectivity, verdict)
    }

    @Test
    fun `tunnel down on otherwise healthy network is normal vpn degradation`() {
        // Validated, foreign endpoints reachable directly, but the tunnel did
        // not establish -> ordinary VPN trouble, NOT whitelist or captive.
        val verdict =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = true, captive = false),
                ReachabilityEvidence(
                    foreignEndpointsTried = 3,
                    foreignEndpointsReachable = 3,
                    localServicesTried = 2,
                    localServicesReachable = 2,
                    tunnelEstablished = false,
                ),
            )
        assertEquals(ConnectivityDegradation.NormalVpnDegradation, verdict)
    }

    @Test
    fun `partial foreign reachability loss is normal degradation not whitelist`() {
        // Some but not all foreign endpoints fail -> not the whitelist signature.
        val verdict =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = true, captive = false),
                ReachabilityEvidence(
                    foreignEndpointsTried = 4,
                    foreignEndpointsReachable = 2,
                    localServicesTried = 2,
                    localServicesReachable = 2,
                    tunnelEstablished = true,
                ),
            )
        assertEquals(ConnectivityDegradation.NormalVpnDegradation, verdict)
        assertNotEquals(ConnectivityDegradation.WhitelistModeSuspected, verdict)
    }

    @Test
    fun `the four degraded verdicts are all distinguishable from one another`() {
        // UI/diagnostic contract: captive, whitelist, no-connectivity and normal
        // degradation must never collapse into the same verdict.
        val captive =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = false, captive = true),
                healthyEvidence.copy(foreignEndpointsReachable = 0, tunnelEstablished = false),
            )
        val whitelist =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = true, captive = false),
                ReachabilityEvidence(5, 0, 3, 3, tunnelEstablished = true),
            )
        val none = ConnectivityDegradationClassifier.classify(noTransport(), ReachabilityEvidence(0, 0, 0, 0, false))
        val normal =
            ConnectivityDegradationClassifier.classify(
                wifi(validated = true, captive = false),
                ReachabilityEvidence(3, 3, 2, 2, tunnelEstablished = false),
            )
        val verdicts = listOf(captive, whitelist, none, normal)
        assertEquals("all four degraded verdicts must be distinct", verdicts.size, verdicts.toSet().size)
    }

    // -- CaptivePortalAssistWindow ----------------------------------------

    @Test
    fun `portal assist allows only the portal host and local network hosts`() {
        val window =
            CaptivePortalAssistWindow.open(
                portalHost = "login.airport-wifi.example",
                portalNetworkHosts = setOf("192.0.2.1", "192.0.2.2"),
                networkScopeKey = "scope-airport",
                openedAtMillis = 1_000L,
            )
        val now = 2_000L
        assertTrue(window.allowsHost("login.airport-wifi.example", "scope-airport", now))
        assertTrue(window.allowsHost("192.0.2.1", "scope-airport", now))
        // General browsing during assist must be denied.
        assertFalse(window.allowsHost("example.com", "scope-airport", now))
        assertFalse(window.allowsHost("www.wikipedia.org", "scope-airport", now))
    }

    @Test
    fun `portal assist host matching is case insensitive`() {
        val window =
            CaptivePortalAssistWindow.open(
                portalHost = "Login.Hotel-WiFi.Example",
                portalNetworkHosts = setOf("10.0.0.1"),
                networkScopeKey = "scope-hotel",
                openedAtMillis = 0L,
            )
        assertTrue(window.allowsHost("login.hotel-wifi.example", "scope-hotel", 1L))
    }

    @Test
    fun `portal assist expires automatically after its ttl`() {
        val window =
            CaptivePortalAssistWindow.open(
                portalHost = "login.cafe-wifi.example",
                portalNetworkHosts = setOf("172.16.0.1"),
                networkScopeKey = "scope-cafe",
                openedAtMillis = 1_000L,
                ttlMillis = 60_000L,
            )
        assertTrue("active at start", window.isActiveAt(1_000L))
        assertTrue("active mid-window", window.isActiveAt(30_000L))
        assertFalse("expired exactly at the boundary", window.isActiveAt(61_000L))
        assertFalse("expired well past ttl", window.isActiveAt(120_000L))
        // After expiry the runtime returns to strict DNS: even the portal host
        // is no longer allowed.
        assertFalse(window.allowsHost("login.cafe-wifi.example", "scope-cafe", 61_000L))
    }

    @Test
    fun `portal assist does not leak across a network handover`() {
        // The window is pinned to the network it opened on; after a handover to
        // a different scope key, the same portal host is denied -> strict DNS.
        val window =
            CaptivePortalAssistWindow.open(
                portalHost = "login.airport-wifi.example",
                portalNetworkHosts = setOf("192.0.2.1"),
                networkScopeKey = "scope-airport",
                openedAtMillis = 0L,
            )
        assertTrue(window.allowsHost("login.airport-wifi.example", "scope-airport", 10L))
        assertFalse(
            "portal assist must not survive onto a different network",
            window.allowsHost("login.airport-wifi.example", "scope-home", 10L),
        )
    }

    @Test
    fun `portal assist returns to strict dns after login window lapses`() {
        // "Return to strict DNS after login": once the window is no longer
        // active, nothing — portal host or local host — is allowed.
        val window =
            CaptivePortalAssistWindow.open(
                portalHost = "login.airport-wifi.example",
                portalNetworkHosts = setOf("192.0.2.1"),
                networkScopeKey = "scope-airport",
                openedAtMillis = 0L,
                ttlMillis = 10_000L,
            )
        val afterLapse = 10_001L
        assertFalse(window.isActiveAt(afterLapse))
        assertFalse(window.allowsHost("login.airport-wifi.example", "scope-airport", afterLapse))
        assertFalse(window.allowsHost("192.0.2.1", "scope-airport", afterLapse))
    }

    @Test
    fun `assist window rejects blank portal host and non-positive ttl`() {
        assertThrows {
            CaptivePortalAssistWindow.open(
                portalHost = "  ",
                portalNetworkHosts = emptySet(),
                networkScopeKey = "scope",
                openedAtMillis = 0L,
            )
        }
        assertThrows {
            CaptivePortalAssistWindow.open(
                portalHost = "login.example",
                portalNetworkHosts = emptySet(),
                networkScopeKey = "scope",
                openedAtMillis = 0L,
                ttlMillis = 0L,
            )
        }
    }

    @Test
    fun `reachability evidence rejects impossible counter combinations`() {
        assertThrows {
            ReachabilityEvidence(
                foreignEndpointsTried = 1,
                foreignEndpointsReachable = 2,
                localServicesTried = 0,
                localServicesReachable = 0,
                tunnelEstablished = false,
            )
        }
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected an IllegalArgumentException to be thrown", threw)
    }
}
