package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure system Private DNS status mapping. These exercise
 * [mapLinkPropertiesToStatus] without touching Android APIs so the tri-state
 * logic is verifiable without a device.
 */
class SystemPrivateDnsDetectorTest {
    @Test
    fun mapLinkPropertiesToStatus_present_when_server_name_set() {
        val status =
            mapLinkPropertiesToStatus(
                privateDnsServerName = "dns.example.org",
                apiSupported = true,
            )
        assertEquals(SystemPrivateDnsStatus.PRESENT, status)
    }

    @Test
    fun mapLinkPropertiesToStatus_absent_when_server_name_blank() {
        assertEquals(
            SystemPrivateDnsStatus.ABSENT,
            mapLinkPropertiesToStatus(privateDnsServerName = null, apiSupported = true),
        )
        assertEquals(
            SystemPrivateDnsStatus.ABSENT,
            mapLinkPropertiesToStatus(privateDnsServerName = "", apiSupported = true),
        )
        assertEquals(
            SystemPrivateDnsStatus.ABSENT,
            mapLinkPropertiesToStatus(privateDnsServerName = "   ", apiSupported = true),
        )
    }

    @Test
    fun mapLinkPropertiesToStatus_unknown_when_api_unsupported() {
        // Below API 29 the resolver name cannot be read, even if one were configured.
        assertEquals(
            SystemPrivateDnsStatus.UNKNOWN,
            mapLinkPropertiesToStatus(privateDnsServerName = "dns.example.org", apiSupported = false),
        )
        assertEquals(
            SystemPrivateDnsStatus.UNKNOWN,
            mapLinkPropertiesToStatus(privateDnsServerName = null, apiSupported = false),
        )
    }

    @Test
    fun mapPrivateDnsModeToStatus_classifies_captured_strings() {
        assertEquals(SystemPrivateDnsStatus.ABSENT, mapPrivateDnsModeToStatus("system"))
        assertEquals(SystemPrivateDnsStatus.ABSENT, mapPrivateDnsModeToStatus("System"))
        assertEquals(SystemPrivateDnsStatus.PRESENT, mapPrivateDnsModeToStatus("dns.example.org"))
        assertEquals(SystemPrivateDnsStatus.UNKNOWN, mapPrivateDnsModeToStatus(""))
        assertEquals(SystemPrivateDnsStatus.UNKNOWN, mapPrivateDnsModeToStatus("   "))
        assertEquals(SystemPrivateDnsStatus.UNKNOWN, mapPrivateDnsModeToStatus(null))
    }

    @Test
    fun wireValues_are_stable_and_round_trip() {
        assertEquals("system_private_dns_present", SystemPrivateDnsStatus.PRESENT.wireValue)
        assertEquals("ignored_for_vpn_policy", SystemPrivateDnsStatus.ABSENT.wireValue)
        assertEquals("unknown", SystemPrivateDnsStatus.UNKNOWN.wireValue)

        SystemPrivateDnsStatus.entries.forEach { status ->
            assertEquals(status, SystemPrivateDnsStatus.fromWireValue(status.wireValue))
        }
        assertEquals(SystemPrivateDnsStatus.UNKNOWN, SystemPrivateDnsStatus.fromWireValue("nonsense"))
        assertEquals(SystemPrivateDnsStatus.UNKNOWN, SystemPrivateDnsStatus.fromWireValue(null))
    }
}
