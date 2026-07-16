package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.TransportPolicyEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolverMappingPolicyTest {
    private val policy = ResolverMappingPolicy()

    @Test
    fun `poisoned selects encrypted mapping even when system is faster`() {
        val mapping =
            policy.select(
                request(
                    classification = DirectDnsClassification.POISONED,
                    systemCandidates = listOf(candidate("198.18.0.10", DnsMode.SYSTEM, latencyMs = 1)),
                    encryptedCandidates = listOf(candidate("203.0.113.10", DnsMode.DOH_PRIMARY, latencyMs = 50)),
                ),
            )

        assertEquals(ResolvedMapping("203.0.113.10", ResolvedIpFamily.IPV4, DnsMode.DOH_PRIMARY), mapping)
    }

    @Test
    fun `clean selects fastest valid candidate and can keep system`() {
        val mapping =
            policy.select(
                request(
                    classification = DirectDnsClassification.CLEAN,
                    systemCandidates = listOf(candidate("198.18.0.10", DnsMode.SYSTEM, latencyMs = 5)),
                    encryptedCandidates = listOf(candidate("203.0.113.10", DnsMode.DOH_PRIMARY, latencyMs = 20)),
                ),
            )

        assertEquals(ResolvedMapping("198.18.0.10", ResolvedIpFamily.IPV4, DnsMode.SYSTEM), mapping)
    }

    @Test
    fun `divergent selects encrypted with matching ip block correlation`() {
        val mapping =
            policy.select(
                request(
                    classification = DirectDnsClassification.DIVERGENT,
                    systemCandidates =
                        listOf(
                            candidate(
                                ip = "198.18.0.10",
                                source = DnsMode.SYSTEM,
                                latencyMs = 5,
                                ipSetDigest = "198.18.0.10",
                            ),
                        ),
                    encryptedCandidates = listOf(candidate("203.0.113.10", DnsMode.DOH_PRIMARY, latencyMs = 20)),
                    transportEnvelope =
                        TransportPolicyEnvelope(
                            ipSetDigest = "198.18.0.10",
                            transportClass = DirectTransportClass.IP_BLOCK_SUSPECT,
                            reasonCode = DirectModeReasonCode.IP_BLOCKED,
                        ),
                ),
            )

        assertEquals(ResolvedMapping("203.0.113.10", ResolvedIpFamily.IPV4, DnsMode.DOH_PRIMARY), mapping)
    }

    @Test
    fun `divergent keeps fastest candidate when correlation is absent`() {
        val mapping =
            policy.select(
                request(
                    classification = DirectDnsClassification.DIVERGENT,
                    systemCandidates =
                        listOf(
                            candidate(
                                ip = "198.18.0.10",
                                source = DnsMode.SYSTEM,
                                latencyMs = 5,
                                ipSetDigest = "198.18.0.10",
                            ),
                        ),
                    encryptedCandidates = listOf(candidate("203.0.113.10", DnsMode.DOH_PRIMARY, latencyMs = 20)),
                    transportEnvelope = TransportPolicyEnvelope(ipSetDigest = "203.0.113.10"),
                ),
            )

        assertEquals(ResolvedMapping("198.18.0.10", ResolvedIpFamily.IPV4, DnsMode.SYSTEM), mapping)
    }

    @Test
    fun `invalid or empty candidates return null`() {
        val mapping =
            policy.select(
                request(
                    classification = DirectDnsClassification.POISONED,
                    systemCandidates = listOf(candidate("not-an-ip", DnsMode.SYSTEM, latencyMs = 1)),
                    encryptedCandidates = emptyList(),
                ),
            )

        assertNull(mapping)
    }

    @Test
    fun `hostname candidate is rejected in favor of numeric encrypted mapping`() {
        val mapping =
            policy.select(
                request(
                    classification = DirectDnsClassification.CLEAN,
                    systemCandidates = listOf(candidate("resolver.example", DnsMode.SYSTEM, latencyMs = 1)),
                    encryptedCandidates = listOf(candidate("203.0.113.10", DnsMode.DOH_PRIMARY, latencyMs = 20)),
                ),
            )

        assertEquals(ResolvedMapping("203.0.113.10", ResolvedIpFamily.IPV4, DnsMode.DOH_PRIMARY), mapping)
    }

    @Test
    fun `compressed ipv6 candidate remains valid without system resolution`() {
        val mapping =
            policy.select(
                request(
                    classification = DirectDnsClassification.CLEAN,
                    systemCandidates = listOf(candidate("2001:db8::10", DnsMode.SYSTEM, latencyMs = 1)),
                    encryptedCandidates = emptyList(),
                ),
            )

        assertEquals(ResolvedMapping("2001:db8::10", ResolvedIpFamily.IPV6, DnsMode.SYSTEM), mapping)
    }

    @Test
    fun `malformed numeric candidate is rejected`() {
        val mapping =
            policy.select(
                request(
                    classification = DirectDnsClassification.CLEAN,
                    systemCandidates = listOf(candidate("2001:db8:::10", DnsMode.SYSTEM, latencyMs = 1)),
                    encryptedCandidates = emptyList(),
                ),
            )

        assertNull(mapping)
    }

    private fun request(
        classification: DirectDnsClassification?,
        systemCandidates: List<ResolverMappingCandidate>,
        encryptedCandidates: List<ResolverMappingCandidate>,
        transportEnvelope: TransportPolicyEnvelope? = null,
    ): ResolverMappingRequest =
        ResolverMappingRequest(
            host = "example.org",
            networkScopeKey = "wifi:home",
            dnsClassification = classification,
            systemCandidates = systemCandidates,
            encryptedCandidates = encryptedCandidates,
            transportEnvelope = transportEnvelope,
        )

    private fun candidate(
        ip: String,
        source: DnsMode,
        latencyMs: Long,
        ipSetDigest: String = "",
    ): ResolverMappingCandidate =
        ResolverMappingCandidate(
            ip = ip,
            source = source,
            latencyMs = latencyMs,
            ttlMs = 60_000L,
            ipSetDigest = ipSetDigest,
        )
}
