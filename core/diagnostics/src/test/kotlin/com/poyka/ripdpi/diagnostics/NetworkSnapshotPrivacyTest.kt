package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSnapshotPrivacyTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `stored wifi snapshot omits network identities and addresses`() {
        val source =
            networkSnapshotModelForTest().copy(
                dnsServers = listOf("192.0.2.53", "198.51.100.53"),
                localAddresses = listOf("10.0.0.2"),
                privateDnsMode = "resolver.private.example",
                wifiDetails =
                    requireNotNull(networkSnapshotModelForTest().wifiDetails).copy(
                        ssid = "HomeNetwork",
                        bssid = "11:22:33:44:55:66",
                        gateway = "10.0.0.1",
                        dhcpServer = "10.0.0.3",
                        ipAddress = "10.0.0.2",
                        subnetMask = "255.255.255.0",
                        networkId = 42,
                    ),
            )

        val stored = source.toStoredNetworkSnapshot()
        val payload = json.encodeToString(NetworkSnapshotModel.serializer(), stored)

        listOf(
            "HomeNetwork",
            "11:22:33:44:55:66",
            "10.0.0.1",
            "10.0.0.2",
            "10.0.0.3",
            "255.255.255.0",
            "192.0.2.53",
            "198.51.100.53",
            "resolver.private.example",
        ).forEach { raw -> assertFalse("Raw network identity escaped: $raw", payload.contains(raw)) }
        assertEquals("strict", stored.privateDnsMode)
        assertEquals(2, stored.dnsServers.size)
        assertEquals(1, stored.localAddresses.size)
        assertEquals("redacted", stored.wifiDetails?.ssid)
        assertNull(stored.wifiDetails?.networkId)
        assertEquals(source.wifiDetails?.frequencyMhz, stored.wifiDetails?.frequencyMhz)
        assertEquals(source.publicIp, stored.publicIp)
        assertTrue(stored.toRedactedSummary().dnsServers.contains("2"))
    }

    @Test
    fun `stored cellular snapshot omits carrier and operator identities`() {
        val source =
            networkSnapshotModelForTest().copy(
                transport = "cellular",
                wifiDetails = null,
                cellularDetails =
                    CellularNetworkDetails(
                        carrierName = "Secret Carrier",
                        simOperatorName = "Secret SIM",
                        networkOperatorName = "Secret Operator",
                        operatorCode = "99901",
                        simOperatorCode = "99902",
                        carrierId = 12345,
                        simCarrierId = 12346,
                        networkCountryIso = "ge",
                        dataNetworkType = "LTE",
                        signalLevel = 3,
                    ),
            )

        val stored = source.toStoredNetworkSnapshot()
        val payload = json.encodeToString(NetworkSnapshotModel.serializer(), stored)

        listOf("Secret Carrier", "Secret SIM", "Secret Operator", "99901", "99902", "12345", "12346")
            .forEach { raw -> assertFalse("Raw cellular identity escaped: $raw", payload.contains(raw)) }
        assertEquals("redacted", stored.cellularDetails?.carrierName)
        assertNull(stored.cellularDetails?.carrierId)
        assertEquals("LTE", stored.cellularDetails?.dataNetworkType)
        assertEquals(3, stored.cellularDetails?.signalLevel)
    }
}
