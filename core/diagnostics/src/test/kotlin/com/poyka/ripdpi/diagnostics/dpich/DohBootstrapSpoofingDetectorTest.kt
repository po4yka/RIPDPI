package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.UnknownHostException

class DohBootstrapSpoofingDetectorTest {
    @Test
    fun legitimateGoogleDohIpReturnsOk() =
        runTest {
            val detector =
                detector(
                    resolver = mapResolver("dns.google" to listOf("8.8.8.8")),
                    metadata = metadata(),
                    providers = listOf(DohProviderFilter("Google", "dns.google", """as(15169) || org("google")""")),
                )

            val result = detector.checkAll().getValue("Google")

            assertEquals(BootstrapVerdict.OK, result.verdict)
            assertEquals("8.8.8.8", result.bootstrapIp)
            assertEquals(15169, result.discoveredAsn)
            assertEquals("Google LLC", result.discoveredOrg)
        }

    @Test
    fun spoofedBootstrapIpReturnsSpoofedWithDiscoveredAsn() =
        runTest {
            val detector =
                detector(
                    resolver = mapResolver("dns.google" to listOf("1.2.3.4")),
                    metadata = metadata(),
                    providers = listOf(DohProviderFilter("Google", "dns.google", """as(15169) || org("google")""")),
                )

            val result = detector.checkAll().getValue("Google")

            assertEquals(BootstrapVerdict.SPOOFED, result.verdict)
            assertEquals("1.2.3.4", result.bootstrapIp)
            assertEquals(12389, result.discoveredAsn)
            assertEquals("Rostelecom", result.discoveredOrg)
        }

    @Test
    fun orgSubstringFilterMatches() =
        runTest {
            val detector =
                detector(
                    resolver = mapResolver("cloudflare-dns.com" to listOf("1.1.1.1")),
                    metadata = metadata(),
                    providers =
                        listOf(
                            DohProviderFilter("Cloudflare", "cloudflare-dns.com", """org("cloudflare")"""),
                        ),
                )

            val result = detector.checkAll().getValue("Cloudflare")

            assertEquals(BootstrapVerdict.OK, result.verdict)
        }

    @Test
    fun resolveFailureReturnsResolveFailedNotSpoofed() =
        runTest {
            val detector =
                detector(
                    resolver =
                        object : DohBootstrapResolver {
                            override suspend fun resolve(hostname: String): List<String> =
                                throw UnknownHostException(hostname)
                        },
                    metadata = metadata(),
                    providers = listOf(DohProviderFilter("Google", "dns.google", """as(15169)""")),
                )

            val result = detector.checkAll().getValue("Google")

            assertEquals(BootstrapVerdict.RESOLVE_FAILED, result.verdict)
            assertEquals(null, result.bootstrapIp)
        }

    @Test
    fun parallelChecksAllProviders() =
        runTest {
            val resolved = mutableListOf<String>()
            val detector =
                detector(
                    resolver =
                        object : DohBootstrapResolver {
                            override suspend fun resolve(hostname: String): List<String> {
                                resolved += hostname
                                return listOf("8.8.8.8")
                            }
                        },
                    metadata = metadata(),
                    providers =
                        listOf(
                            DohProviderFilter("Google", "dns.google", """as(15169)"""),
                            DohProviderFilter("Google Alt", "8.8.8.8", """as(15169)"""),
                        ),
                )

            val results = detector.checkAll()

            assertEquals(setOf("Google", "Google Alt"), results.keys)
            assertEquals(listOf("dns.google", "8.8.8.8"), resolved)
        }

    private fun detector(
        resolver: DohBootstrapResolver,
        metadata: SubnetMetadataLookup,
        providers: List<DohProviderFilter>,
    ): DohBootstrapSpoofingDetector =
        DohBootstrapSpoofingDetector(
            resolver = resolver,
            metadata = metadata,
            providers = providers,
        )

    private fun mapResolver(vararg mappings: Pair<String, List<String>>): DohBootstrapResolver {
        val byHost = mappings.toMap()
        return DohBootstrapResolver { hostname -> byHost[hostname].orEmpty() }
    }

    private fun metadata(): SubnetMetadataLookup =
        FakeSubnetMetadataLookup(
            records =
                listOf(
                    SubnetMetadata(IpRange("8.8.8.0/24"), asn = 15169, org = "Google LLC", country = "US"),
                    SubnetMetadata(IpRange("1.1.1.0/24"), asn = 13335, org = "Cloudflare, Inc.", country = "US"),
                    SubnetMetadata(IpRange("1.2.3.0/24"), asn = 12389, org = "Rostelecom", country = "RU"),
                ),
            ipToAsn =
                mapOf(
                    "8.8.8.8" to 15169,
                    "1.1.1.1" to 13335,
                    "1.2.3.4" to 12389,
                ),
            ipToSubnet =
                mapOf(
                    "8.8.8.8" to IpRange("8.8.8.0/24"),
                    "1.1.1.1" to IpRange("1.1.1.0/24"),
                    "1.2.3.4" to IpRange("1.2.3.0/24"),
                ),
        )
}
