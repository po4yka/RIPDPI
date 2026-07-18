package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.DestinationRoutingSectionCodec
import com.poyka.ripdpi.core.codec.DestinationRoutingWireContract
import com.poyka.ripdpi.core.codec.NativeDestinationDomainMatcher
import com.poyka.ripdpi.core.codec.NativeDestinationDomainMatcherKind
import com.poyka.ripdpi.core.codec.NativeDestinationIpMatcher
import com.poyka.ripdpi.core.codec.NativeDestinationIpMatcherKind
import com.poyka.ripdpi.core.codec.NativeDestinationPortRange
import com.poyka.ripdpi.core.codec.NativeDestinationRoutingAction
import com.poyka.ripdpi.core.codec.NativeDestinationRoutingConfig
import com.poyka.ripdpi.core.codec.NativeDestinationRoutingNetwork
import com.poyka.ripdpi.core.codec.NativeDestinationRoutingRule
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
import org.junit.Assert.assertThrows
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
        assertEquals(CrossLanguageMixedDigest, policy.canonicalDigest)
        assertEquals(policy, decodeRipDpiProxyUiPreferences(encoded)?.destinationRouting)
    }

    @Test
    fun sharedMixedFixtureUsesCanonicalKindRanks() {
        val payload = requireNotNull(javaClass.getResource("/fixtures/destination-routing-mixed.json")).readText()
        val decoded = requireNotNull(decodeRipDpiProxyUiPreferences(payload))

        assertEquals(destinationPolicy(), decoded.destinationRouting)
        assertEquals(CrossLanguageMixedDigest, decoded.destinationRouting.canonicalDigest)
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
    fun emptyPolicyRejectsNonemptyDigest() {
        val payload =
            """
            {
              "kind":"ui",
              "listen":{},
              "destinationRouting":{"rules":[],"defaultAction":"tunneled","canonicalDigest":"${"0".repeat(64)}"},
              "schemaVersion":2
            }
            """.trimIndent()

        assertNull(decodeRipDpiProxyUiPreferences(payload))
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

    @Test
    fun aggregateAndCanonicalBoundsAcceptExactLimitsAndRejectOverflow() {
        val exact = exactAggregateBoundaryPolicy()

        DestinationRoutingWireContract.validate(exact)

        val aggregateOverflow =
            exact.copy(
                rules =
                    exact.rules.toMutableList().also { rules ->
                        rules[0] =
                            rules[0].copy(
                                domains = rules[0].domains + geoMatcher("aggregate-overflow".padEnd(63, 'a')),
                            )
                    },
                canonicalDigest = "0".repeat(64),
            )
        val canonicalOverflow =
            exact.copy(
                rules =
                    exact.rules.toMutableList().also { rules ->
                        val first = rules[0].domains.first()
                        rules[0] =
                            rules[0].copy(
                                domains =
                                    listOf(first.copy(value = first.value + "a")) + rules[0].domains.drop(1),
                            )
                    },
                canonicalDigest = "0".repeat(64),
            )

        assertThrows(IllegalArgumentException::class.java) {
            DestinationRoutingWireContract.validate(aggregateOverflow)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DestinationRoutingWireContract.validate(canonicalOverflow)
        }
    }

    @Test
    fun matcherFieldsAcceptExactLimitsAndRejectIpAndPortOverflow() {
        val exactRule =
            NativeDestinationRoutingRule(
                action = NativeDestinationRoutingAction.DIRECT,
                network = NativeDestinationRoutingNetwork.BOTH,
                domains = (0 until 256).map { geoMatcher("d$it") },
                ipRanges = (0 until 256).map { cidrMatcher("10.0.$it.0/24") },
                destinationPorts = (1..256).map { NativeDestinationPortRange(it, it) },
            )
        DestinationRoutingWireContract.validate(nativePolicy(listOf(exactRule)))

        val ipOverflow =
            exactRule.copy(
                domains = emptyList(),
                ipRanges = exactRule.ipRanges + cidrMatcher("10.1.0.0/24"),
                destinationPorts = emptyList(),
            )
        val portOverflow =
            exactRule.copy(
                domains = emptyList(),
                ipRanges = emptyList(),
                destinationPorts = exactRule.destinationPorts + NativeDestinationPortRange(257, 257),
            )

        assertThrows(IllegalArgumentException::class.java) {
            DestinationRoutingWireContract.validate(
                NativeDestinationRoutingConfig(rules = listOf(ipOverflow), canonicalDigest = "0".repeat(64)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DestinationRoutingWireContract.validate(
                NativeDestinationRoutingConfig(rules = listOf(portOverflow), canonicalDigest = "0".repeat(64)),
            )
        }
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

    private fun exactAggregateBoundaryPolicy(): NativeDestinationRoutingConfig {
        val rules =
            (0 until 256).map { ruleIndex ->
                NativeDestinationRoutingRule(
                    action = NativeDestinationRoutingAction.DIRECT,
                    network = NativeDestinationRoutingNetwork.BOTH,
                    domains =
                        (0 until 4).map { matcherIndex ->
                            geoMatcher("r${ruleIndex}m$matcherIndex".padEnd(63, 'a'))
                        },
                )
            }
        return nativePolicy(rules)
    }

    private fun nativePolicy(rules: List<NativeDestinationRoutingRule>): NativeDestinationRoutingConfig =
        NativeDestinationRoutingConfig(
            rules = rules,
            canonicalDigest = DestinationRoutingWireContract.computeCanonicalDigest(rules),
        )

    private fun geoMatcher(value: String) =
        NativeDestinationDomainMatcher(NativeDestinationDomainMatcherKind.GEOSITE, value)

    private fun cidrMatcher(value: String) = NativeDestinationIpMatcher(NativeDestinationIpMatcherKind.CIDR, value)

    private companion object {
        const val CrossLanguageMixedDigest = "e7ed9f9ec8688b89eea6f22a7ae6e93e7f441a903b9f9de96d387700c122bee7"
    }
}
