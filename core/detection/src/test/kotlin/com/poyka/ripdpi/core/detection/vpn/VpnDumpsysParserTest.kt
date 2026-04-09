package com.poyka.ripdpi.core.detection.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnDumpsysParserTest {
    @Test
    fun `empty output is unavailable`() {
        assertTrue(VpnDumpsysParser.isUnavailable(""))
    }

    @Test
    fun `permission denial is unavailable`() {
        assertTrue(VpnDumpsysParser.isUnavailable("Permission Denial: can't dump vpn_management"))
    }

    @Test
    fun `service not found is unavailable`() {
        assertTrue(VpnDumpsysParser.isUnavailable("Can't find service: vpn_management"))
    }

    @Test
    fun `parseVpnManagement extracts active package`() {
        val output = "  Active package name: com.v2ray.ang\n  Other info"
        val records = VpnDumpsysParser.parseVpnManagement(output)
        assertEquals(1, records.size)
        assertEquals("com.v2ray.ang", records[0].packageName)
    }

    @Test
    fun `parseVpnManagement returns empty for unavailable`() {
        assertTrue(VpnDumpsysParser.parseVpnManagement("").isEmpty())
    }

    @Test
    fun `parseVpnServices extracts service record`() {
        val output = "  * ServiceRecord{abc1234 u0 com.v2ray.ang/.VpnService}"
        val records = VpnDumpsysParser.parseVpnServices(output)
        assertEquals(1, records.size)
        assertEquals("com.v2ray.ang", records[0].packageName)
        assertEquals(".VpnService", records[0].serviceName)
    }

    @Test
    fun `parseVpnServices filters non-vpn services`() {
        val output = "  * ServiceRecord{abc1234 u0 com.example/.NotVpn}"
        val records = VpnDumpsysParser.parseVpnServices(output)
        assertTrue(records.isEmpty())
    }

    @Test
    fun `parseVpnServices deduplicates records`() {
        val output =
            """
            * ServiceRecord{abc u0 com.v2ray.ang/.VpnService}
            * ServiceRecord{abc u0 com.v2ray.ang/.VpnService}
            """.trimIndent()
        val records = VpnDumpsysParser.parseVpnServices(output)
        assertEquals(1, records.size)
    }
}
