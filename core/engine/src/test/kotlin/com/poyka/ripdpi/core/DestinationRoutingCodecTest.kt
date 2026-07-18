package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationIpMatcher
import com.poyka.ripdpi.core.routing.DestinationIpMatcherKind
import com.poyka.ripdpi.core.routing.DestinationPortRange
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.routing.DestinationRoutingRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationRoutingCodecTest {
    @Test
    fun oldSchemaV2PayloadWithoutDestinationRoutingDecodesToInertPolicy() {
        val decoded = decodeRipDpiProxyUiPreferences("""{"kind":"ui","adaptiveFallback":{},"schemaVersion":2}""")

        assertNotNull(decoded)
        assertEquals(emptyList<DestinationRoutingRule>(), decoded?.destinationRouting?.rules)
        assertEquals(DestinationRoutingAction.TUNNELED, decoded?.destinationRouting?.defaultAction)
        assertEquals("", decoded?.destinationRouting?.canonicalDigest)
    }

    @Test
    fun policyRoundTripsWithExplicitNativeEnumCasing() {
        val policy = destinationPolicy()
        val encoded = RipDpiProxyUIPreferences(destinationRouting = policy).toNativeConfigJson()
        val section =
            Json
                .parseToJsonElement(encoded)
                .jsonObject
                .getValue("destinationRouting")
                .jsonObject
        val rule = section.getValue("rules").toString()

        assertEquals("tunneled", section.getValue("defaultAction").jsonPrimitive.content)
        assertTrue(rule.contains("\"action\":\"direct\""))
        assertTrue(rule.contains("\"network\":\"both\""))
        assertTrue(rule.contains("\"kind\":\"geo_ip\""))
        assertEquals(policy, decodeRipDpiProxyUiPreferences(encoded)?.destinationRouting)
    }

    @Test
    fun rewriteAndStripPreserveDestinationRoutingBytes() {
        val encoded = RipDpiProxyUIPreferences(destinationRouting = destinationPolicy()).toNativeConfigJson()
        val original = destinationRoutingSection(encoded)

        val stripped = stripRipDpiRuntimeContext(encoded)
        val rewritten = RipDpiProxyJsonPreferences(encoded).toNativeConfigJson()

        assertEquals(original, destinationRoutingSection(stripped))
        assertEquals(original, destinationRoutingSection(rewritten))
    }

    @Test
    fun malformedOrUnknownDestinationRoutingRejectsAtomically() {
        val unknownAction =
            """
            {
              "kind":"ui",
              "listen":{},
              "destinationRouting":{"rules":[],"defaultAction":"future","canonicalDigest":""},
              "schemaVersion":2
            }
            """.trimIndent()
        val unknownRuleField =
            """
            {
              "kind":"ui",
              "listen":{},
              "destinationRouting":{
                "rules":[{
                  "action":"block",
                  "network":"tcp",
                  "domains":[{"kind":"exact","value":"example.com"}],
                  "unexpected":true
                }],
                "defaultAction":"tunneled",
                "canonicalDigest":"digest"
              },
              "schemaVersion":2
            }
            """.trimIndent()
        val malformedRule =
            """
            {
              "kind":"ui",
              "listen":{},
              "destinationRouting":{
                "rules":[{
                  "action":"direct",
                  "network":"both",
                  "destinationPorts":[{"start":8443,"endInclusive":443}]
                }],
                "defaultAction":"tunneled",
                "canonicalDigest":"digest"
              },
              "schemaVersion":2
            }
            """.trimIndent()

        assertNull(decodeRipDpiProxyUiPreferences(unknownAction))
        assertNull(decodeRipDpiProxyUiPreferences(unknownRuleField))
        assertNull(decodeRipDpiProxyUiPreferences(malformedRule))
    }

    private fun destinationRoutingSection(json: String): String =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("destinationRouting")
            .toString()

    private fun destinationPolicy(): DestinationRoutingPolicy =
        DestinationRoutingPolicy(
            rules =
                listOf(
                    DestinationRoutingRule(
                        action = DestinationRoutingAction.DIRECT,
                        network = DestinationRoutingNetwork.BOTH,
                        domains =
                            listOf(
                                DestinationDomainMatcher(DestinationDomainMatcherKind.EXACT, "example.com"),
                                DestinationDomainMatcher(DestinationDomainMatcherKind.SUFFIX, "example.net"),
                                DestinationDomainMatcher(DestinationDomainMatcherKind.GEOSITE, "ru"),
                            ),
                        ipRanges =
                            listOf(
                                DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "192.0.2.0/24"),
                                DestinationIpMatcher(DestinationIpMatcherKind.GEO_IP, "ru"),
                            ),
                        destinationPorts = listOf(DestinationPortRange(443, 8443)),
                    ),
                ),
            canonicalDigest = "digest-v1",
        )
}
