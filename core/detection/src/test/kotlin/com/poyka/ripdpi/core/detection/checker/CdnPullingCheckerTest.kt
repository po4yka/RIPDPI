package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CdnPullingAddressFamily
import com.poyka.ripdpi.core.detection.CdnPullingEndpointDescriptor
import com.poyka.ripdpi.core.detection.CdnPullingEndpointStatus
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CdnPullingCheckerTest {
    @Test
    fun noCallsMadeWhenDisabled() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val endpoint =
                    endpoint(
                        url = server.url("/cdn-cgi/trace").toString(),
                        family = CdnPullingAddressFamily.IPV4,
                    )

                val result =
                    CdnPullingChecker.check(
                        dispatchers = testDispatchers(),
                        enabled = false,
                        endpoints = listOf(endpoint),
                    )

                assertEquals(0, server.requestCount)
                assertEquals(0, result.endpoints.size)
                assertTrue(
                    result
                        .category
                        .findings
                        .single()
                        .description
                        .contains("disabled"),
                )
            }
        }

    @Test
    fun cdnIpParsedFromValidCloudflareTrace() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("ip=1.2.3.4\nuag=Mozilla/5.0\n")
                        .build(),
                )
                server.start()

                val result =
                    CdnPullingChecker.check(
                        dispatchers = testDispatchers(),
                        enabled = true,
                        endpoints =
                            listOf(
                                endpoint(
                                    url = server.url("/cdn-cgi/trace").toString(),
                                    family = CdnPullingAddressFamily.IPV4,
                                ),
                            ),
                    )

                assertEquals("1.2.3.4", result.endpoints.single().reflectedIp)
                assertEquals(CdnPullingEndpointStatus.OK, result.endpoints.single().status)
            }
        }

    @Test
    fun tlsErrorProducesDpiMitmFindingWithHighConfidence() =
        runTest {
            val endpoint = endpoint(family = CdnPullingAddressFamily.IPV4)
            val result =
                CdnPullingChecker.check(
                    dispatchers = testDispatchers(),
                    enabled = true,
                    endpointClient =
                        CdnTraceClient {
                            throw SSLHandshakeException("certificate pinning failure")
                        },
                    endpoints = listOf(endpoint),
                )

            assertTrue(result.category.detected)
            assertTrue(
                result.category.evidence.any {
                    it.source == EvidenceSource.CDN_PULLING &&
                        it.confidence == EvidenceConfidence.HIGH &&
                        it.detected
                },
            )
            assertTrue(result.category.findings.any { it.description.contains("DPI_MITM") })
        }

    @Test
    fun ipv4Ipv6MismatchFlaggedAsBypassEvidence() =
        runTest {
            val endpoints =
                listOf(
                    endpoint(
                        label = "meduza v4",
                        targetHost = "meduza.io",
                        family = CdnPullingAddressFamily.IPV4,
                    ),
                    endpoint(
                        label = "meduza v6",
                        targetHost = "meduza.io",
                        family = CdnPullingAddressFamily.IPV6,
                    ),
                )

            val result =
                CdnPullingChecker.check(
                    dispatchers = testDispatchers(),
                    enabled = true,
                    endpointClient =
                        CdnTraceClient { endpoint ->
                            when (endpoint.addressFamily) {
                                CdnPullingAddressFamily.IPV4 -> "ip=1.2.3.4"
                                CdnPullingAddressFamily.IPV6 -> "ip=2001:db8::10"
                            }
                        },
                    endpoints = endpoints,
                )

            assertTrue(result.category.needsReview)
            assertEquals(listOf("meduza.io"), result.actionableTargets)
            assertTrue(
                result.category.evidence.any {
                    it.source == EvidenceSource.CDN_PULLING &&
                        it.confidence == EvidenceConfidence.HIGH
                },
            )
        }

    private fun endpoint(
        label: String = "Cloudflare IPv4",
        url: String = "https://cloudflare.com/cdn-cgi/trace",
        targetHost: String = "cloudflare.com",
        family: CdnPullingAddressFamily,
    ): CdnPullingEndpointDescriptor =
        CdnPullingEndpointDescriptor(
            label = label,
            url = url,
            targetHost = targetHost,
            addressFamily = family,
        )

    private fun testDispatchers(): AppCoroutineDispatchers {
        val dispatcher = UnconfinedTestDispatcher()
        return AppCoroutineDispatchers(
            default = dispatcher,
            io = dispatcher,
            main = dispatcher,
        )
    }
}
