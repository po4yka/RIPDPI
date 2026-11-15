package com.poyka.ripdpi.core.detection.checker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class GeoIpProviderParserTest {
    @Test
    fun `provider echo must identify the requested address`() {
        val snapshot = GeoIpProviderParser.parse(JSONObject("""{"ip":"8.8.8.8"}"""))
        assertThrows(IOException::class.java) { GeoIpProviderParser.validate(snapshot, "9.9.9.9") }
        val ipv6 = GeoIpProviderParser.parse(JSONObject("""{"ip":"2001:4860:4860::8888"}"""))
        assertEquals(ipv6.ip, GeoIpProviderParser.validate(ipv6, "2001:4860:4860:0:0:0:0:8888").ip)
    }

    @Test
    fun `conflicting recognized identity aliases are rejected`() {
        val json = JSONObject("""{"query":"8.8.8.8","ip":"9.9.9.9"}""")
        assertThrows(IOException::class.java) { GeoIpProviderParser.parse(json) }
        assertThrows(IOException::class.java) { GeoIpProviderParser.seedIp(json) }
    }

    @Test
    fun `a valid identity alias cannot hide a malformed alias`() {
        listOf(JSONObject.NULL, JSONObject(), 42, true, "not-an-address").forEach { malformed ->
            listOf("query", "ip").forEach { key ->
                val other = if (key == "query") "ip" else "query"
                val json = JSONObject().put(key, malformed).put(other, "8.8.8.8")
                assertThrows(IOException::class.java) { GeoIpProviderParser.parse(json) }
                assertThrows(IOException::class.java) { GeoIpProviderParser.seedIp(json) }
            }
        }
    }

    @Test
    fun `equivalent numeric IPv6 aliases identify the same address`() {
        val json = JSONObject("""{"query":"2001:4860:4860:0:0:0:0:8888","ip":"2001:4860:4860::8888"}""")
        assertEquals("2001:4860:4860::8888", GeoIpProviderParser.parse(json).ip)
        assertEquals("2001:4860:4860::8888", GeoIpProviderParser.seedIp(json))
    }

    @Test
    fun `seed rejects missing non numeric and local addresses`() {
        listOf("{}", """{"ip":"example.com"}""", """{"ip":"127.0.0.1"}""", """{"ip":"198.18.0.53"}""").forEach {
            assertThrows(IOException::class.java) { GeoIpProviderParser.seedIp(JSONObject(it)) }
        }
    }

    @Test
    fun `provider API failure cannot become an opinion`() {
        listOf(
            """{"success":false,"ip":"8.8.8.8"}""",
            """{"status":"fail","ip":"8.8.8.8"}""",
            """{"error":"quota","ip":"8.8.8.8"}""",
        ).forEach {
            assertThrows(IOException::class.java) { GeoIpProviderParser.parse(JSONObject(it)) }
        }
    }

    @Test
    fun `partial negative proxy flags remain unknown`() {
        val partial = GeoIpProviderParser.parse(JSONObject("""{"ip":"8.8.8.8","privacy":{"is_proxy":false}}"""))
        assertNull(partial.isProxy)
        val complete =
            GeoIpProviderParser.parse(
                JSONObject("""{"ip":"8.8.8.8","privacy":{"is_proxy":false,"is_vpn":false,"is_tor":false}}"""),
            )
        assertEquals(false, complete.isProxy)
    }

    @Test
    fun `current IPBot classification and network fields are supported`() {
        val snapshot =
            GeoIpProviderParser.parse(
                JSONObject(
                    """
                    {
                      "ip": "8.8.8.8",
                      "location": {"country":"United States","country_code":"US"},
                      "network": {"asn":"AS15169","org":"Google LLC"},
                      "classification": {"is_proxy":true,"is_datacenter":false}
                    }
                    """.trimIndent(),
                ),
                providerName = "ipbot.com",
            )
        assertTrue(snapshot.isProxy == true)
        assertEquals(false, snapshot.isHosting)
        assertEquals("AS15169", snapshot.asn)
        assertEquals("Google LLC", snapshot.org)
        assertEquals("US", snapshot.countryCode)
    }

    @Test
    fun `ASN parsing removes operator suffix and rejects non numeric values`() {
        val numeric = GeoIpProviderParser.parse(JSONObject("""{"ip":"8.8.8.8","asn":"AS15169 Google LLC"}"""))
        assertEquals("AS15169", numeric.asn)
        listOf("0", "4294967296", "ASinvalid").forEach {
            val snapshot = GeoIpProviderParser.parse(JSONObject().put("ip", "8.8.8.8").put("asn", it))
            assertEquals("N/A", snapshot.asn)
        }
    }
}
