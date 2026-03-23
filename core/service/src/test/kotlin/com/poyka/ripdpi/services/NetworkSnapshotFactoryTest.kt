package com.poyka.ripdpi.services

import android.telephony.ServiceState
import android.telephony.TelephonyManager
import com.poyka.ripdpi.data.CellularNetworkIdentityTuple
import com.poyka.ripdpi.data.NativeCellularSnapshot
import com.poyka.ripdpi.data.NativeWifiSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSnapshotFactoryTest {
    @Test
    fun `wifi frequency maps to normalized band`() {
        assertEquals("2.4ghz", describeWifiBand(2412))
        assertEquals("5ghz", describeWifiBand(5180))
        assertEquals("6ghz", describeWifiBand(5955))
    }

    @Test
    fun `wifi channel width and standard reuse diagnostics vocabulary`() {
        assertEquals("80 MHz", describeWifiChannelWidth(2))
        assertEquals("320 MHz", describeWifiChannelWidth(5))
        assertEquals("802.11ax", describeWifiStandard(6))
        assertEquals("802.11be", describeWifiStandard(8))
    }

    @Test
    fun `cellular mappings normalize mobile type and service state`() {
        assertEquals("LTE", describeMobileNetworkType(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("NR", canonicalMobileNetworkType("nr"))
        assertEquals("in_service", describeServiceState(ServiceState.STATE_IN_SERVICE))
        assertEquals("4g", cellularGeneration("LTE"))
        assertEquals("5g", cellularGeneration("nr"))
    }

    @Test
    fun `unsupported mapping values fall back to unknown`() {
        assertEquals("unknown", describeWifiBand(null))
        assertEquals("unknown", describeWifiChannelWidth(99))
        assertEquals("unknown", describeWifiStandard(null))
        assertEquals("unknown", describeMobileNetworkType(null))
        assertEquals("unknown", describeServiceState(-1))
        assertEquals("unknown", canonicalMobileNetworkType(""))
        assertEquals("unknown", cellularGeneration("satellite"))
    }

    @Test
    fun `snapshot builder keeps wifi analytics compact and privacy preserving`() {
        val snapshot =
            buildNativeNetworkSnapshot(
                fingerprint = wifiFingerprint(),
                txBytes = 100L,
                rxBytes = 200L,
                wifi =
                    NativeWifiSnapshot(
                        frequencyBand = "5ghz",
                        ssidHash = "cafebabe",
                        frequencyMhz = 5180,
                        rssiDbm = -58,
                        linkSpeedMbps = 866,
                        rxLinkSpeedMbps = 780,
                        txLinkSpeedMbps = 720,
                        channelWidth = "80 MHz",
                        wifiStandard = "802.11ax",
                    ),
                cellular = null,
                mtu = 1500,
                capturedAtMs = 1700000000000L,
            )

        assertEquals("wifi", snapshot.transport)
        assertNull(snapshot.cellular)
        assertEquals("cafebabe", snapshot.wifi?.ssidHash)
        assertEquals(5180, snapshot.wifi?.frequencyMhz)
        assertEquals(-58, snapshot.wifi?.rssiDbm)
        assertEquals("80 MHz", snapshot.wifi?.channelWidth)
        assertEquals("802.11ax", snapshot.wifi?.wifiStandard)
    }

    @Test
    fun `snapshot builder includes cellular radio and signal analytics`() {
        val snapshot =
            buildNativeNetworkSnapshot(
                fingerprint = cellularFingerprint(),
                txBytes = 100L,
                rxBytes = 200L,
                wifi = null,
                cellular =
                    NativeCellularSnapshot(
                        generation = "5g",
                        roaming = true,
                        operatorCode = "25001",
                        dataNetworkType = "NR",
                        serviceState = "in_service",
                        carrierId = 42,
                        signalLevel = 4,
                        signalDbm = -95,
                    ),
                mtu = 1420,
                capturedAtMs = 1700000000000L,
            )

        assertEquals("cellular", snapshot.transport)
        assertNull(snapshot.wifi)
        assertTrue(snapshot.cellular?.roaming == true)
        assertEquals("NR", snapshot.cellular?.dataNetworkType)
        assertEquals("in_service", snapshot.cellular?.serviceState)
        assertEquals(42, snapshot.cellular?.carrierId)
        assertEquals(4, snapshot.cellular?.signalLevel)
        assertEquals(-95, snapshot.cellular?.signalDbm)
    }

    @Test
    fun `snapshot builder returns network none when fingerprint missing`() {
        val snapshot =
            buildNativeNetworkSnapshot(
                fingerprint = null,
                txBytes = 5L,
                rxBytes = 7L,
                wifi = NativeWifiSnapshot(ssidHash = "ignored"),
                cellular = NativeCellularSnapshot(operatorCode = "ignored"),
                mtu = 1280,
                capturedAtMs = 1700000000000L,
            )

        assertEquals("none", snapshot.transport)
        assertNull(snapshot.wifi)
        assertNull(snapshot.cellular)
        assertFalse(snapshot.validated)
        assertEquals(1280, snapshot.mtu)
    }

    private fun wifiFingerprint(): NetworkFingerprint =
        NetworkFingerprint(
            transport = "wifi",
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = listOf("1.1.1.1"),
            wifi =
                WifiNetworkIdentityTuple(
                    ssid = "ripdpi-lab",
                    bssid = "aa:bb:cc:dd:ee:ff",
                    gateway = "192.0.2.1",
                ),
            metered = false,
        )

    private fun cellularFingerprint(): NetworkFingerprint =
        NetworkFingerprint(
            transport = "cellular",
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = listOf("9.9.9.9"),
            cellular =
                CellularNetworkIdentityTuple(
                    operatorCode = "25001",
                    simOperatorCode = "25001",
                    carrierId = 42,
                    dataNetworkType = "nr",
                    roaming = true,
                ),
            metered = true,
        )
}
