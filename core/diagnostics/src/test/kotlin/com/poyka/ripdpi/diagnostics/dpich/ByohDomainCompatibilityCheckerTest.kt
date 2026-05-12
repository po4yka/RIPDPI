package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

class ByohDomainCompatibilityCheckerTest {
    @Test
    fun defaultConfigRequiresAtLeast128KiBBody() {
        assertEquals(128 * 1024L, ByohConfig(dstIp = "127.0.0.1").rangeTo)
    }

    @Test
    fun fullTransferClassifiedCompatible() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("x".repeat(128)).build())
                server.start()

                val checker = ByohDomainCompatibilityChecker(probe = httpProbe())
                val events =
                    checker
                        .run(mockConfig(server, domainList = listOf("probe.example"), rangeTo = 64))
                        .toList()

                assertEquals(ByohProgress.Probing("probe.example", 1, 1), events[0])
                assertEquals(ByohProgress.Compatible("probe.example", bytesReceived = 64), events[1])
                assertEquals(
                    ByohProgress.Completed(ByohStats(total = 1, compatible = 1, incompatible = 0, bytesReceived = 64)),
                    events[2],
                )
            }
        }

    @Test
    fun partialTransferClassifiedIncompatible() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("short").build())
                server.start()

                val events =
                    ByohDomainCompatibilityChecker(probe = httpProbe())
                        .run(mockConfig(server, domainList = listOf("partial.example"), rangeTo = 64))
                        .toList()

                assertEquals(
                    ByohProgress.Incompatible(
                        domain = "partial.example",
                        reason = ByohIncompatibleReason.PartialBody,
                        bytesReceived = 5,
                    ),
                    events[1],
                )
                assertEquals(
                    ByohProgress.Completed(ByohStats(total = 1, compatible = 0, incompatible = 1, bytesReceived = 5)),
                    events[2],
                )
            }
        }

    @Test
    fun probeRoutesConfiguredDomainToDstIpWithoutRealDns() =
        runTest {
            val resolvedHost = AtomicReference<String>()
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("x".repeat(32)).build())
                server.start()

                val probe =
                    OkHttpByohDomainProbe(
                        dnsFactory = { config ->
                            RecordingByohDns(config.dstIp, resolvedHost)
                        },
                    )

                ByohDomainCompatibilityChecker(probe = probe)
                    .run(mockConfig(server, domainList = listOf("routed.example"), rangeTo = 16))
                    .toList()

                assertEquals("routed.example", resolvedHost.get())
            }
        }

    @Test
    fun requestHostHeaderUsesDomainThatAlsoDrivesTlsSni() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("x".repeat(32)).build())
                server.start()

                ByohDomainCompatibilityChecker(probe = httpProbe())
                    .run(mockConfig(server, domainList = listOf("host.example"), rangeTo = 16))
                    .toList()

                val request = server.takeRequest()
                assertTrue(request.headers["Host"].orEmpty().startsWith("host.example"))
            }
        }

    @Test
    fun cancellationKeepsAlreadyEmittedPartialResults() =
        runTest {
            val checker =
                ByohDomainCompatibilityChecker(
                    probe =
                        object : ByohDomainProbe {
                            override suspend fun probe(
                                domain: String,
                                config: ByohConfig,
                            ): ByohProbeResult {
                                if (domain == "slow.example") delay(1_000)
                                return ByohProbeResult(domain = domain, compatible = true, bytesReceived = 64)
                            }
                        },
                )
            val events = mutableListOf<ByohProgress>()

            try {
                checker
                    .run(
                        ByohConfig(
                            dstIp = "127.0.0.1",
                            domainList = listOf("done.example", "slow.example"),
                            rangeTo = 64,
                        ),
                    ).collect { event ->
                        events += event
                        if (event is ByohProgress.Probing && event.domain == "slow.example") {
                            throw CancellationException("stop after first result")
                        }
                    }
            } catch (_: CancellationException) {
                // Expected: caller cancellation should not erase already observed progress.
            }

            assertTrue(events.contains(ByohProgress.Compatible("done.example", bytesReceived = 64)))
            assertTrue(events.none { event -> event is ByohProgress.Completed })
        }

    @Test
    fun csvExportIncludesBytesReceived() {
        val csv =
            ByohCsvExporter.toCsv(
                listOf(
                    ByohProbeResult(domain = "ok.example", compatible = true, bytesReceived = 64),
                    ByohProbeResult(
                        domain = "partial.example",
                        compatible = false,
                        bytesReceived = 12,
                        reason = ByohIncompatibleReason.PartialBody,
                    ),
                ),
            )

        assertEquals(
            """
            domain,compatible,bytes_received
            ok.example,true,64
            partial.example,false,12
            """.trimIndent(),
            csv,
        )
    }

    private fun mockConfig(
        server: MockWebServer,
        domainList: List<String>,
        rangeTo: Long,
    ): ByohConfig =
        ByohConfig(
            dstIp = "127.0.0.1",
            dstPort = server.port,
            scheme = "http",
            domainList = domainList,
            rangeTo = rangeTo,
        )

    private fun httpProbe(): OkHttpByohDomainProbe = OkHttpByohDomainProbe()

    private class RecordingByohDns(
        private val dstIp: String,
        private val resolvedHost: AtomicReference<String>,
    ) : ByohDns {
        override fun lookup(hostname: String): List<InetAddress> {
            resolvedHost.set(hostname)
            return listOf(InetAddress.getByName(dstIp))
        }
    }
}
