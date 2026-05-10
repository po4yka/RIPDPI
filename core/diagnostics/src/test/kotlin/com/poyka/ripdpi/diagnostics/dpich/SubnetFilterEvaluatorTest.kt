package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SubnetFilterEvaluatorTest {
    @Test
    fun country_returns_all_subnets_in_country() =
        runTest {
            val evaluator = evaluator()

            assertEquals(
                setOf(range("1.1.1.0/24"), range("1.1.2.0/24"), range("1.1.3.0/24")),
                evaluator.evaluate(SubnetFilterAst.Country(listOf("de"))),
            )
        }

    @Test
    fun org_substring_match() =
        runTest {
            val evaluator = evaluator()

            assertEquals(
                setOf(
                    range("1.1.1.0/24"),
                    range("1.1.2.0/24"),
                    range("5.5.5.0/24"),
                    range("7.7.7.0/24"),
                    range("8.8.8.0/24"),
                    range("9.9.9.0/24"),
                    range("10.10.10.0/24"),
                    range("11.11.11.0/24"),
                ),
                evaluator.evaluate(SubnetFilterAst.Org(listOf("hetzner"))),
            )
        }

    @Test
    fun as_two_phase_via_ip() =
        runTest {
            val evaluator = evaluator()

            assertEquals(
                setOf(
                    range("5.5.5.0/24"),
                    range("7.7.7.0/24"),
                    range("8.8.8.0/24"),
                    range("9.9.9.0/24"),
                    range("10.10.10.0/24"),
                ),
                evaluator.evaluate(SubnetFilterAst.As(listOf("1.2.3.4"))),
            )
        }

    @Test
    fun subnet_minimal_from_ip() =
        runTest {
            val evaluator = evaluator()

            assertEquals(
                setOf(range("1.2.3.0/24")),
                evaluator.evaluate(SubnetFilterAst.Subnet(listOf("1.2.3.4"))),
            )
        }

    @Test
    fun host_resolves_via_dns() =
        runTest {
            val evaluator =
                evaluator(
                    dns =
                        object : SubnetFilterDnsResolver {
                            override suspend fun resolve(hostname: String): Set<String> =
                                when (hostname) {
                                    "blocked.example" -> setOf("1.2.3.4", "5.6.7.8")
                                    else -> emptySet()
                                }
                        },
                )

            assertEquals(
                setOf(range("1.2.3.0/24"), range("5.6.7.0/24")),
                evaluator.evaluate(SubnetFilterAst.Host(listOf("blocked.example"))),
            )
        }

    @Test
    fun and_intersection() =
        runTest {
            val evaluator = evaluator()

            assertEquals(
                setOf(range("1.1.1.0/24"), range("1.1.2.0/24")),
                evaluator.evaluate(
                    SubnetFilterAst.And(
                        left = SubnetFilterAst.Org(listOf("hetzner")),
                        right = SubnetFilterAst.Country(listOf("de")),
                    ),
                ),
            )
        }

    @Test
    fun or_union() =
        runTest {
            val evaluator = evaluator()

            assertEquals(
                setOf(
                    range("1.1.1.0/24"),
                    range("1.1.2.0/24"),
                    range("5.5.5.0/24"),
                    range("7.7.7.0/24"),
                    range("8.8.8.0/24"),
                    range("9.9.9.0/24"),
                    range("10.10.10.0/24"),
                    range("11.11.11.0/24"),
                    range("12.12.12.0/24"),
                ),
                evaluator.evaluate(
                    SubnetFilterAst.Or(
                        left = SubnetFilterAst.Org(listOf("hetzner")),
                        right = SubnetFilterAst.As(listOf("123")),
                    ),
                ),
            )
        }

    @Test
    fun empty_filter_expression_returns_empty_set() =
        runTest {
            assertEquals(emptySet<IpRange>(), evaluator().evaluate(SubnetFilterDsl.parse("")))
        }

    private fun evaluator(dns: SubnetFilterDnsResolver = EmptyDnsResolver) =
        SubnetFilterEvaluator(
            geoipDb = fakeGeoipDb(),
            dnsResolver = dns,
        )

    private fun fakeGeoipDb(): SubnetMetadataLookup =
        FakeSubnetMetadataLookup(
            records =
                listOf(
                    SubnetMetadata(range("1.1.1.0/24"), asn = 64500, org = "Hetzner Online GmbH", country = "DE"),
                    SubnetMetadata(range("1.1.2.0/24"), asn = 64500, org = "Hetzner Online GmbH", country = "DE"),
                    SubnetMetadata(range("1.1.3.0/24"), asn = 64499, org = "Example Net", country = "DE"),
                    SubnetMetadata(range("2.2.2.0/24"), asn = 64498, org = "Example Net", country = "FI"),
                    SubnetMetadata(range("1.2.3.0/24"), asn = 64501, org = "DigitalOcean", country = "US"),
                    SubnetMetadata(range("5.6.7.0/24"), asn = 64502, org = "Other Host", country = "NL"),
                    SubnetMetadata(range("5.5.5.0/24"), asn = 199524, org = "Hetzner Cloud", country = "FI"),
                    SubnetMetadata(range("7.7.7.0/24"), asn = 199524, org = "Hetzner Cloud", country = "US"),
                    SubnetMetadata(range("8.8.8.0/24"), asn = 199524, org = "Hetzner Cloud", country = "US"),
                    SubnetMetadata(range("9.9.9.0/24"), asn = 199524, org = "Hetzner Cloud", country = "US"),
                    SubnetMetadata(range("10.10.10.0/24"), asn = 199524, org = "Hetzner Cloud", country = "US"),
                    SubnetMetadata(range("11.11.11.0/24"), asn = 53667, org = "Hetzner USA", country = "US"),
                    SubnetMetadata(range("12.12.12.0/24"), asn = 123, org = "Transit AS", country = "GB"),
                ),
            ipToAsn =
                mapOf(
                    "1.2.3.4" to 199524,
                    "5.6.7.8" to 64502,
                ),
            ipToSubnet =
                mapOf(
                    "1.2.3.4" to range("1.2.3.0/24"),
                    "5.6.7.8" to range("5.6.7.0/24"),
                ),
        )

    private fun range(cidr: String) = IpRange(cidr)
}
