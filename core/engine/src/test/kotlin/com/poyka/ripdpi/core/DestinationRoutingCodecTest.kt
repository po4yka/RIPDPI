package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.DestinationRoutingSectionCodec
import com.poyka.ripdpi.core.codec.DestinationRoutingWireContract
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
        assertEquals("5b7e40d21eaec2e217c0b2b095fa69e926d2c3631fccc4482ca4a6899c9cfb5a", policy.canonicalDigest)
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
                "canonicalDigest":"${"0".repeat(64)}"
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
                "canonicalDigest":"${"0".repeat(64)}"
              },
              "schemaVersion":2
            }
            """.trimIndent()

        assertNull(decodeRipDpiProxyUiPreferences(unknownAction))
        assertNull(decodeRipDpiProxyUiPreferences(unknownRuleField))
        assertNull(decodeRipDpiProxyUiPreferences(malformedRule))
    }

    @Test
    fun directAndBlockDefaultsRejectEvenWithoutRules() {
        listOf("direct", "block").forEach { action ->
            val payload =
                """
                {
                  "kind":"ui",
                  "listen":{},
                  "destinationRouting":{"rules":[],"defaultAction":"$action","canonicalDigest":""},
                  "schemaVersion":2
                }
                """.trimIndent()

            assertNull(decodeRipDpiProxyUiPreferences(payload))
        }
    }

    @Test
    fun nonCanonicalMatchersAndForgedDigestRejectAtomically() {
        val valid = RipDpiProxyUIPreferences(destinationRouting = destinationPolicy()).toNativeConfigJson()
        val policy =
            Json
                .parseToJsonElement(valid)
                .jsonObject
                .getValue("destinationRouting")
                .jsonObject
        val malformedValues =
            listOf(
                "example.com" to "Example.com",
                "192.0.2.0/24" to "192.0.2.1/24",
                policy.getValue("canonicalDigest").jsonPrimitive.content to
                    policy
                        .getValue("canonicalDigest")
                        .jsonPrimitive.content
                        .uppercase(),
                policy.getValue("canonicalDigest").jsonPrimitive.content to "0".repeat(64),
            )

        malformedValues.forEach { (canonical, malformed) ->
            assertNull(decodeRipDpiProxyUiPreferences(valid.replace(canonical, malformed)))
        }
    }

    @Test
    fun structuralBoundsRejectAtomically() {
        val tooManyRules =
            (0..256).joinToString(",") {
                """{"action":"direct","network":"tcp","domains":[{"kind":"exact","value":"r$it.example"}]}"""
            }
        val payload =
            """
            {
              "kind":"ui",
              "listen":{},
              "destinationRouting":{
                "rules":[$tooManyRules],
                "defaultAction":"tunneled",
                "canonicalDigest":"${"0".repeat(64)}"
              },
              "schemaVersion":2
            }
            """.trimIndent()

        assertNull(decodeRipDpiProxyUiPreferences(payload))

        val tooManyDomains =
            (0..256).joinToString(",") { """{"kind":"exact","value":"d$it.example"}""" }
        val tooManyEntriesPayload =
            """
            {
              "kind":"ui",
              "listen":{},
              "destinationRouting":{
                "rules":[{"action":"direct","network":"tcp","domains":[$tooManyDomains]}],
                "defaultAction":"tunneled",
                "canonicalDigest":"${"0".repeat(64)}"
              },
              "schemaVersion":2
            }
            """.trimIndent()
        val oversizedTokenPayload =
            """
            {
              "kind":"ui",
              "listen":{},
              "destinationRouting":{
                "rules":[{
                  "action":"direct",
                  "network":"tcp",
                  "domains":[{"kind":"geosite","value":"${"a".repeat(254)}"}]
                }],
                "defaultAction":"tunneled",
                "canonicalDigest":"${"0".repeat(64)}"
              },
              "schemaVersion":2
            }
            """.trimIndent()

        assertNull(decodeRipDpiProxyUiPreferences(tooManyEntriesPayload))
        assertNull(decodeRipDpiProxyUiPreferences(oversizedTokenPayload))
    }

    private fun destinationRoutingSection(json: String): String =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("destinationRouting")
            .toString()

    private fun destinationPolicy(): DestinationRoutingPolicy =
        destinationRules().let { rules ->
            val nativeRules =
                DestinationRoutingSectionCodec
                    .toNative(DestinationRoutingPolicy(rules = rules, canonicalDigest = ""))
                    .rules
            DestinationRoutingPolicy(
                rules = rules,
                canonicalDigest = DestinationRoutingWireContract.computeCanonicalDigest(nativeRules),
            )
        }

    private fun destinationRules(): List<DestinationRoutingRule> =
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
        )
}
