package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.subscription.SingBoxRouteRulesParseResult
import com.poyka.ripdpi.data.subscription.SingBoxRouteRulesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SingBoxRouteRulesParser]: reads the `route.rules` array of a
 * sing-box bundle and yields per-app [com.poyka.ripdpi.data.routing.PackageRoutingRule]
 * records. Only entries carrying `package_name` are kept; everything else
 * (domain, geoip, port, …) is ignored without error. A package appearing in
 * both a bypass rule and a via-tun rule makes the bundle malformed.
 */
class SingBoxRouteRulesParserTest {
    private fun success(result: SingBoxRouteRulesParseResult): SingBoxRouteRulesParseResult.Success {
        assertTrue("expected success, got $result", result is SingBoxRouteRulesParseResult.Success)
        return result as SingBoxRouteRulesParseResult.Success
    }

    @Test
    fun `parses bypass and via-tun package rules`() {
        val json =
            """
            {
              "route": {
                "rules": [
                  { "package_name": ["com.bank.app", "com.maps.app"], "outbound": "direct" },
                  { "package_name": ["com.stream.app"], "outbound": "select" }
                ]
              }
            }
            """.trimIndent()

        val parsed = success(SingBoxRouteRulesParser.parse(json))

        assertEquals(3, parsed.rules.size)
        assertEquals(
            PackageRoutingAction.BYPASS,
            parsed.rules.single { it.packageName == "com.bank.app" }.action,
        )
        assertEquals(
            PackageRoutingAction.BYPASS,
            parsed.rules.single { it.packageName == "com.maps.app" }.action,
        )
        assertEquals(
            PackageRoutingAction.VIA_TUN,
            parsed.rules.single { it.packageName == "com.stream.app" }.action,
        )
    }

    @Test
    fun `maps a named non-direct non-select outbound to via-outbound`() {
        val json =
            """
            {
              "route": {
                "rules": [
                  { "package_name": ["com.work.app"], "outbound": "corp-proxy" }
                ]
              }
            }
            """.trimIndent()

        val parsed = success(SingBoxRouteRulesParser.parse(json))

        assertEquals(1, parsed.rules.size)
        assertEquals(PackageRoutingAction.VIA_OUTBOUND, parsed.rules.single().action)
    }

    @Test
    fun `ignores non package_name rules without error`() {
        val json =
            """
            {
              "route": {
                "rules": [
                  { "domain": ["example.com"], "outbound": "direct" },
                  { "geoip": ["cn"], "outbound": "direct" },
                  { "port": [443], "outbound": "select" },
                  { "package_name": ["com.only.app"], "outbound": "direct" }
                ]
              }
            }
            """.trimIndent()

        val parsed = success(SingBoxRouteRulesParser.parse(json))

        assertEquals(1, parsed.rules.size)
        assertEquals("com.only.app", parsed.rules.single().packageName)
    }

    @Test
    fun `a bundle with no route section yields an empty rule list`() {
        val json =
            """
            { "outbounds": [ { "type": "trojan", "tag": "t", "server": "t.example.com",
              "server_port": 443, "password": "p" } ] }
            """.trimIndent()

        val parsed = success(SingBoxRouteRulesParser.parse(json))

        assertTrue(parsed.rules.isEmpty())
    }

    @Test
    fun `same package in both bypass and via-tun is rejected as malformed with the rule index`() {
        val json =
            """
            {
              "route": {
                "rules": [
                  { "package_name": ["com.dup.app"], "outbound": "direct" },
                  { "package_name": ["com.other.app"], "outbound": "select" },
                  { "package_name": ["com.dup.app"], "outbound": "select" }
                ]
              }
            }
            """.trimIndent()

        val result = SingBoxRouteRulesParser.parse(json)

        assertTrue("expected error, got $result", result is SingBoxRouteRulesParseResult.Error)
        result as SingBoxRouteRulesParseResult.Error
        assertEquals("com.dup.app", result.packageName)
        // The offending rule is the second occurrence at route.rules index 2.
        assertEquals(2, result.ruleIndex)
        assertTrue(result.message.contains("com.dup.app"))
        assertTrue(result.message.contains("2"))
    }

    @Test
    fun `malformed json surfaces a typed error`() {
        val result = SingBoxRouteRulesParser.parse("{ not json ")

        assertTrue(result is SingBoxRouteRulesParseResult.Error)
        result as SingBoxRouteRulesParseResult.Error
        assertTrue(result.message.isNotBlank())
    }
}
