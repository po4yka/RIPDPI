package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.Hysteria2PortHopping
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Hysteria2HoppingImportTest {
    @Test
    fun `singbox range list and randomized intervals preserve nominal port without widening destination set`() {
        val profile =
            singBoxProfile(
                """"server_port":443,"server_ports":["5002:5005","5000:5003","8443"],""" +
                    """"hop_interval":"5.001s","hop_interval_max":"15s",""",
            )
        assertEquals(443, profile.serverPort)
        assertEquals(Hysteria2PortHopping("5000-5005,8443", 5001, 15000), profile.portHopping)
        assertEquals("peer.example", profile.serverName)
        assertEquals("fixture-obfs", profile.obfsPassword)
    }

    @Test
    fun `singbox range alone supplies required scalar metadata and default cadence`() {
        val profile = singBoxProfile(""""server_ports":"5000:5002",""")
        assertEquals(5000, profile.serverPort)
        assertEquals(Hysteria2PortHopping("5000-5002"), profile.portHopping)
        assertNull(singBoxProfile(""""server_port":443,""").portHopping)
    }

    @Test
    fun `invalid singbox hopping refuses the complete payload rather than returning a scalar profile`() {
        listOf(
            """"server_ports":[],""",
            """"server_ports":[443],""",
            """"server_ports":["65536"],""",
            """"server_ports":["5002:5000"],""",
            """"server_ports":["5000-5002"],""",
            """"server_ports":["5000:5002"],"hop_interval":"4s",""",
            """"server_ports":["5000:5002"],"hop_interval":"5.0001s",""",
            """"server_ports":["5000:5002"],"hop_interval_max":"60s",""",
            """"hop_interval":"30s",""",
            """"server_ports":null,""",
        ).forEach { fields ->
            assertTrue(fields, SingBoxSubscriptionParser.parse(singBox(fields), "group") is SingBoxParseResult.Error)
        }
    }

    @Test
    fun `bundle extras agree after normalization and preserve fixed interval`() {
        val profile = bundleProfile(""""server_ports":["5000:5002"],"hop_interval":"30000ms",""", "5000-5002", "30s")
        assertEquals(Hysteria2PortHopping("5000-5002"), profile.portHopping)
        assertEquals(profile.portHopping, bundleProfile("", "5000:5002", "30s").portHopping)
    }

    @Test
    fun `conflicting malformed or unmatched bundle extras reject the recognized bundle atomically`() {
        val standard = """"server_ports":["5000:5002"],"hop_interval":"30s","""
        listOf(
            bundle(standard, "5000:5003", "30s"),
            bundle(standard, "5000:5002", "5s"),
            bundle(standard, "5000:5002", "4s"),
            bundle(standard, "5000:5002", "30s").replace("\"interval\":\"30s\"", "\"interval\":30"),
            bundle(
                standard,
                "5000:5002",
                "30s",
            ).replace("\"hysteria_extras\":{\"hy\"", "\"hysteria_extras\":{\"missing\""),
        ).forEach { payload ->
            val parsed = SingBoxSubscriptionParser.parse(payload, "group")
            assertTrue(parsed is SingBoxParseResult.Error)
            assertTrue((parsed as SingBoxParseResult.Error).recognizedBundle)
        }
    }

    @Test
    fun `mihomo documented union separators and random seconds map to one policy`() {
        val parsed = ClashSubscriptionParser.parse(clash("ports: 5000-5002/8443,5002\n    hop-interval: 5-15"), "group")
        assertTrue(parsed.errors.isEmpty())
        val profile = parsed.profiles.single() as ProxyProfile.Hysteria2
        assertEquals(443, profile.serverPort)
        assertEquals(Hysteria2PortHopping("5000-5002,8443", 5000, 15000), profile.portHopping)
        val withoutPort =
            ClashSubscriptionParser.parse(
                clash("ports: 5000-5002").replace("    port: 443\n", ""),
                "group",
            )
        assertEquals(5000, (withoutPort.profiles.single() as ProxyProfile.Hysteria2).serverPort)
    }

    @Test
    fun `mihomo malformed hopping cannot become a selectable scalar node`() {
        listOf(
            "ports: 5002-5000",
            "ports: 0",
            "ports: [5000,5001]",
            "hop-interval: 30",
            "ports: 5000-5002\n    hop-interval: 4",
            "ports: 5000-5002\n    hop-interval: 30-5",
        ).forEach { fields ->
            val parsed = ClashSubscriptionParser.parse(clash(fields), "group")
            assertTrue(parsed.profiles.isEmpty())
            assertFalse(parsed.errors.isEmpty())
        }
    }

    @Test
    fun `URI safely parses percent encoded authentication IPv6 unions and exact interval extensions`() {
        val profile =
            ProxyUriCodec.parse(
                "hysteria2://u%3Ap%40ss@[::1]:443,5000-5002/?sni=peer.example&" +
                    "minHopInterval=5.001s&maxHopInterval=10s#name%2520",
            ) as ProxyProfile.Hysteria2
        assertEquals("u:p@ss", profile.password)
        assertEquals("::1", profile.server)
        assertEquals("name%20", profile.displayName)
        assertEquals(Hysteria2PortHopping("443,5000-5002", 5001, 10000), profile.portHopping)
        val restored = ProxyUriCodec.parse(ProxyUriCodec.encode(profile)) as ProxyProfile.Hysteria2
        assertEquals(profile.copy(id = restored.id), restored)
    }

    @Test
    fun `URI defaults scalar port and preserves explicitly configured singleton policy`() {
        val scalar = ProxyUriCodec.parse("hy2://fixture@peer.example") as ProxyProfile.Hysteria2
        assertEquals(443, scalar.serverPort)
        assertNull(scalar.portHopping)
        val singleton = ProxyUriCodec.parse("hy2://fixture@peer.example?hopInterval=30") as ProxyProfile.Hysteria2
        assertEquals(Hysteria2PortHopping("443"), singleton.portHopping)
        assertEquals(
            singleton.portHopping,
            (ProxyUriCodec.parse(ProxyUriCodec.encode(singleton)) as ProxyProfile.Hysteria2).portHopping,
        )
    }

    @Test
    fun `URI rejects ambiguous authority conflicting repeated or unsupported hopping fields`() {
        listOf(
            "hy2://fixture@peer.example:0",
            "hy2://fixture@peer.example:5002-5000",
            "hy2://fixture@peer.example:443?hopInterval=5s&minHopInterval=5s&maxHopInterval=10s",
            "hy2://fixture@peer.example:443?minHopInterval=5s",
            "hy2://fixture@peer.example:443?hopInterval=5s&hopInterval=10s",
            "hy2://fixture@peer.example:443?hopInterval=5s&%68opInterval=10s",
            "hy2://fixture@peer.example:443?mport=5000-5002",
            "hy2://fixture@peer.example:443?hopInterval=5.0001s",
            "hy2://fixture@peer.example:443/path",
            "hy2://fixture@peer%2eexample:443",
            "hy2://fixture@unexpected@peer.example:443",
        ).forEach { assertNull(it, ProxyUriCodec.parse(it)) }
    }

    private fun singBoxProfile(fields: String): ProxyProfile.Hysteria2 =
        (
            SingBoxSubscriptionParser.parse(
                singBox(fields),
                "group",
            ) as SingBoxParseResult.Success
        ).profiles.single() as ProxyProfile.Hysteria2

    private fun singBox(fields: String) =
        """{"type":"hysteria2","tag":"hy","server":"peer.example",$fields""" +
            """"password":"fixture","tls":{"server_name":"peer.example"},""" +
            """"obfs":{"type":"salamander","password":"fixture-obfs"}}"""

    private fun bundle(
        fields: String,
        ports: String,
        interval: String,
    ): String {
        val outbound = singBox("\"server_port\":443,$fields")
        return """{"outbounds":[$outbound],"ripdpi":{"schema_version":1,""" +
            """"hysteria_extras":{"hy":{"port_hopping":{"ports":"$ports","interval":"$interval"}}}}}"""
    }

    private fun bundleProfile(
        fields: String,
        ports: String,
        interval: String,
    ): ProxyProfile.Hysteria2 =
        (
            SingBoxSubscriptionParser.parse(
                bundle(fields, ports, interval),
                "group",
            ) as SingBoxParseResult.Success
        ).profiles.single() as ProxyProfile.Hysteria2

    private fun clash(fields: String) =
        "proxies:\n  - name: hy\n    type: hysteria2\n    server: peer.example\n" +
            "    port: 443\n    password: fixture\n    $fields\n"
}
