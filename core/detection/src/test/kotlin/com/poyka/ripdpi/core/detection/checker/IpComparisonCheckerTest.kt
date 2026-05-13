package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.IpComparisonAddressFamily
import com.poyka.ripdpi.core.detection.IpComparisonDnsRecords
import com.poyka.ripdpi.core.detection.IpComparisonEndpointDescriptor
import com.poyka.ripdpi.core.detection.IpComparisonEndpointGroup
import com.poyka.ripdpi.core.detection.IpComparisonEndpointStatus
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IpComparisonCheckerTest {
    @Test
    fun `cross group mismatch produces high confidence finding`() =
        runTest {
            val endpoints = IpComparisonChecker.DEFAULT_ENDPOINTS
            val client =
                FakeIpComparisonEndpointClient(
                    endpoints = endpoints,
                    waitForAllEndpoints = true,
                ) { endpoint ->
                    if (endpoint.group == IpComparisonEndpointGroup.RU) {
                        "1.2.3.4"
                    } else {
                        "5.6.7.8"
                    }
                }

            val result =
                IpComparisonChecker.check(
                    dispatchers = testDispatchers(),
                    endpointClient = client,
                    dnsResolver = FakeIpComparisonDnsResolver(endpoints),
                    endpoints = endpoints,
                )

            assertEquals(11, endpoints.size)
            assertEquals(endpoints.size, result.endpoints.size)
            assertEquals(endpoints.size, client.probedLabels.toSet().size)
            assertEquals(endpoints.size, client.maxInFlight.get())
            assertTrue(result.category.needsReview)
            assertTrue(
                result.category.findings.any {
                    it.source == EvidenceSource.IP_COMPARISON &&
                        it.confidence == EvidenceConfidence.HIGH &&
                        it.needsReview
                },
            )
            assertTrue(
                result.category.evidence.any {
                    it.source == EvidenceSource.IP_COMPARISON &&
                        it.confidence == EvidenceConfidence.HIGH
                },
            )
        }

    @Test
    fun `no finding when all groups agree`() =
        runTest {
            val endpoints = IpComparisonChecker.DEFAULT_ENDPOINTS

            val result =
                IpComparisonChecker.check(
                    dispatchers = testDispatchers(),
                    endpointClient = FakeIpComparisonEndpointClient(endpoints) { "1.2.3.4" },
                    dnsResolver = FakeIpComparisonDnsResolver(endpoints),
                    endpoints = endpoints,
                )

            assertFalse(result.category.needsReview)
            assertFalse(
                result.category.findings.any {
                    it.source == EvidenceSource.IP_COMPARISON &&
                        it.confidence == EvidenceConfidence.HIGH
                },
            )
        }

    @Test
    fun `result non null when 3 endpoints unreachable`() =
        runTest {
            val endpoints = IpComparisonChecker.DEFAULT_ENDPOINTS
            val failingLabels =
                endpoints
                    .filterNot { it.addressFamily == IpComparisonAddressFamily.IPV6 }
                    .take(3)
                    .map { it.label }
                    .toSet()

            val result =
                IpComparisonChecker.check(
                    dispatchers = testDispatchers(),
                    endpointClient =
                        FakeIpComparisonEndpointClient(endpoints) { endpoint ->
                            if (endpoint.label in failingLabels) {
                                throw IOException("unreachable")
                            }
                            "1.2.3.4"
                        },
                    dnsResolver = FakeIpComparisonDnsResolver(endpoints),
                    endpoints = endpoints,
                )

            assertNotNull(result)
            assertEquals(endpoints.size, result.endpoints.size)
            assertEquals(3, result.endpoints.count { it.status == IpComparisonEndpointStatus.ERROR })
            assertTrue(result.category.findings.any { it.description.contains("[ERROR]") })
        }

    @Test
    fun `ipv6 absence not an error`() =
        runTest {
            val endpoints = IpComparisonChecker.DEFAULT_ENDPOINTS

            val result =
                IpComparisonChecker.check(
                    dispatchers = testDispatchers(),
                    endpointClient =
                        FakeIpComparisonEndpointClient(endpoints) { endpoint ->
                            if (endpoint.addressFamily == IpComparisonAddressFamily.IPV6) {
                                throw SocketTimeoutException("IPv6 unavailable")
                            }
                            "1.2.3.4"
                        },
                    dnsResolver =
                        FakeIpComparisonDnsResolver(
                            endpoints = endpoints,
                            ipv6Available = false,
                        ),
                    endpoints = endpoints,
                )

            assertEquals(endpoints.size, result.endpoints.size)
            assertEquals(
                endpoints.count { it.addressFamily == IpComparisonAddressFamily.IPV6 },
                result.endpoints.count { it.status == IpComparisonEndpointStatus.SKIPPED },
            )
            assertEquals(0, result.endpoints.count { it.status == IpComparisonEndpointStatus.ERROR })
            assertFalse(result.category.findings.any { it.description.contains("[ERROR]") })
            assertFalse(result.category.needsReview)
        }

    @Test
    fun `default endpoint client extracts ipv6 from delimited body`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = """{"ip":"2001:db8::42"}"""))
                val endpoint =
                    IpComparisonEndpointDescriptor(
                        label = "local ipv6 reflector",
                        url = server.url("/ip").toString(),
                        group = IpComparisonEndpointGroup.NON_RU,
                        addressFamily = IpComparisonAddressFamily.IPV4,
                    )

                val reflectedIp = DefaultIpComparisonEndpointClient().fetchReflectedIp(endpoint)

                assertEquals("2001:db8::42", reflectedIp)
            }
        }

    private class FakeIpComparisonEndpointClient(
        private val endpoints: List<IpComparisonEndpointDescriptor>,
        private val waitForAllEndpoints: Boolean = false,
        private val response: suspend (IpComparisonEndpointDescriptor) -> String,
    ) : IpComparisonEndpointClient {
        private val allStarted = CompletableDeferred<Unit>()
        private val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val probedLabels = mutableListOf<String>()

        override suspend fun fetchReflectedIp(endpoint: IpComparisonEndpointDescriptor): String {
            probedLabels += endpoint.label
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> previous.coerceAtLeast(current) }
            if (probedLabels.toSet().size == endpoints.size) {
                allStarted.complete(Unit)
            }
            if (waitForAllEndpoints) {
                allStarted.await()
            }
            return try {
                response(endpoint)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private class FakeIpComparisonDnsResolver(
        private val endpoints: List<IpComparisonEndpointDescriptor>,
        private val ipv6Available: Boolean = true,
    ) : IpComparisonDnsResolver {
        override suspend fun resolve(endpoint: IpComparisonEndpointDescriptor): IpComparisonDnsRecords {
            require(endpoint in endpoints) { "Unexpected endpoint ${endpoint.label}" }
            return IpComparisonDnsRecords(
                aRecords = listOf("203.0.113.10"),
                aaaaRecords =
                    if (ipv6Available || endpoint.addressFamily != IpComparisonAddressFamily.IPV6) {
                        listOf("2001:db8::10")
                    } else {
                        emptyList()
                    },
            )
        }
    }

    private fun testDispatchers(): AppCoroutineDispatchers {
        val dispatcher = UnconfinedTestDispatcher()
        return AppCoroutineDispatchers(
            default = dispatcher,
            io = dispatcher,
            main = dispatcher,
        )
    }
}
