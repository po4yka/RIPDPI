package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: [DnsLeakTestDomainGenerator] produces unique per-run domains tagged by scenario.
 */
class DnsLeakTestDomainGeneratorTest {
    private val generator = DnsLeakTestDomainGenerator()

    @Test
    fun generatedDomainEndsWithTestZoneSuffix() {
        val domain = generator.generate(DnsLeakScenario.Proxy, runSalt = "abc")
        assertTrue(
            "Domain must end with the fixed test zone suffix",
            domain.value.endsWith(".leak-test.invalid"),
        )
    }

    @Test
    fun generatedDomainContainsScenarioTag() {
        val domain = generator.generate(DnsLeakScenario.Proxy, runSalt = "abc")
        assertTrue(
            "Domain must contain the scenario tag",
            domain.value.contains("proxy"),
        )
    }

    @Test
    fun generatedDomainForDirectScenarioContainsDirectTag() {
        val domain = generator.generate(DnsLeakScenario.Direct, runSalt = "abc")
        assertTrue(domain.value.contains("direct"))
    }

    @Test
    fun generatedDomainForIpv6ScenarioContainsIpv6Tag() {
        val domain = generator.generate(DnsLeakScenario.Ipv6, runSalt = "abc")
        assertTrue(domain.value.contains("ipv6"))
    }

    @Test
    fun generatedDomainForCaptiveScenarioContainsCaptiveTag() {
        val domain = generator.generate(DnsLeakScenario.Captive, runSalt = "abc")
        assertTrue(domain.value.contains("captive"))
    }

    @Test
    fun domainScenarioTagMatchesGeneratedScenario() {
        val domain = generator.generate(DnsLeakScenario.Ipv6, runSalt = "xyz")
        assertEquals(DnsLeakScenario.Ipv6, domain.scenario)
    }

    @Test
    fun differentSaltsProduceDifferentSubdomains() {
        val d1 = generator.generate(DnsLeakScenario.Proxy, runSalt = "salt-1")
        val d2 = generator.generate(DnsLeakScenario.Proxy, runSalt = "salt-2")
        assertNotEquals(
            "Different run salts must produce different domain values",
            d1.value,
            d2.value,
        )
    }

    @Test
    fun sameSaltAndScenarioProducesSameDomain() {
        val d1 = generator.generate(DnsLeakScenario.Direct, runSalt = "deterministic")
        val d2 = generator.generate(DnsLeakScenario.Direct, runSalt = "deterministic")
        assertEquals(d1.value, d2.value)
    }

    @Test
    fun allScenariosCanBeGenerated() {
        DnsLeakScenario.entries.forEach { scenario ->
            val domain = generator.generate(scenario, runSalt = "check")
            assertTrue(domain.value.isNotBlank())
        }
    }
}
