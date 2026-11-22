package com.poyka.ripdpi.services.fieldreport

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.services.LocalSocksRoute
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.services.OwnedTlsFingerprintSelection
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FieldReportUploaderTest {
    private val reality = "p0-reality-upcloud-prod"
    private val recordedAt = epoch("2026-11-19T10:15:00Z")
    private val nextHour = epoch("2026-11-19T11:05:00Z")
    private val route = LocalSocksRoute("127.0.0.1", 1080)
    private val config = RipdpiFieldReport("https://reports.example.test/v1/field-report", "cohort-a", 2)

    private val member = FieldReportMember("bundle-group", "bundle-member")

    private fun target() = FieldReportTarget(config, mapOf(member to reality))

    private class Harness(
        val file: File = File.createTempFile("field-report", ".json").apply { delete() },
        var consent: Boolean = true,
        var now: Long = 0,
        var tunnel: LocalSocksRoute? = null,
        var network: FieldReportNetwork = FieldReportNetwork("server_hint", "wifi", asn = 64500, country = "ZZ"),
        var targets: List<FieldReportTarget> = emptyList(),
        var accept: Boolean = true,
        var upstream: FieldReportUpstream? = FieldReportUpstream("bundle-group", "bundle-member"),
    ) {
        val requests = mutableListOf<Pair<String, String>>()
        val ledger = FieldReportLedger(file, { consent }, { now }, { network }, { null })
        var beforePost: suspend () -> Unit = {}
        var afterPost: () -> Unit = {}
        var routeCurrent = true
        val uploader get() = withLedger(ledger)

        fun withLedger(store: FieldReportLedger): FieldReportUploader =
            FieldReportUploader(
                ledger = store,
                consent = { consent },
                targets = { targets },
                appVersion = "1.4",
                tunnel = {
                    val activeRoute = tunnel
                    val activeUpstream = upstream
                    if (activeRoute != null && activeUpstream != null) {
                        FieldReportTunnel(activeRoute, activeUpstream) { routeCurrent }
                    } else {
                        null
                    }
                },
                poster = { _, target, body, stillCurrent ->
                    beforePost()
                    if (!stillCurrent()) {
                        FieldReportPostResult.RouteChanged
                    } else {
                        requests += target.endpoint to body
                        afterPost()
                        if (accept) FieldReportPostResult.Accepted else FieldReportPostResult.Failed
                    }
                },
                clock = { now },
            )
    }

    private suspend fun harnessWithHours(
        hours: Int,
        attempts: Int = 5,
    ): Harness {
        val harness = Harness(tunnel = route, targets = listOf(target()))
        repeat(hours) { hour ->
            harness.now = recordedAt + hour * MillisPerHour
            repeat(attempts) {
                harness.ledger.update {
                    it.record(
                        FieldReportAttempt(reality, "vless-reality", null, 80, member),
                        harness.network,
                        harness.now,
                    )
                }
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
    fun `an hour on two networks sends one report per network with its own source`() =
        runTest {
            val harness = Harness(tunnel = route, targets = listOf(target()))
            harness.now = recordedAt
            repeat(5) { harness.ledger.recordAttempt(harness) }
            harness.network = FieldReportNetwork("sim", "cellular", mcc = "001", mnc = "01")
            repeat(5) { harness.ledger.recordAttempt(harness) }
            harness.now = nextHour

            harness.uploader.uploadCompletedHours()

            assertEquals(2, harness.requests.size)
            val networks = harness.requests.map { (_, body) -> contractJsonOf(body).getValue("network").toString() }
            assertEquals(
                listOf(
                    "{\"source\":\"server_hint\",\"asn\":64500,\"country\":\"ZZ\",\"network_type\":\"wifi\"}",
                    "{\"source\":\"sim\",\"operator\":{\"mcc\":\"001\",\"mnc\":\"01\"},\"network_type\":\"cellular\"}",
                ),
                networks,
            )
            harness.requests.forEach { (_, body) ->
                assertEquals(emptyList<String>(), ClientFieldReportSchema().errors(contractJsonOf(body)))
            }
        }

    private suspend fun FieldReportLedger.recordAttempt(harness: Harness) =
        update {
            it.record(
                FieldReportAttempt(reality, "vless-reality", null, 80, member),
                harness.network,
                harness.now,
            )
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
    fun `a report is sent only while the tunnel exits through a member of the target`() =
        runTest {
            val harness = harnessWithHours(1)
            for (other in listOf(null, FieldReportUpstream(null, null), FieldReportUpstream("other", "foreign"))) {
                harness.upstream = other
                harness.uploader.uploadCompletedHours()
                assertEquals(0, harness.requests.size)
                assertEquals(
                    1,
                    harness.ledger
                        .snapshot()
                        .hours.size,
                )
            }

            // A non-selector relay names only its member (profile) id.
            harness.upstream = FieldReportUpstream(null, "bundle-member")
            harness.uploader.uploadCompletedHours()
            assertEquals(1, harness.requests.size)
        }

    @Test
    fun `the same tag is isolated by subscription and another owner's buckets remain queued`() =
        runTest {
            val harness = harnessWithHours(1)
            val other = FieldReportMember("other-group", "other-member")
            val otherConfig = config.copy(endpoint = "https://other.example.test/v1/field-report")
            harness.targets += FieldReportTarget(otherConfig, mapOf(other to reality))
            repeat(7) {
                harness.ledger.update {
                    it.record(
                        FieldReportAttempt(reality, "vless-reality", null, 80, other),
                        harness.network,
                        recordedAt,
                    )
                }
            }

            harness.uploader.uploadCompletedHours()
            assertEquals(listOf(config.endpoint), harness.requests.map { it.first })
            assertTrue(
                harness.requests
                    .single()
                    .second
                    .contains("\"attempts\":5"),
            )
            assertEquals(
                7,
                harness.ledger
                    .snapshot()
                    .hours
                    .single()
                    .buckets
                    .single()
                    .attempts
                    .toInt(),
            )

            harness.upstream = FieldReportUpstream(other.groupId, other.memberId)
            harness.uploader.uploadCompletedHours()
            assertEquals(otherConfig.endpoint, harness.requests.last().first)
            assertTrue(
                harness.requests
                    .last()
                    .second
                    .contains("\"attempts\":7"),
            )
        }

    @Test
    fun `the accepted request remains claimed after a worker interruption`() =
        runTest {
            val harness = harnessWithHours(1)
            val interrupted = CancellationException("worker stopped")
            val reopened = FieldReportLedger(harness.file, { true }, { harness.now }, { harness.network }, { null })
            harness.afterPost = { throw interrupted }

            try {
                harness.uploader.uploadCompletedHours()
                error("worker continued after cancellation")
            } catch (error: CancellationException) {
                assertEquals(interrupted, error)
            }
            harness.afterPost = {}
            harness.withLedger(reopened).uploadCompletedHours()

            assertEquals(1, harness.requests.size)
            assertEquals(listOf(harness.now), reopened.snapshot().uploads[config.endpoint])
            assertTrue(reopened.snapshot().hours.isEmpty())
        }

    @Test
    fun `a revoked route makes no request`() =
        runTest {
            val harness = harnessWithHours(1)
            harness.routeCurrent = false

            harness.uploader.uploadCompletedHours()

            assertTrue(harness.requests.isEmpty())
            assertEquals(
                1,
                harness.ledger
                    .snapshot()
                    .hours.size,
            )
        }

    @Test
    fun `a route change after the claim keeps the definitely unsent report queued`() =
        runTest {
            val harness = harnessWithHours(1)
            harness.beforePost = { harness.routeCurrent = false }

            harness.uploader.uploadCompletedHours()

            val queued = harness.ledger.snapshot()
            assertTrue(harness.requests.isEmpty())
            assertTrue(queued.uploads.isEmpty())
            assertEquals(1, queued.hours.size)

            harness.beforePost = {}
            harness.routeCurrent = true
            harness.uploader.uploadCompletedHours()
            assertEquals(1, harness.requests.size)
        }

    @Test
    fun `a claim from before consent revocation is neither dispatched nor restored`() =
        runTest {
            val harness = harnessWithHours(1)
            harness.beforePost = { harness.ledger.clear() }

            harness.uploader.uploadCompletedHours()

            val queued = harness.ledger.snapshot()
            assertTrue(harness.requests.isEmpty())
            assertTrue(queued.uploads.isEmpty())
            assertTrue(queued.hours.isEmpty())
        }

    @Test
    fun `the upstream is read only from the applied configuration of the current runtime`() {
        val selection =
            RuntimeConfigurationSelection("relay", selectorGroupId = "bundle-group", selectorMemberId = "m1")
        val applied =
            RuntimeConfigurationApplication.Applied(
                AppliedRuntimeConfiguration(
                    runtimeId = "runtime-1",
                    revision = 1,
                    appliedAt = 0,
                    mode = Mode.VPN,
                    requestedSelection = selection,
                    effectiveSelection = selection,
                    dns = RuntimeConfigurationDns("plain", "system"),
                    strategy = RuntimeConfigurationStrategy(enabled = false),
                    reason = RuntimeConfigurationApplyReason.InitialStart,
                ),
            )

        assertEquals(FieldReportUpstream("bundle-group", "m1"), fieldReportUpstream(applied, "runtime-1"))
        assertNull(fieldReportUpstream(applied, "runtime-2"))
        assertNull(fieldReportUpstream(applied, null))
        assertNull(fieldReportUpstream(RuntimeConfigurationApplication.Unknown, "runtime-1"))
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
                        FieldReportAttempt(reality, "vless-reality", null, 80, member),
                        harness.network,
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
                        FieldReportAttempt(reality, "vless-reality", null, 80, member),
                        harness.network,
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
    fun `no request without an https target or for an hour without a recorded network`() =
        runTest {
            val plainHttp = harnessWithHours(1)
            plainHttp.targets =
                listOf(target().copy(config = config.copy(endpoint = "http://reports.example.test/v1")))
            plainHttp.uploader.uploadCompletedHours()
            assertEquals(0, plainHttp.requests.size)

            val noTarget = harnessWithHours(1)
            noTarget.targets = emptyList()
            noTarget.uploader.uploadCompletedHours()
            assertEquals(0, noTarget.requests.size)

            // An hour from a file written before the network was recorded is dropped unsent.
            val noNetwork = harnessWithHours(1)
            noNetwork.ledger.update { state -> state.copy(hours = state.hours.map { it.copy(network = null) }) }
            noNetwork.uploader.uploadCompletedHours()
            assertEquals(0, noNetwork.requests.size)
            assertEquals(emptyList<FieldReportHour>(), noNetwork.ledger.snapshot().hours)
        }

    @Test
    fun `the poster sends the cohort token through the tunnel listener and accepts only 202`() =
        runTest {
            val tls = InterceptingTlsClientFactory(code = 202)
            val poster = OkHttpFieldReportPoster(tls)

            assertEquals(
                FieldReportPostResult.Accepted,
                poster.post(route, config, "{\"schema_version\":1}") { true },
            )
            val request = requireNotNull(tls.request)
            assertEquals("POST", request.method)
            assertEquals(config.endpoint, request.url.toString())
            assertEquals("Bearer cohort-a", request.header("Authorization"))
            assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
            assertEquals("{\"schema_version\":1}", Buffer().also { request.body?.writeTo(it) }.readUtf8())
            val client = requireNotNull(tls.client)
            assertEquals(Proxy.NO_PROXY, client.proxy)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(
                FieldReportPostResult.Failed,
                OkHttpFieldReportPoster(InterceptingTlsClientFactory(code = 429)).post(route, config, "{}") { true },
            )
            assertEquals(
                FieldReportPostResult.Failed,
                OkHttpFieldReportPoster(
                    InterceptingTlsClientFactory(failure = IOException("reset")),
                ).post(route, config, "{}") { true },
            )
            assertEquals(FieldReportPostResult.RouteChanged, poster.post(route, config, "{}") { false })
        }

    @Test
    fun `the report host is resolved by the tunnel listener and never by system DNS`() =
        runTest {
            // Even a route that asks for local resolution sends the host name to the listener.
            val tls = InterceptingTlsClientFactory(code = 202)
            OkHttpFieldReportPoster(tls).post(route.copy(resolveNamesLocally = true), config, "{}") { true }
            val resolved = requireNotNull(tls.client).dns.lookup("reports.example.test").single()
            assertEquals("reports.example.test", resolved.hostName)
            assertTrue(resolved.address.all { it == 0.toByte() })
        }

    @Test
    fun `the poster connects only to the tunnel listener and speaks SOCKS5 there`() =
        runTest {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                val greeting = CompletableFuture<Int>()
                thread { listener.accept().use { greeting.complete(it.getInputStream().read()) } }
                val tls = InterceptingTlsClientFactory(code = 202)
                OkHttpFieldReportPoster(
                    tls,
                ).post(LocalSocksRoute("127.0.0.1", listener.localPort), config, "{}") { true }

                val socket = requireNotNull(tls.client).socketFactory.createSocket()
                runCatching { socket.connect(InetSocketAddress.createUnresolved("reports.example.test", 443), 2_000) }
                socket.close()

                // The SOCKS5 version byte reached the listener, so the report host is a CONNECT target there.
                assertEquals(5, greeting.get(5, TimeUnit.SECONDS))
            }
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
