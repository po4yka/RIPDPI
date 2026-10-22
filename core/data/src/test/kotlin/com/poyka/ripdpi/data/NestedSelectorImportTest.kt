package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.FailoverPolicy
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NestedSelectorImportTest {
    @Test
    fun `current deployment materializer preserves core credentials and drops research candidates`() {
        val payload = requireNotNull(javaClass.classLoader?.getResource("deployment-generated/bundle.json")).readText()
        val result = SelectorUrltestGroupImport.import(payload, "fleet")
        assertTrue("$result", result is SelectorUrltestImportResult.Success)
        result as SelectorUrltestImportResult.Success
        val members = requireNotNull(result.group).members
        assertEquals(4, members.size)
        assertEquals("select", result.group?.name)
        assertEquals("auto", result.defaultMemberTag)
        assertEquals(2, result.profiles.filterIsInstance<ProxyProfile.RawConfig>().size)
        val reality = members.filterIsInstance<ProxyProfile.VlessReality>().single()
        assertEquals("xtls-rprx-vision", reality.flow)
        assertEquals("11111111-2222-4333-8444-555555555555", reality.uuid)
        assertEquals("0102030405060708", reality.realityShortId)
        assertEquals("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA", reality.realityPublicKey)
        assertEquals("reality.example.test", reality.serverName)
        assertEquals("chrome", reality.fingerprint)
        val xhttp = members.filterIsInstance<ProxyProfile.Vless>()
        assertTrue(xhttp.all { it.uuid == reality.uuid })
        assertEquals(listOf(8443, 2083), xhttp.map { it.serverPort })
        assertEquals(listOf("direct.example.test", "alternate.example.test"), xhttp.map { it.serverName })
        assertEquals(xhttp.map { it.serverName }, xhttp.map { it.xhttpHost })
        assertTrue(xhttp.all { it.xhttpPath == "/fixture-sync" && it.xhttpMode == "stream-up" })
        val hysteria = members.filterIsInstance<ProxyProfile.Hysteria2>().single()
        assertEquals("fixture-device:synthetic-hysteria", hysteria.password)
        assertEquals("synthetic-salamander", hysteria.obfsPassword)
        assertEquals(false, hysteria.insecure)
    }

    @Test
    fun `backend mixed research bundle keeps the outer selector and supported candidates`() {
        val result = SelectorUrltestGroupImport.import(bundle(), "fleet")
        assertTrue("$result", result is SelectorUrltestImportResult.Success)
        result as SelectorUrltestImportResult.Success
        assertEquals("select", result.group?.name)
        assertEquals(listOf("core"), result.group?.members?.map { it.displayName })
        assertEquals(listOf("core"), result.memberOrder)
        assertTrue(result.failoverPolicy is FailoverPolicy.Urltest)
    }

    @Test
    fun `unsupported default is not silently replaced by a supported candidate`() {
        assertError(bundle().replace("\"default\":\"auto\"", "\"default\":\"research\""))
    }

    @Test
    fun `nested default must resolve to a supported candidate`() {
        assertError(
            bundle()
                .replace("\"default\":\"auto\"", "\"default\":\"research\"")
                .replace("\"outbounds\":[\"snell\"]", "\"outbounds\":[\"core\",\"snell\"]"),
        )
    }

    @Test
    fun `urltest cannot hide an unsupported nested default`() {
        assertError(
            bundle()
                .replace("\"outbounds\":[\"core\"]", "\"outbounds\":[\"research\"]")
                .replace("\"outbounds\":[\"snell\"]", "\"outbounds\":[\"core\",\"snell\"]"),
        )
    }

    @Test
    fun `selected empty group cannot fall back to a different sibling`() {
        assertError(bundle().replace("\"outbounds\":[\"core\"]", "\"outbounds\":[]"))
    }

    @Test
    fun `cycles and missing nested references reject the bundle`() {
        assertError(bundle().replace("\"outbounds\":[\"snell\"]", "\"outbounds\":[\"select\"]"))
        assertError(bundle().replace("\"outbounds\":[\"snell\"]", "\"outbounds\":[\"missing\"]"))
    }

    @Test
    fun `duplicate tags reject ambiguous endpoint resolution`() {
        assertError(bundle().replace("\"tag\":\"snell\"", "\"tag\":\"core\""))
    }

    @Test
    fun `unknown urltest members cannot bypass selector validation`() {
        assertError(bundle().replace("\"outbounds\":[\"core\"]", "\"outbounds\":[\"missing\"]"))
    }

    @Test
    fun `unsupported only selector is rejected`() {
        assertError(
            bundle()
                .replace("\"core\",\"auto\",\"research\"", "\"research\"")
                .replace("\"default\":\"auto\"", "\"default\":\"research\""),
        )
    }

    @Test(timeout = 2_000L)
    fun `shared graph is bounded before unsupported probe topology rejection`() {
        val groups =
            (0 until 28).joinToString(",") { index ->
                val next = if (index == 27) "core" else "probe${index + 1}"
                """{"type":"urltest","tag":"probe$index","outbounds":["$next","$next"]}"""
            }
        val payload = """{"outbounds":[
          {"type":"trojan","tag":"core","server":"core.example","server_port":443,"password":"fixture"},
          {"type":"selector","tag":"select","outbounds":["probe0"]},$groups
        ]}"""
        assertError(payload)
    }

    private fun assertError(payload: String) {
        val result = SelectorUrltestGroupImport.import(payload, "fleet")
        assertTrue("$result", result is SelectorUrltestImportResult.Error)
    }

    private fun bundle(): String =
        """
        {"outbounds":[
          {"type":"vless","tag":"core","server":"core.example","server_port":443,
           "uuid":"00000000-0000-0000-0000-000000000000"},
          {"type":"snell","tag":"snell","server":"research.example","server_port":443,"psk":"fixture"},
          {"type":"selector","tag":"select","outbounds":["core","auto","research"],"default":"auto"},
          {"type":"urltest","tag":"auto","outbounds":["core"],"url":"https://probe.example/204","interval":"5m"},
          {"type":"selector","tag":"research","outbounds":["snell"],"default":"snell"}
        ],"route":{"final":"select"}}
        """.trimIndent()
}
