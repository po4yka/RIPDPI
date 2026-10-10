package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class DnsSemanticCheckerTest {
    @Test
    fun matchingNegativeRepliesAreNotBlocking() =
        runTest {
            val response =
                DnsProbeAnswer(response = DnsResponseSemantics(outcome = DnsResponseOutcome.NXDOMAIN, rcode = 3))
            val result = checker(response, response).check(listOf("example.com"))
            assertEquals(DnsIntegrityVerdict.DNS_NEGATIVE, result.domains.single().verdict)
            assertEquals(
                3,
                result.domains
                    .single()
                    .udpResponse
                    ?.rcode,
            )
            assertEquals(0, result.dohBlocked)
        }

    @Test
    fun udpAnswerAndEncryptedNegativeAreDisagreement() =
        runTest {
            val result =
                checker(
                    DnsProbeAnswer(listOf("1.2.3.4")),
                    DnsProbeAnswer(response = DnsResponseSemantics(outcome = DnsResponseOutcome.REFUSED, rcode = 5)),
                ).check(listOf("example.com"))
            assertEquals(DnsIntegrityVerdict.DNS_DISAGREEMENT, result.domains.single().verdict)
        }

    @Test
    fun legacyEmptyListsDoNotInventRcodeOrBlocking() =
        runTest {
            val result =
                DnsIntegrityChecker(
                    DnsUdpProbe { emptyList() },
                    DnsAddressProbe { emptySet() },
                    DnsAddressProbe { emptySet() },
                ).check(listOf("example.com"))
            assertEquals(DnsIntegrityVerdict.ENCRYPTED_UNAVAILABLE, result.domains.single().verdict)
            assertNull(result.domains.single().udpResponse)
            assertNull(result.domains.single().dohJsonResponse)
        }

    @Test
    fun semanticProbeCancellationPropagates() =
        runTest {
            val udp =
                object : SemanticDnsUdpProbe {
                    override suspend fun resolveResponse(domain: String): DnsProbeAnswer =
                        throw CancellationException("stop")
                }
            try {
                DnsIntegrityChecker(
                    udp,
                    DnsAddressProbe { emptySet() },
                    DnsAddressProbe { emptySet() },
                ).check(listOf("example.com"))
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                // Expected.
            }
        }

    private fun checker(
        udp: DnsProbeAnswer,
        encrypted: DnsProbeAnswer,
    ): DnsIntegrityChecker {
        val udpProbe =
            object : SemanticDnsUdpProbe {
                override suspend fun resolveResponse(domain: String): DnsProbeAnswer = udp
            }
        val dohProbe =
            object : SemanticDnsAddressProbe {
                override suspend fun resolveResponse(
                    domain: String,
                    excludedDohHostnames: Set<String>,
                ): DnsProbeAnswer = encrypted
            }
        return DnsIntegrityChecker(udpProbe, dohProbe, dohProbe)
    }
}
