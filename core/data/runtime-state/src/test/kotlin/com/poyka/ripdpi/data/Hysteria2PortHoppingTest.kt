package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class Hysteria2PortHoppingTest {
    @Test
    fun `port unions merge overlap adjacency duplicates and preserve the full domain`() {
        assertEquals("443,5000-5005,65535", normalizeHysteria2HopPorts("5003-5005,443,5000-5003,443,65535"))
        assertEquals("1-65535", normalizeHysteria2HopPorts((1..65535).joinToString(",")))
    }

    @Test
    fun `invalid ports cannot become scalar or partial configurations`() {
        listOf("", "0", "65536", "10-9", "1,,2", "-1", "1-", "1:2", "1/2", "a", "1-2-3", "+5").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { normalizeHysteria2HopPorts(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { hysteria2PortHopping(null, 5000) }
        assertThrows(IllegalArgumentException::class.java) { hysteria2PortHopping(null, intervalMaxMillis = 5000) }
        assertNull(hysteria2PortHopping(null))
    }

    @Test
    fun `cadence defaults and equal bounds have a single representation`() {
        val policy = Hysteria2PortHopping("443,5000-5001")
        assertEquals(policy, hysteria2PortHopping("5001,443,5000"))
        assertEquals(policy, hysteria2PortHopping("443,5000-5001", 30000, 30000))
        assertEquals(443, policy.firstPort)
        assertThrows(IllegalArgumentException::class.java) { hysteria2PortHopping("443", 10000, 5000) }
        assertThrows(IllegalArgumentException::class.java) { Hysteria2PortHopping("443", 4999) }
        assertThrows(IllegalArgumentException::class.java) {
            Hysteria2PortHopping(
                "443",
                Hysteria2MaximumHopIntervalMillis + 1,
            )
        }
    }

    @Test
    fun `duration conversion preserves exact milliseconds and rejects overflow and fine precision`() {
        mapOf(
            "5s" to 5000L,
            "5.s" to 5000L,
            ".5h" to 1_800_000L,
            "5.001s" to 5001L,
            "1h2m3.004s" to 3_723_004L,
            "5000000us" to 5000L,
            "5000000000ns" to 5000L,
            "9223372036854ms" to Hysteria2MaximumHopIntervalMillis,
        ).forEach { (raw, expected) -> assertEquals(raw, expected, parseHysteria2HopDuration(raw)) }
        listOf(
            "5",
            "4.999s",
            "5.0001s",
            "9223372036855ms",
            "NaNs",
            "Infinitys",
            "-5s",
            "+5s",
            "1e5s",
            "5 s",
            "5s!",
        ).forEach {
            assertThrows(it, IllegalArgumentException::class.java) { parseHysteria2HopDuration(it) }
        }
    }

    @Test
    fun `stored model requires canonical valid data on decode`() {
        val policy = Hysteria2PortHopping("443,5000-5010", 5000, 15000)
        val encoded = RipDpiJson.encodeToString(Hysteria2PortHopping.serializer(), policy)
        assertEquals(policy, RipDpiJson.decodeFromString(Hysteria2PortHopping.serializer(), encoded))
        listOf(
            """{"ports":"5000,443"}""",
            """{"ports":"443","intervalMillis":30000,"intervalMaxMillis":30000}""",
            """{"ports":"443","intervalMillis":-1}""",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                RipDpiJson.decodeFromString(Hysteria2PortHopping.serializer(), raw)
            }
        }
    }
}
