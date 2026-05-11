package com.poyka.ripdpi.diagnostics.rkn

import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class RknLayeredProbePipelineTest {
    @Test
    fun `dns block short circuits pipeline no tcp attempt`() =
        runTest {
            val tcp = FakeTcpProbe()
            val pipeline =
                pipeline(
                    dns =
                        FakeDnsProbe(
                            dnsResult(
                                verdict = DnsComparisonVerdict.DNS_BLOCK,
                                confidence = DnsComparisonConfidence.HIGH,
                            ),
                        ),
                    tcp = tcp,
                )

            val result = pipeline.checkUrl(target("blocked", "https://blocked.example/"))

            assertEquals(RknVerdict.DNS_BLOCK, result.verdict)
            assertEquals(RknConfidence.HIGH, result.confidence)
            assertEquals(0, tcp.calls)
            assertEquals(setOf("1.1.1.1"), result.dohIps)
            assertEquals("System resolver failed while DoH returned A records", result.notes.single())
        }

    @Test
    fun `dns ok tcp reset returns tcp reset medium`() =
        runTest {
            val pipeline =
                pipeline(
                    tcp = FakeTcpProbe(error = ConnectException("Connection reset")),
                )

            val result = pipeline.checkUrl(target("reset", "https://reset.example/"))

            assertEquals(RknVerdict.TCP_RESET, result.verdict)
            assertEquals(RknConfidence.MEDIUM, result.confidence)
            assertFalse(result.tcpOk)
            assertEquals("Connection reset", result.tcpError)
        }

    @Test
    fun `dns ok tcp ok tls reset returns tls block`() =
        runTest {
            val pipeline =
                pipeline(
                    tls = FakeTlsProbe(error = SSLException("connection reset by peer")),
                )

            val result = pipeline.checkUrl(target("tls", "https://tls.example/"))

            assertEquals(RknVerdict.TLS_BLOCK, result.verdict)
            assertEquals(RknConfidence.MEDIUM, result.confidence)
            assertEquals(true, result.tcpOk)
            assertFalse(result.tlsOk)
            assertEquals("connection reset by peer", result.tlsError)
        }

    @Test
    fun `dns tcp tls ok http stub marker returns http stub high`() =
        runTest {
            val pipeline =
                pipeline(
                    http = FakeHttpProbe(RknHttpProbeResult(statusCode = 200, bodyPreview = "доступ ограничен")),
                )

            val result = pipeline.checkUrl(target("stub", "https://stub.example/"))

            assertEquals(RknVerdict.HTTP_STUB, result.verdict)
            assertEquals(RknConfidence.HIGH, result.confidence)
            assertEquals(200, result.statusCode)
        }

    @Test
    fun `http 451 returns http stub high`() =
        runTest {
            val pipeline =
                pipeline(
                    http = FakeHttpProbe(RknHttpProbeResult(statusCode = 451, bodyPreview = "")),
                )

            val result = pipeline.checkUrl(target("legal", "https://legal.example/"))

            assertEquals(RknVerdict.HTTP_STUB, result.verdict)
            assertEquals(RknConfidence.HIGH, result.confidence)
            assertEquals(451, result.statusCode)
        }

    @Test
    fun `all ok no dns mismatch returns ok high`() =
        runTest {
            val result = pipeline().checkUrl(target("ok", "https://ok.example/"))

            assertEquals(RknVerdict.OK, result.verdict)
            assertEquals(RknConfidence.HIGH, result.confidence)
            assertEquals(true, result.tcpOk)
            assertEquals(true, result.tlsOk)
            assertEquals(200, result.statusCode)
        }

    @Test
    fun `all ok with dns mismatch returns ok medium`() =
        runTest {
            val result =
                pipeline(
                    dns =
                        FakeDnsProbe(
                            dnsResult(
                                verdict = DnsComparisonVerdict.DNS_REWRITE,
                                confidence = DnsComparisonConfidence.MEDIUM,
                                sysIps = setOf("2.2.2.2"),
                                dohIps = setOf("1.1.1.1"),
                            ),
                        ),
                ).checkUrl(target("rewrite", "https://rewrite.example/"))

            assertEquals(RknVerdict.OK, result.verdict)
            assertEquals(RknConfidence.MEDIUM, result.confidence)
            assertEquals(true, result.dnsMismatch)
        }

    @Test
    fun `tcp timeout returns timeout low`() =
        runTest {
            val result =
                pipeline(
                    tcp = FakeTcpProbe(error = SocketTimeoutException("connect timed out")),
                ).checkUrl(target("timeout", "https://timeout.example/"))

            assertEquals(RknVerdict.TIMEOUT, result.verdict)
            assertEquals(RknConfidence.LOW, result.confidence)
            assertEquals("connect timed out", result.tcpError)
        }

    @Test
    fun `cert cn extracted on tls success`() =
        runTest {
            val result =
                pipeline(
                    tls = FakeTlsProbe(result = RknTlsProbeResult(ok = true, timeMs = 18, certCn = "*.example.com")),
                ).checkUrl(target("cert", "https://cert.example/"))

            assertEquals("*.example.com", result.tlsCertCn)
            assertEquals(true, result.tlsOk)
        }

    @Test
    fun `check result carries diagnostics tls client state`() =
        runTest {
            val tlsState =
                DiagnosticsTlsClientState(
                    profileId = "chrome_stable",
                    nativeOwnedTlsAvailable = false,
                    fallbackActive = true,
                    fallbackReason = "android_okhttp_fingerprint_template",
                )

            val result =
                pipeline(
                    tlsClientStateProvider = { tlsState },
                ).checkUrl(target("ok", "https://ok.example/"))

            assertEquals(tlsState, result.tlsClientState)
        }

    @Test
    fun `default tls probe uses injected diagnostics client factory`() =
        runTest {
            var factoryCalls = 0
            var requestedHost = ""
            val probe =
                HttpClientRknTlsProbe(
                    clientBuilder = { configure ->
                        factoryCalls += 1
                        OkHttpClient
                            .Builder()
                            .addInterceptor(
                                Interceptor { chain ->
                                    requestedHost = chain.request().url.host
                                    Response
                                        .Builder()
                                        .request(chain.request())
                                        .protocol(Protocol.HTTP_1_1)
                                        .code(204)
                                        .message("No Content")
                                        .body(ByteArray(0).toResponseBody())
                                        .build()
                                },
                            ).apply(configure)
                            .build()
                    },
                )

            val result = probe.handshake("tls.example", 443, timeoutMs = 1000)

            assertEquals(true, result.ok)
            assertEquals(1, factoryCalls)
            assertEquals("tls.example", requestedHost)
        }

    @Test
    fun `parallel iter emits results as completed`() =
        runTest {
            val pipeline =
                pipeline(
                    dns =
                        object : RknDnsProbe {
                            override suspend fun compare(host: String): DnsComparisonResult {
                                delay(if (host == "slow.example") 50 else 10)
                                return dnsResult()
                            }
                        },
                )

            val results =
                pipeline
                    .iterCheckUrls(
                        listOf(
                            target("slow", "https://slow.example/"),
                            target("fast", "https://fast.example/"),
                        ),
                        workers = 2,
                    ).toList()

            assertEquals(listOf("fast", "slow"), results.map { it.name })
        }

    private fun pipeline(
        dns: RknDnsProbe = FakeDnsProbe(dnsResult()),
        tcp: RknTcpProbe = FakeTcpProbe(),
        tls: RknTlsProbe = FakeTlsProbe(),
        http: RknHttpProbe = FakeHttpProbe(RknHttpProbeResult(statusCode = 200, bodyPreview = "ok")),
        tlsClientStateProvider: () -> DiagnosticsTlsClientState? = { null },
    ): RknLayeredProbePipeline =
        RknLayeredProbePipeline(
            dnsProbe = dns,
            tcpProbe = tcp,
            tlsProbe = tls,
            httpProbe = http,
            tlsClientStateProvider = tlsClientStateProvider,
            stubPageDetector = RknStubPageDetector(DefaultStubMarkers),
        )

    private fun target(
        name: String,
        url: String,
    ): RknProbeTarget = RknProbeTarget(name = name, url = url)

    private fun dnsResult(
        verdict: DnsComparisonVerdict = DnsComparisonVerdict.OK,
        confidence: DnsComparisonConfidence = DnsComparisonConfidence.LOW,
        sysIps: Set<String> = setOf("1.1.1.1"),
        dohIps: Set<String> = setOf("1.1.1.1"),
    ): DnsComparisonResult =
        DnsComparisonResult(
            verdict = verdict,
            confidence = confidence,
            sysIps = sysIps,
            dohIps = dohIps,
            sysIp = sysIps.firstOrNull(),
            dohIp = dohIps.firstOrNull(),
            dnsMethod = DnsMethod.SYSTEM,
            notes =
                when (verdict) {
                    DnsComparisonVerdict.DNS_BLOCK -> listOf("System resolver failed while DoH returned A records")
                    DnsComparisonVerdict.DNS_REWRITE -> listOf("System resolver returned IPs not seen via DoH")
                    DnsComparisonVerdict.DOWN -> listOf("Both system and DoH lookups failed")
                    DnsComparisonVerdict.OK -> emptyList()
                },
        )

    private class FakeDnsProbe(
        private val result: DnsComparisonResult,
    ) : RknDnsProbe {
        override suspend fun compare(host: String): DnsComparisonResult = result
    }

    private class FakeTcpProbe(
        private val result: RknTcpProbeResult = RknTcpProbeResult(ok = true, timeMs = 12),
        private val error: Exception? = null,
    ) : RknTcpProbe {
        var calls = 0

        override suspend fun connect(
            host: String,
            port: Int,
            timeoutMs: Long,
        ): RknTcpProbeResult {
            calls += 1
            error?.let { throw it }
            return result
        }
    }

    private class FakeTlsProbe(
        private val result: RknTlsProbeResult = RknTlsProbeResult(ok = true, timeMs = 20, certCn = "example.com"),
        private val error: Exception? = null,
    ) : RknTlsProbe {
        override suspend fun handshake(
            host: String,
            port: Int,
            timeoutMs: Long,
        ): RknTlsProbeResult {
            error?.let { throw it }
            return result
        }
    }

    private class FakeHttpProbe(
        private val result: RknHttpProbeResult,
        private val error: Exception? = null,
    ) : RknHttpProbe {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): RknHttpProbeResult {
            error?.let { throw it }
            return result
        }
    }
}
