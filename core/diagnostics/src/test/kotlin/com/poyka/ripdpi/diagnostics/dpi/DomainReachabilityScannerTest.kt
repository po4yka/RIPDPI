package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
import com.poyka.ripdpi.core.detection.dpi.ProbeStage
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class DomainReachabilityScannerTest {
    @Test
    fun http451ReturnsBlocked() =
        runTest {
            val result =
                scanner(
                    attempts = {
                        when (it) {
                            ReachabilityProbeKind.HTTP -> AttemptResult(AttemptStatus.BLOCKED, statusCode = 451)
                            else -> AttemptResult(AttemptStatus.OK, statusCode = 200)
                        }
                    },
                ).scan(listOf("blocked.example"), stubIps = emptySet()).single()

            assertEquals(AttemptStatus.BLOCKED, result.http.status)
            assertEquals(DomainVerdict.BLOCKED, result.verdict)
        }

    @Test
    fun redirectToSameDomainReturnsRedirOk() =
        runTest {
            val result =
                scanner(
                    attempts = {
                        AttemptResult(
                            status = AttemptStatus.REDIR_OK,
                            statusCode = 301,
                            detail = "https://example.com/foo",
                        )
                    },
                ).scan(listOf("example.com"), stubIps = emptySet()).single()

            assertEquals(AttemptStatus.REDIR_OK, result.http.status)
            assertEquals(DomainVerdict.OK, result.verdict)
        }

    @Test
    fun redirectToForeignDomainReturnsRedirSuspicious() =
        runTest {
            val result =
                scanner(
                    attempts = {
                        AttemptResult(
                            status = AttemptStatus.REDIR_SUSPICIOUS,
                            statusCode = 301,
                            detail = "https://block.gov/foo",
                        )
                    },
                ).scan(listOf("example.com"), stubIps = emptySet()).single()

            assertEquals(AttemptStatus.REDIR_SUSPICIOUS, result.http.status)
            assertEquals(DomainVerdict.BLOCKED, result.verdict)
        }

    @Test
    fun redirectToStubIpReturnsIspPage() =
        runTest {
            val result =
                scanner(
                    attempts = {
                        AttemptResult(
                            status = AttemptStatus.REDIR_SUSPICIOUS,
                            statusCode = 302,
                            detail = "http://100.64.0.5/block",
                        )
                    },
                ).scan(listOf("example.com"), stubIps = setOf("100.64.0.5")).single()

            assertEquals(AttemptStatus.ISP_PAGE, result.http.status)
            assertEquals(DomainVerdict.ISP_PAGE, result.verdict)
        }

    @Test
    fun stubIpShortCircuitsToIspPage() =
        runTest {
            var attempts = 0
            val result =
                scanner(
                    resolver = { listOf("100.64.0.5") },
                    attempts = {
                        attempts += 1
                        AttemptResult(AttemptStatus.OK)
                    },
                ).scan(listOf("example.com"), stubIps = setOf("100.64.0.5")).single()

            assertEquals(DomainVerdict.ISP_PAGE, result.verdict)
            assertEquals(0, attempts)
            assertEquals(AttemptStatus.ISP_PAGE, result.tls13.status)
        }

    @Test
    fun fakeIpShortCircuitsToFakeIp() =
        runTest {
            val result =
                scanner(resolver = { listOf("198.18.0.1") })
                    .scan(listOf("example.com"), stubIps = emptySet())
                    .single()

            assertEquals(DomainVerdict.FAKE_IP, result.verdict)
            assertEquals(AttemptStatus.FAKE_IP, result.tls13.status)
        }

    @Test
    fun tcp16BandTimeoutClassified() =
        runTest {
            val result =
                scanner(
                    attempts = {
                        AttemptResult(
                            status = AttemptStatus.TCP16_BAND_TIMEOUT,
                            bytesRead = 17_000,
                            error = DpiProbeError.Unknown,
                        )
                    },
                ).scan(listOf("example.com"), stubIps = emptySet()).single()

            assertEquals(DomainVerdict.TCP16_BAND, result.verdict)
        }

    @Test
    fun tls12OnlyBlockDetectedWhenTls13Succeeds() =
        runTest {
            val result =
                scanner(
                    attempts = {
                        when (it) {
                            ReachabilityProbeKind.TLS13 -> {
                                AttemptResult(AttemptStatus.OK, statusCode = 200)
                            }

                            ReachabilityProbeKind.TLS12 -> {
                                AttemptResult(
                                    AttemptStatus.ERROR,
                                    error = DpiProbeError.TlsRst,
                                )
                            }

                            ReachabilityProbeKind.HTTP -> {
                                AttemptResult(AttemptStatus.OK, statusCode = 200)
                            }
                        }
                    },
                ).scan(listOf("example.com"), stubIps = emptySet()).single()

            assertEquals(DomainVerdict.TLS_VERSION_BLOCK, result.verdict)
        }

    @Test
    fun defaultAttemptMapsSocketTimeoutInTcp16Band() {
        val result =
            DomainReachabilityScanner.classifyException(
                SocketTimeoutException("read timed out"),
                ProbeStage.READING_DATA,
                bytesRead = 16_384,
            )

        assertEquals(AttemptStatus.TCP16_BAND_TIMEOUT, result.status)
        assertEquals(DpiProbeError.Unknown, result.error)
        assertTrue(result.detail.contains("TCP16"))
    }

    @Test
    fun defaultAttemptUsesDpiClassifierWithStage() {
        val result =
            DomainReachabilityScanner.classifyException(
                SocketTimeoutException("connect timed out"),
                ProbeStage.TCP_CONNECT,
                bytesRead = 0,
            )

        assertEquals(AttemptStatus.ERROR, result.status)
        assertEquals(DpiProbeError.SynDrop, result.error)
        assertEquals(ProbeStage.TCP_CONNECT, result.stage)
    }

    @Test
    fun httpAttemptUsesMockWebServer451ReturnsBlocked() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(451)
                        .body("blocked")
                        .build(),
                )
                server.start()

                val result = mockHttpRunner(server)("example.com", ReachabilityProbeKind.HTTP, emptySet())

                assertEquals(AttemptStatus.BLOCKED, result.status)
                assertEquals(451, result.statusCode)
                assertEquals(ProbeStage.READING_DATA, result.stage)
                assertEquals("HEAD", server.takeRequest(1, TimeUnit.SECONDS)?.method)
            }
        }

    @Test
    fun httpAttemptUsesMockWebServerRedirectClassification() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(301)
                        .addHeader("Location", "https://example.com/path")
                        .build(),
                )
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(302)
                        .addHeader("Location", "https://block.gov/path")
                        .build(),
                )
                server.start()
                val runner = mockHttpRunner(server)

                val sameDomain = runner("example.com", ReachabilityProbeKind.HTTP, emptySet())
                val foreignDomain = runner("example.com", ReachabilityProbeKind.HTTP, emptySet())

                assertEquals(AttemptStatus.REDIR_OK, sameDomain.status)
                assertEquals(AttemptStatus.REDIR_SUSPICIOUS, foreignDomain.status)
            }
        }

    private fun scanner(
        resolver: suspend (String) -> List<String> = { listOf("93.184.216.34") },
        attempts: suspend (
            ReachabilityProbeKind,
        ) -> AttemptResult = { AttemptResult(AttemptStatus.OK, statusCode = 200) },
    ): DomainReachabilityScanner =
        DomainReachabilityScanner(
            resolver = resolver,
            attemptRunner =
                { _, kind, _ -> attempts(kind) },
        )

    private fun mockHttpRunner(server: MockWebServer): OkHttpDomainReachabilityAttemptRunner =
        OkHttpDomainReachabilityAttemptRunner(
            timeoutMs = 1_000,
            endpointResolver =
                ReachabilityProbeEndpointResolver { domain, _ ->
                    ReachabilityProbeEndpoint(
                        connectHost = "127.0.0.1",
                        port = server.port,
                        hostHeader = domain,
                    )
                },
        )
}
