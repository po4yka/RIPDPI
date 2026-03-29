package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStrategyMemoryTest {
    // -- RememberedNetworkPolicySource --

    @Test
    fun `fromStorageValue parses known values`() {
        assertEquals(
            RememberedNetworkPolicySource.MANUAL_SESSION,
            RememberedNetworkPolicySource.fromStorageValue("manual_session"),
        )
        assertEquals(
            RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
            RememberedNetworkPolicySource.fromStorageValue("automatic_probing_background"),
        )
        assertEquals(
            RememberedNetworkPolicySource.STRATEGY_PROBE_MANUAL,
            RememberedNetworkPolicySource.fromStorageValue("strategy_probe_manual"),
        )
    }

    @Test
    fun `fromStorageValue normalizes case and whitespace`() {
        assertEquals(
            RememberedNetworkPolicySource.MANUAL_SESSION,
            RememberedNetworkPolicySource.fromStorageValue("  MANUAL_SESSION  "),
        )
    }

    @Test
    fun `fromStorageValue returns UNKNOWN for unrecognized value`() {
        assertEquals(RememberedNetworkPolicySource.UNKNOWN, RememberedNetworkPolicySource.fromStorageValue("garbage"))
        assertEquals(RememberedNetworkPolicySource.UNKNOWN, RememberedNetworkPolicySource.fromStorageValue(null))
        assertEquals(RememberedNetworkPolicySource.UNKNOWN, RememberedNetworkPolicySource.fromStorageValue(""))
    }

    @Test
    fun `encodeStorageValue round trips for non-UNKNOWN sources`() {
        RememberedNetworkPolicySource.entries
            .filter { it != RememberedNetworkPolicySource.UNKNOWN }
            .forEach { source ->
                assertEquals(source, RememberedNetworkPolicySource.fromStorageValue(source.encodeStorageValue()))
            }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encodeStorageValue rejects UNKNOWN`() {
        RememberedNetworkPolicySource.UNKNOWN.encodeStorageValue()
    }

    // -- NetworkFingerprint.scopeKey --

    @Test
    fun `scopeKey produces consistent hash for same input`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("1.1.1.1", "8.8.8.8"),
                wifi = WifiNetworkIdentityTuple(ssid = "MyWifi", bssid = "aa:bb:cc", gateway = "192.168.1.1"),
            )
        val key1 = fingerprint.scopeKey()
        val key2 = fingerprint.scopeKey()
        assertEquals(key1, key2)
        assertEquals(64, key1.length) // SHA-256 hex
    }

    @Test
    fun `scopeKey differs for different networks`() {
        val fp1 =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("1.1.1.1"),
                wifi = WifiNetworkIdentityTuple(ssid = "HomeWifi"),
            )
        val fp2 = fp1.copy(wifi = WifiNetworkIdentityTuple(ssid = "OfficeWifi"))
        assertNotEquals(fp1.scopeKey(), fp2.scopeKey())
    }

    // -- NetworkFingerprint.summary --

    @Test
    fun `summary captures wifi network state`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("1.1.1.1"),
                wifi = WifiNetworkIdentityTuple(),
            )
        val summary = fingerprint.summary()
        assertEquals("wifi", summary.transport)
        assertEquals("validated", summary.networkState)
        assertEquals("wifi", summary.identityKind)
        assertEquals("system", summary.privateDnsMode)
        assertEquals(1, summary.dnsServerCount)
    }

    @Test
    fun `summary detects captive portal`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = false,
                captivePortalDetected = true,
                privateDnsMode = "system",
                dnsServers = emptyList(),
                wifi = WifiNetworkIdentityTuple(),
            )
        assertEquals("captive", fingerprint.summary().networkState)
    }

    @Test
    fun `summary detects unvalidated state`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = false,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = emptyList(),
                wifi = WifiNetworkIdentityTuple(),
            )
        assertEquals("unvalidated", fingerprint.summary().networkState)
    }

    @Test
    fun `summary detects cellular identity`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "cellular",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("8.8.8.8"),
                cellular = CellularNetworkIdentityTuple(operatorCode = "25001"),
            )
        assertEquals("cellular", fingerprint.summary().identityKind)
    }

    @Test
    fun `summary private dns mode marks custom when not system`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "dns.google",
                dnsServers = emptyList(),
                wifi = WifiNetworkIdentityTuple(),
            )
        assertEquals("custom", fingerprint.summary().privateDnsMode)
    }

    @Test
    fun `summary dns server count deduplicates`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("1.1.1.1", "1.1.1.1", "8.8.8.8"),
                wifi = WifiNetworkIdentityTuple(),
            )
        assertEquals(2, fingerprint.summary().dnsServerCount)
    }

    // -- NetworkFingerprintSummary.displayLabel --

    @Test
    fun `displayLabel formats wifi summary`() {
        val summary =
            NetworkFingerprintSummary(
                transport = "wifi",
                networkState = "validated",
                identityKind = "wifi",
                privateDnsMode = "system",
                dnsServerCount = 2,
            )
        assertEquals("Wifi · Validated · DNS 2", summary.displayLabel())
    }

    @Test
    fun `displayLabel includes Private DNS for custom mode`() {
        val summary =
            NetworkFingerprintSummary(
                transport = "wifi",
                networkState = "validated",
                identityKind = "wifi",
                privateDnsMode = "custom",
                dnsServerCount = 1,
            )
        assertTrue(summary.displayLabel().contains("Private DNS"))
    }

    // -- canonicalParts --

    @Test
    fun `canonicalParts for wifi includes wifi identity fields`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("1.1.1.1"),
                wifi = WifiNetworkIdentityTuple(ssid = "MyWifi", bssid = "aa:bb", gateway = "192.168.1.1"),
            )
        val parts = fingerprint.canonicalParts()
        assertTrue(parts.contains("wifi"))
        assertTrue(parts.contains("mywifi")) // lowercased
        assertTrue(parts.contains("aa:bb"))
        assertTrue(parts.contains("192.168.1.1"))
    }

    @Test
    fun `canonicalParts for cellular includes operator fields`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "cellular",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("8.8.8.8"),
                cellular =
                    CellularNetworkIdentityTuple(
                        operatorCode = "25001",
                        simOperatorCode = "25001",
                        carrierId = 42,
                        dataNetworkType = "LTE",
                    ),
            )
        val parts = fingerprint.canonicalParts()
        assertTrue(parts.contains("cellular"))
        assertTrue(parts.contains("25001"))
        assertTrue(parts.contains("42"))
        assertTrue(parts.contains("lte"))
    }

    @Test
    fun `canonicalParts for other transport uses fallback identity`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "ethernet",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("1.1.1.1"),
            )
        val parts = fingerprint.canonicalParts()
        assertTrue(parts.contains("other"))
        assertTrue(parts.contains("ethernet"))
    }
}
