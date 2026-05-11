package com.poyka.ripdpi.core.detection.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BeaconDbClientTest {
    @Test
    fun `valid beacondb response resolves country`() {
        val client =
            BeaconDbClient(
                countryResolver = { _, _ -> "ru" },
                request = {
                    BeaconDbClient.HttpResult(
                        code = 200,
                        body = """{"location":{"lat":55.75,"lng":37.61},"accuracy":100}""",
                    )
                },
            )

        val result = client.lookup(listOf(cellTower()), emptyList())

        assertEquals("RU", result.countryIso)
        assertEquals("BeaconDB: exact match", result.summary)
    }

    @Test
    fun `beacondb failure returns non fatal summary`() {
        val client =
            BeaconDbClient(
                countryResolver = { _, _ -> null },
                request = { throw IOException("offline") },
            )

        val result = client.lookup(listOf(cellTower()), emptyList())

        assertEquals(null, result.countryIso)
        assertEquals("BeaconDB: offline", result.summary)
    }

    @Test
    fun `request body includes cell and wifi candidates`() {
        val client = BeaconDbClient(countryResolver = { _, _ -> null })

        val body =
            client.buildRequestBody(
                cellTowers = listOf(cellTower()),
                wifiAccessPoints = listOf(WifiAccessPointSignal("aa:bb:cc:dd:ee:ff", -44, 2412)),
            )

        assertTrue(body.contains(""""cellTowers""""))
        assertTrue(body.contains(""""wifiAccessPoints""""))
        assertTrue(body.contains(""""mobileCountryCode":250"""))
        assertTrue(body.contains(""""macAddress":"aa:bb:cc:dd:ee:ff""""))
    }

    private fun cellTower(): CellTowerSignal =
        CellTowerSignal(
            radio = "lte",
            mcc = "250",
            mnc = "01",
            areaCode = 100,
            cellId = 200,
            signalStrength = -70,
        )
}
