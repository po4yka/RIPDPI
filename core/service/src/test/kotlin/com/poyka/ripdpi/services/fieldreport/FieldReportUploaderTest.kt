package com.poyka.ripdpi.services.fieldreport

import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.services.LocalSocksRoute
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.services.OwnedTlsFingerprintSelection
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Proxy

class FieldReportUploaderTest {
    private val reality = "p0-reality-upcloud-prod"
    private val recordedAt = epoch("2026-11-19T10:15:00Z")
    private val nextHour = epoch("2026-11-19T11:05:00Z")
    private val route = LocalSocksRoute("127.0.0.1", 1080)
    private val config = RipdpiFieldReport("https://reports.example.test/v1/field-report", "cohort-a", 2)

    private class Harness(
        val file: File = File.createTempFile("field-report", ".json").apply { delete() },
        var consent: Boolean = true,
        var now: Long = 0,
        var tunnel: LocalSocksRoute? = null,
        var network: FieldReportNetwork? = FieldReportNetwork(64500, "ZZ", "wifi"),
        var targets: List<FieldReportTarget> = emptyList(),
        var accept: Boolean = true,
    ) {
        val requests = mutableListOf<Pair<String, String>>()
        val ledger = FieldReportLedger(file, { consent }, { now })
        val uploader =
            FieldReportUploader(
                ledger = ledger,
                consent = { consent },
                targets = { targets },
                network = { network },
                appVersion = "1.4",
                tunnelRoute = { tunnel },
                poster = { _, target, body -> accept.also { requests += target.endpoint to body } },
                clock = { now },
            )
    }

    private suspend fun harnessWithHours(
        hours: Int,
        attempts: Int = 5,
    ): Harness {
        val harness = Harness(tunnel = route, targets = listOf(FieldReportTarget(config, setOf(reality))))
        repeat(hours) { hour ->
            harness.now = recordedAt + hour * MillisPerHour
            repeat(attempts) {
                harness.ledger.update { it.record(FieldReportAttempt(reality, "vless-reality", null, 80), harness.now) }
            }
        }
        harness.now = recordedAt + hours * MillisPerHour
        return harness
    }

    @Test
    fun `a completed hour is sent once through the tunnel and leaves the queue`() =
        runTest {
            val harness = harnessWithHours(1)

            harness.uploader.uploadCompletedHours()
            harness.uploader.uploadCompletedHours()

            assertEquals(1, harness.requests.size)
            val (endpoint, body) = harness.requests.single()
            assertEquals(config.endpoint, endpoint)
            assertEquals(emptyList<String>(), ClientFieldReportSchema().errors(contractJsonOf(body)))
            assertTrue(body.contains("\"start_utc\":\"2026-11-19T10:00:00Z\""))
            assertEquals(emptyList<FieldReportHour>(), harness.ledger.snapshot().hours)
        }

    @Test
    fun `the current hour is never sent`() =
        runTest {
            val harness = harnessWithHours(1)
            harness.now = recordedAt

            harness.uploader.uploadCompletedHours()

            assertEquals(0, harness.requests.size)
            assertEquals(
                1,
                harness.ledger
                    .snapshot()
                    .hours.size,
            )
        }

    @Test
    fun `without the tunnel nothing is sent and the hour stays pending`() =
        runTest {
            val harness = harnessWithHours(1)
            harness.tunnel = null

            harness.uploader.uploadCompletedHours()

            assertEquals(0, harness.requests.size)
            assertEquals(
                1,
                harness.ledger
                    .snapshot()
                    .hours.size,
            )
            // A pending hour ages out after 24 hours instead of waiting forever.
            harness.now = nextHour + MillisPerDay
            harness.uploader.uploadCompletedHours()
            assertEquals(emptyList<FieldReportHour>(), harness.ledger.snapshot().hours)
        }

    @Test
    fun `revoked consent sends nothing and deletes the pending hours`() =
        runTest {
            val harness = harnessWithHours(1)
            harness.consent = false

            harness.uploader.uploadCompletedHours()

            assertEquals(0, harness.requests.size)
            assertFalse(harness.file.exists())
        }

    @Test
    fun `the daily cap limits requests in any 24 hours`() =
        runTest {
            val harness = harnessWithHours(3)

            harness.uploader.uploadCompletedHours()
            assertEquals(2, harness.requests.size)

            // A later hour inside the same 24 hours is dropped at the cap; after 24 hours requests resume.
            repeat(
                5,
            ) {
                harness.ledger.update {
                    it.record(
                        FieldReportAttempt(reality, "vless-reality", null, 80),
                        harness.now,
                    )
                }
            }
            harness.now += MillisPerHour
            harness.uploader.uploadCompletedHours()
            assertEquals(2, harness.requests.size)

            harness.now += MillisPerDay
            repeat(
                5,
            ) {
                harness.ledger.update {
                    it.record(
                        FieldReportAttempt(reality, "vless-reality", null, 80),
                        harness.now,
                    )
                }
            }
            harness.now += MillisPerHour
            harness.uploader.uploadCompletedHours()
            assertEquals(3, harness.requests.size)
        }

    @Test
    fun `a failed request drops the report without a retry`() =
        runTest {
            val harness = harnessWithHours(1)
            harness.accept = false

            harness.uploader.uploadCompletedHours()
            harness.uploader.uploadCompletedHours()

            assertEquals(1, harness.requests.size)
            assertEquals(emptyList<FieldReportHour>(), harness.ledger.snapshot().hours)
        }

    @Test
    fun `an hour with only suppressed buckets makes no request`() =
        runTest {
            val harness = harnessWithHours(1, attempts = 4)

            harness.uploader.uploadCompletedHours()

            assertEquals(0, harness.requests.size)
            assertEquals(emptyList<FieldReportHour>(), harness.ledger.snapshot().hours)
        }

    @Test
    fun `no request without an https target or a network context`() =
        runTest {
            val plainHttp = harnessWithHours(1)
            plainHttp.targets =
                listOf(FieldReportTarget(config.copy(endpoint = "http://reports.example.test/v1"), setOf(reality)))
            plainHttp.uploader.uploadCompletedHours()
            assertEquals(0, plainHttp.requests.size)

            val noTarget = harnessWithHours(1)
            noTarget.targets = emptyList()
            noTarget.uploader.uploadCompletedHours()
            assertEquals(0, noTarget.requests.size)

            // Production has no ASN source yet: the hour is dropped unsent.
            val noNetwork = harnessWithHours(1)
            noNetwork.network = null
            noNetwork.uploader.uploadCompletedHours()
            assertEquals(0, noNetwork.requests.size)
            assertEquals(emptyList<FieldReportHour>(), noNetwork.ledger.snapshot().hours)
        }

    @Test
    fun `the poster sends the cohort token through the tunnel listener and accepts only 202`() =
        runTest {
            val tls = InterceptingTlsClientFactory(code = 202)
            val poster = OkHttpFieldReportPoster(tls)

            assertTrue(poster.post(route, config, "{\"schema_version\":1}"))
            val request = requireNotNull(tls.request)
            assertEquals("POST", request.method)
            assertEquals(config.endpoint, request.url.toString())
            assertEquals("Bearer cohort-a", request.header("Authorization"))
            assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
            assertEquals("{\"schema_version\":1}", Buffer().also { request.body?.writeTo(it) }.readUtf8())
            val client = requireNotNull(tls.client)
            assertEquals(Proxy.NO_PROXY, client.proxy)
            assertFalse(client.retryOnConnectionFailure)
            assertFalse(client.followRedirects)

            assertFalse(OkHttpFieldReportPoster(InterceptingTlsClientFactory(code = 429)).post(route, config, "{}"))
            assertFalse(
                OkHttpFieldReportPoster(
                    InterceptingTlsClientFactory(failure = IOException("reset")),
                ).post(route, config, "{}"),
            )
        }

    /** Builds the poster's client as configured and answers at the application layer, so no socket opens. */
    private class InterceptingTlsClientFactory(
        private val code: Int = 202,
        private val failure: IOException? = null,
    ) : OwnedTlsClientFactory {
        var request: Request? = null
        var client: OkHttpClient? = null

        override fun currentProfile(): String = OwnedTlsFingerprintSelection().profileId

        override fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection =
            OwnedTlsFingerprintSelection()

        override fun create(
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .apply(configure)
                .addInterceptor { chain ->
                    request = chain.request()
                    failure?.let { throw it }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("status")
                        .body("".toResponseBody())
                        .build()
                }.build()
                .also { client = it }

        override fun createForAuthority(
            authority: String?,
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient = create(forcedTlsVersions, configure)
    }
}
