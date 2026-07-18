package com.poyka.ripdpi.services.routing

import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationIpMatcher
import com.poyka.ripdpi.core.routing.DestinationIpMatcherKind
import com.poyka.ripdpi.core.routing.DestinationPortRange
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationRoutingPolicyCompilerTest {
    @Test
    fun `compiles enabled rules in user order and preserves first-match representation`() {
        val result =
            compileSuccess(
                listOf(
                    rule(
                        id = 20,
                        order = 2,
                        domains = "domain_suffix:.Example.COM\nGeosite:Category-RU",
                        action = OutboundTag.Bypass,
                        network = RuleNetwork.UDP,
                    ),
                    rule(
                        id = 10,
                        order = 1,
                        domains = "domain:API.Example.com\nplain.example",
                        ipCidrs = "192.0.2.129/24\n2001:db8::1234/64\ngeoip:RU",
                        ports = "443\n8000-8999",
                        action = OutboundTag.Block,
                        network = RuleNetwork.TCP,
                    ),
                    rule(id = 5, order = 0, enabled = false, domains = "disabled.example"),
                ),
            )

        assertEquals(DestinationRoutingAction.TUNNELED, result.defaultAction)
        assertEquals(2, result.rules.size)
        assertEquals(DestinationRoutingAction.BLOCK, result.rules[0].action)
        assertEquals(DestinationRoutingNetwork.TCP, result.rules[0].network)
        assertEquals(
            listOf(
                DestinationDomainMatcher(DestinationDomainMatcherKind.EXACT, "api.example.com"),
                DestinationDomainMatcher(DestinationDomainMatcherKind.EXACT, "plain.example"),
            ),
            result.rules[0].domains,
        )
        assertEquals(
            listOf(
                DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "192.0.2.0/24"),
                DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "2001:db8:0:0:0:0:0:0/64"),
                DestinationIpMatcher(DestinationIpMatcherKind.GEO_IP, "ru"),
            ),
            result.rules[0].ipRanges,
        )
        assertEquals(
            listOf(DestinationPortRange(443, 443), DestinationPortRange(8000, 8999)),
            result.rules[0].destinationPorts,
        )
        assertEquals(DestinationRoutingAction.DIRECT, result.rules[1].action)
        assertEquals(DestinationRoutingNetwork.UDP, result.rules[1].network)
        assertEquals(
            listOf(
                DestinationDomainMatcher(DestinationDomainMatcherKind.SUFFIX, "example.com"),
                DestinationDomainMatcher(DestinationDomainMatcherKind.GEOSITE, "category-ru"),
            ),
            result.rules[1].domains,
        )
    }

    @Test
    fun `maps proxy bypass block and all network variants`() {
        val result =
            compileSuccess(
                listOf(
                    rule(id = 1, order = 0, domains = "proxy.example", action = OutboundTag.Proxy),
                    rule(
                        id = 2,
                        order = 1,
                        domains = ".direct.example",
                        action = OutboundTag.Bypass,
                        network = RuleNetwork.UDP,
                    ),
                    rule(
                        id = 3,
                        order = 2,
                        ports = "53",
                        action = OutboundTag.Block,
                        network = RuleNetwork.BOTH,
                    ),
                ),
            )

        assertEquals(
            listOf(DestinationRoutingAction.TUNNELED, DestinationRoutingAction.DIRECT, DestinationRoutingAction.BLOCK),
            result.rules.map { it.action },
        )
        assertEquals(
            listOf(DestinationRoutingNetwork.TCP, DestinationRoutingNetwork.UDP, DestinationRoutingNetwork.BOTH),
            result.rules.map { it.network },
        )
    }

    @Test
    fun `rejects unsupported semantics atomically`() {
        val unsupported =
            listOf(
                rule(id = 1, domains = "example.com", action = OutboundTag.Profile(7)),
                rule(id = 2, domains = "example.com", action = OutboundTag.Group(8)),
                rule(id = 3, domains = "example.com", packages = setOf("com.example")),
                rule(id = 4, domains = "example.com", processName = "browser"),
                rule(id = 5, domains = "example.com", sourcePorts = "1024-2048"),
            )

        unsupported.forEach { candidate ->
            val result = DestinationRoutingPolicyCompiler.compile(listOf(candidate))
            assertTrue("rule ${candidate.id} must reject", result is DestinationRoutingPolicyCompileResult.Failure)
        }
    }

    @Test
    fun `rejects duplicate enabled order independent of restored input order`() {
        val restoredRules =
            listOf(
                rule(id = 20, order = 4, domains = "second.example"),
                rule(id = 10, order = 4, domains = "first.example"),
                rule(id = 30, order = 4, enabled = false, domains = "disabled.example"),
            )

        val forward = compileFailure(restoredRules)
        val reversed = compileFailure(restoredRules.reversed())

        assertEquals(DestinationRoutingPolicyErrorCode.DUPLICATE_RULE_ORDER, forward.code)
        assertEquals(DestinationRoutingPolicyField.USER_ORDER, forward.field)
        assertEquals(10L, forward.ruleId)
        assertEquals(forward, reversed)
    }

    @Test
    fun `ignores unsupported fields on disabled rules`() {
        val result =
            compileSuccess(
                listOf(
                    rule(
                        id = 1,
                        enabled = false,
                        action = OutboundTag.Profile(7),
                        packages = setOf("com.example"),
                    ),
                ),
            )

        assertTrue(result.rules.isEmpty())
        assertEquals(DestinationRoutingAction.TUNNELED, result.defaultAction)
    }

    @Test
    fun `rejects malformed domain cidr geoip and port inputs`() {
        val malformed =
            listOf(
                rule(id = 1, domains = "-bad.example"),
                rule(id = 2, domains = "domain_regex:["),
                rule(id = 3, ipCidrs = "192.0.2.1"),
                rule(id = 4, ipCidrs = "192.0.2.1/33"),
                rule(id = 5, ipCidrs = "2001:::1/64"),
                rule(id = 6, ipCidrs = "geoip:R U"),
                rule(id = 7, ports = "0"),
                rule(id = 8, ports = "65536"),
                rule(id = 9, ports = "9000-8000"),
                rule(id = 10),
            )

        malformed.forEach { candidate ->
            val result = DestinationRoutingPolicyCompiler.compile(listOf(candidate))
            assertTrue("rule ${candidate.id} must reject", result is DestinationRoutingPolicyCompileResult.Failure)
        }
    }

    @Test
    fun `surfaces repository-valid regex as unsupported and preserves source line index`() {
        val error =
            compileFailure(
                listOf(
                    rule(
                        id = 7,
                        domains = "\n\n domain:valid.example \n\n domain_regex:^api\\.example$",
                    ),
                ),
            )

        assertEquals(DestinationRoutingPolicyErrorCode.UNSUPPORTED_MATCHER, error.code)
        assertEquals(DestinationRoutingPolicyField.DOMAINS, error.field)
        assertEquals(4, error.entryIndex)
        assertTrue(error.message.contains("line 5"))
    }

    @Test
    fun `rejects rule and matcher bounds without a partial policy`() {
        val tooManyRules =
            List(DestinationRoutingPolicyCompiler.MAX_RULES + 1) { index ->
                rule(id = index.toLong(), order = index, domains = "host$index.example")
            }
        val tooManyEntries =
            rule(
                id = 900,
                domains =
                    List(DestinationRoutingPolicyCompiler.MAX_ENTRIES_PER_FIELD + 1) { index ->
                        "host$index.example"
                    }.joinToString("\n"),
            )
        val tooLongToken =
            rule(
                id = 901,
                domains = "${"a".repeat(DestinationRoutingPolicyCompiler.MAX_TOKEN_LENGTH)}.example",
            )

        assertFailureCode(tooManyRules, DestinationRoutingPolicyErrorCode.TOO_MANY_RULES)
        assertFailureCode(listOf(tooManyEntries), DestinationRoutingPolicyErrorCode.TOO_MANY_FIELD_ENTRIES)
        assertFailureCode(listOf(tooLongToken), DestinationRoutingPolicyErrorCode.MALFORMED_MATCHER)
    }

    @Test
    fun `rejects total matcher and canonical snapshot size bounds`() {
        val tooManyMatchers =
            List(5) { ruleIndex ->
                rule(
                    id = ruleIndex.toLong(),
                    order = ruleIndex,
                    ports =
                        List(DestinationRoutingPolicyCompiler.MAX_ENTRIES_PER_FIELD) { portIndex ->
                            (ruleIndex * DestinationRoutingPolicyCompiler.MAX_ENTRIES_PER_FIELD + portIndex + 1)
                                .toString()
                        }.joinToString("\n"),
                )
            }
        val maximumLengthDomains =
            List(DestinationRoutingPolicyCompiler.MAX_ENTRIES_PER_FIELD) { index ->
                longDomain(index)
            }.joinToString("\n")
        val tooLargeCanonicalSnapshot =
            listOf(
                rule(id = 10, order = 0, domains = maximumLengthDomains),
                rule(id = 11, order = 1, domains = maximumLengthDomains),
            )

        assertFailureCode(tooManyMatchers, DestinationRoutingPolicyErrorCode.TOO_MANY_MATCHERS)
        assertFailureCode(
            tooLargeCanonicalSnapshot,
            DestinationRoutingPolicyErrorCode.CANONICAL_SIZE_EXCEEDED,
        )
    }

    @Test
    fun `canonical digest is stable for equivalent snapshots and changes with rule order`() {
        val first =
            rule(
                id = 1,
                order = 0,
                domains = " Domain:EXAMPLE.COM. \n domain:example.com",
                ports = "443\n443",
            )
        val second = rule(id = 2, order = 1, ipCidrs = "2001:0DB8:0:0::1/64", action = OutboundTag.Bypass)
        val canonical = compileSuccess(listOf(first, second)).canonicalDigest
        val equivalent =
            compileSuccess(
                listOf(
                    second.copy(id = 20),
                    first.copy(id = 10, domains = "domain:example.com", ports = "443"),
                ),
            ).canonicalDigest
        val reordered =
            compileSuccess(
                listOf(
                    first.copy(userOrder = 2),
                    second.copy(userOrder = 1),
                ),
            ).canonicalDigest

        assertEquals(canonical, equivalent)
        assertNotEquals(canonical, reordered)
        assertEquals(64, canonical.length)
    }

    @Test
    fun `canonical digest ignores alternative order inside a rule`() {
        val original =
            rule(
                id = 1,
                domains = "domain:b.example\ndomain:a.example",
                ipCidrs = "geoip:ru\n192.0.2.1/24",
                ports = "8000-9000\n443",
            )
        val reordered =
            original.copy(
                domains = "domain:a.example\ndomain:b.example",
                ipCidrs = "192.0.2.0/24\ngeoip:RU",
                ports = "443\n8000-9000",
            )

        assertEquals(
            compileSuccess(listOf(original)).canonicalDigest,
            compileSuccess(listOf(reordered)).canonicalDigest,
        )
    }

    private fun compileSuccess(rules: List<RuleEntity>) =
        when (val result = DestinationRoutingPolicyCompiler.compile(rules)) {
            is DestinationRoutingPolicyCompileResult.Success -> {
                result.policy
            }

            is DestinationRoutingPolicyCompileResult.Failure -> {
                error(
                    "unexpected compilation failure: ${result.error}",
                )
            }
        }

    private fun compileFailure(rules: List<RuleEntity>): DestinationRoutingPolicyCompileError =
        when (val result = DestinationRoutingPolicyCompiler.compile(rules)) {
            is DestinationRoutingPolicyCompileResult.Success -> error("unexpected success: ${result.policy}")
            is DestinationRoutingPolicyCompileResult.Failure -> result.error
        }

    private fun assertFailureCode(
        rules: List<RuleEntity>,
        expected: DestinationRoutingPolicyErrorCode,
    ) {
        val result = DestinationRoutingPolicyCompiler.compile(rules)
        assertTrue(result is DestinationRoutingPolicyCompileResult.Failure)
        assertEquals(
            expected,
            (result as DestinationRoutingPolicyCompileResult.Failure).error.code,
        )
    }

    private fun longDomain(index: Int): String =
        listOf(
            "a".repeat(63),
            "b".repeat(63),
            "c".repeat(63),
            "d".repeat(49) + index.toString().padStart(3, '0'),
        ).joinToString(".")

    private fun rule(
        id: Long,
        order: Int = 0,
        enabled: Boolean = true,
        domains: String = "",
        ipCidrs: String = "",
        ports: String = "",
        sourcePorts: String = "",
        processName: String = "",
        packages: Set<String> = emptySet(),
        action: OutboundTag = OutboundTag.Proxy,
        network: RuleNetwork = RuleNetwork.TCP,
    ): RuleEntity =
        RuleEntity(
            id = id,
            userOrder = order,
            enabled = enabled,
            domains = domains,
            ipCidrs = ipCidrs,
            ports = ports,
            sourcePorts = sourcePorts,
            processName = processName,
            packages = packages,
            outboundTag = action,
            network = network,
        )
}
