package com.poyka.ripdpi.services

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFingerprintMapperTest {
    private val mapper = NetworkFingerprintMapper()

    @Test
    fun `maps wifi snapshot into normalized fingerprint`() {
        val result =
            mapper.map(
                CapturedNetworkSnapshot(
                    transports = setOf(CapturedTransport.Wifi),
                    networkValidated = true,
                    privateDnsServerName = " dns.example.test ",
                    dnsServers = listOf("8.8.8.8 ", "1.1.1.1"),
                    wifi =
                        CapturedWifiIdentity(
                            ssid = "\"Cafe Wifi\"",
                            bssid = "AA:BB:CC:DD:EE:FF",
                            gatewayIpv4 = 0x0101A8C0.toInt(),
                        ),
                    metered = true,
                ),
            )

        assertEquals("wifi", result.transport)
        assertTrue(result.networkValidated)
        assertEquals("dns.example.test", result.privateDnsMode)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), result.dnsServers)
        assertEquals("cafe wifi", result.wifi?.ssid)
        assertEquals("aa:bb:cc:dd:ee:ff", result.wifi?.bssid)
        assertEquals("192.168.1.1", result.wifi?.gateway)
        assertEquals(true, result.metered)
    }

    @Test
    fun `maps unknown wifi markers to unknown placeholders`() {
        val result =
            mapper.map(
                CapturedNetworkSnapshot(
                    transports = setOf(CapturedTransport.Wifi),
                    wifi =
                        CapturedWifiIdentity(
                            ssid = "<unknown ssid>",
                            bssid = "02:00:00:00:00:00",
                        ),
                ),
            )

        assertEquals("unknown", result.wifi?.ssid)
        assertEquals("unknown", result.wifi?.bssid)
        assertEquals("unknown", result.wifi?.gateway)
        assertEquals("system", result.privateDnsMode)
    }

    @Test
    fun `maps cellular snapshot into normalized identity`() {
        val result =
            mapper.map(
                CapturedNetworkSnapshot(
                    transports = setOf(CapturedTransport.Cellular),
                    cellular =
                        CapturedCellularIdentity(
                            networkOperator = " 25001 ",
                            simOperator = " 25020 ",
                            carrierId = 7,
                            dataNetworkType = TelephonyManager.NETWORK_TYPE_LTE,
                            roaming = true,
                        ),
                ),
            )

        assertEquals("cellular", result.transport)
        assertEquals("25001", result.cellular?.operatorCode)
        assertEquals("25020", result.cellular?.simOperatorCode)
        assertEquals(7, result.cellular?.carrierId)
        assertEquals("lte", result.cellular?.dataNetworkType)
        assertEquals(true, result.cellular?.roaming)
        assertNull(result.wifi)
    }

    @Test
    fun `treats missing capabilities as unknown transport and ignores identity payloads`() {
        val result =
            mapper.map(
                CapturedNetworkSnapshot(
                    transports = null,
                    wifi = CapturedWifiIdentity(ssid = "ssid"),
                    cellular = CapturedCellularIdentity(networkOperator = "25001"),
                ),
            )

        assertEquals("unknown", result.transport)
        assertNull(result.wifi)
        assertNull(result.cellular)
        assertTrue(result.dnsServers.isEmpty())
    }
}
