package com.poyka.ripdpi.services.fieldreport

import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class FieldReportNetworkContextTest {
    private val wifi = DefaultNetwork(handle = 101, networkType = "wifi", vpn = false)
    private val otherWifi = DefaultNetwork(handle = 102, networkType = "wifi", vpn = false)
    private val cellular = DefaultNetwork(handle = 201, networkType = "cellular", vpn = false)
    private val target =
        RipdpiFieldReport(
            "https://reports.example.test/v1/field-report",
            "cohort-a",
            4,
            networkHintEndpoint = "https://reports.example.test/v1/network-hint",
        )
    private val golden = contractFile("client-network-hint.golden.json").readText()
    private val hinted = FieldReportNetwork("server_hint", "wifi", asn = 64500, country = "ZZ")

    private class Harness(
        var network: DefaultNetwork?,
        var body: String?,
    ) {
        var consent = true
        var now = epoch("2026-11-19T10:30:00Z")
        var operator: String? = "25001"
        val fetches = mutableListOf<Triple<String, String, Long>>()
        var duringFetch: () -> Unit = {}
        val context =
            FieldReportNetworkContext(
                consent = { consent },
                defaultNetwork = { network },
                operatorCode = { operator },
                fetcher = { endpoint, token, on ->
                    fetches += Triple(endpoint, token, on.handle)
                    duringFetch()
                    body
                },
                clock = { now },
                random = Random(7),
                pause = {},
            )
    }

    @Test
    fun `a cellular network is labelled by its operator and never fetches a hint`() =
        runTest {
            val harness = Harness(cellular, golden)

            harness.context.refreshHint(listOf(target))

            assertEquals(emptyList<Triple<String, String, Long>>(), harness.fetches)
            assertEquals(FieldReportNetwork("sim", "cellular", mcc = "250", mnc = "01"), harness.context.current())
            harness.operator = ""
            assertEquals(FieldReportNetwork("none", "cellular"), harness.context.current())
        }

    @Test
    fun `a valid hint labels the same network until it expires`() =
        runTest {
            val harness = Harness(wifi, golden)

            harness.context.refreshHint(listOf(target))

            assertEquals(listOf(Triple(target.networkHintEndpoint!!, "cohort-a", 101L)), harness.fetches)
            assertEquals(hinted, harness.context.current())
            // observed_at 10:00 plus ttl_seconds 21600.
            harness.now = epoch("2026-11-19T16:00:00Z")
            assertEquals(FieldReportNetwork("none", "wifi"), harness.context.current())
        }

    @Test
    fun `a network change drops the hint for good`() =
        runTest {
            val harness = Harness(wifi, golden)
            harness.context.refreshHint(listOf(target))

            harness.network = otherWifi
            assertEquals(FieldReportNetwork("none", "wifi"), harness.context.current())
            harness.network = wifi
            assertEquals(FieldReportNetwork("none", "wifi"), harness.context.current())
        }

    @Test
    fun `a hint is discarded when the network changes during the request`() =
        runTest {
            val harness = Harness(wifi, golden)
            harness.duringFetch = { harness.network = otherWifi }

            harness.context.refreshHint(listOf(target))
            harness.network = wifi

            assertEquals(1, harness.fetches.size)
            assertEquals(FieldReportNetwork("none", "wifi"), harness.context.current())
        }

    @Test
    fun `no request on a VPN default network, without consent, or without a hint endpoint`() =
        runTest {
            val vpn = Harness(wifi.copy(vpn = true), golden)
            vpn.context.refreshHint(listOf(target))
            assertEquals(FieldReportNetwork("none", "other"), vpn.context.current())

            val noConsent = Harness(wifi, golden)
            noConsent.consent = false
            noConsent.context.refreshHint(listOf(target))

            val noEndpoint = Harness(wifi, golden)
            noEndpoint.context.refreshHint(listOf(target.copy(networkHintEndpoint = null)))

            assertEquals(0, vpn.fetches.size + noConsent.fetches.size + noEndpoint.fetches.size)
            assertEquals(FieldReportNetwork("none", "wifi"), noEndpoint.context.current())
        }

    @Test
    fun `a hint is fetched no more often than its ttl plus jitter`() =
        runTest {
            val harness = Harness(wifi, golden)
            harness.context.refreshHint(listOf(target))

            // ttl_seconds is 21600; the jitter adds at most a quarter of it.
            harness.now += 21_599_000
            harness.context.refreshHint(listOf(target))
            assertEquals(1, harness.fetches.size)

            harness.now += 21_600_000 / 4 + 2_000
            harness.context.refreshHint(listOf(target))
            assertEquals(2, harness.fetches.size)
        }

    @Test
    fun `any non-200 or invalid answer means no hint and waits an hour before the next request`() =
        runTest {
            val harness = Harness(wifi, null)
            harness.context.refreshHint(listOf(target))
            assertEquals(FieldReportNetwork("none", "wifi"), harness.context.current())

            harness.body = golden
            harness.now += 3_599_000
            harness.context.refreshHint(listOf(target))
            assertEquals(1, harness.fetches.size)

            harness.body = contractFile("negative/hint-extra-property.json").readText()
            harness.now += 3_600_000 / 4 + 2_000
            harness.context.refreshHint(listOf(target))
            assertEquals(2, harness.fetches.size)
            assertEquals(FieldReportNetwork("none", "wifi"), harness.context.current())
        }

    @Test
    fun `the hint request counts as tunnelled through a proxy or a loopback address`() {
        val call = OkHttpClient().newCall(Request.Builder().url("https://reports.example.test/").build())

        fun tunnelled(
            address: InetSocketAddress,
            proxy: Proxy,
        ): Boolean = AtomicBoolean(false).also { directOnlyListener(it).connectStart(call, address, proxy) }.get()

        val public = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 10)), 443)
        val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 1080)
        assertFalse(tunnelled(public, Proxy.NO_PROXY))
        assertTrue(tunnelled(loopback, Proxy.NO_PROXY))
        assertTrue(tunnelled(public, Proxy(Proxy.Type.SOCKS, loopback)))
    }
}
