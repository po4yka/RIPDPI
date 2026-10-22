package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class StrictPayloadSocksTest {
    private val payload = "x".repeat(32_768)

    private fun control(host: String = "control-a.example.test") =
        PayloadProbeControl(
            "https://$host/payload",
            payload.length,
            MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) },
        )

    private fun complete() = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\n\r\n$payload"

    @Test
    fun `strict complete HTTPS payload traverses configured SOCKS and verifies identity`() =
        runBlocking {
            StrictPayloadSocksFixture(listOf(complete())).use { fixture ->
                assertTrue(fixture.probe(control()))
                assertEquals(listOf("control-a.example.test"), fixture.hosts)
                assertTrue(fixture.requestHeaders.any { it.equals("Accept-Encoding: identity", ignoreCase = true) })
            }
        }

    @Test
    fun `untrusted control certificate cannot qualify a relay`() =
        runBlocking {
            StrictPayloadSocksFixture(listOf(complete())).use { fixture ->
                assertFalse(OkHttpRelayTcpProbe(3_000, control()).probe(fixture.endpoint, control().url).succeeded)
            }
        }

    @Test
    fun `strict HTTPS rejects truncated oversized wrong digest redirects and no content`() =
        runBlocking {
            val responses =
                listOf(
                    "HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\nshort",
                    "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + payload + "x",
                    "HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\n" + "y".repeat(32_768),
                    "HTTP/1.1 302 Found\r\n" +
                        "Location: https://control-b.example.test/payload\r\nContent-Length: 0\r\n\r\n",
                    "HTTP/1.1 204 No Content\r\n\r\n",
                    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n8000\r\n$payload\r\n",
                )
            for (response in responses) {
                StrictPayloadSocksFixture(listOf(response)).use { fixture ->
                    assertFalse(fixture.probe(control()))
                    assertEquals(1, fixture.hosts.size)
                }
            }
        }

    @Test
    fun `complete chunked payload succeeds only with terminating chunk`() =
        runBlocking {
            val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n8000\r\n$payload\r\n0\r\n\r\n"
            StrictPayloadSocksFixture(listOf(response)).use { assertTrue(it.probe(control())) }
        }

    @Test
    fun `second independent control can prove the same relay when first control fails`() =
        runBlocking {
            val responses = listOf("HTTP/1.1 503 Error\r\nContent-Length: 0\r\n\r\n", complete())
            StrictPayloadSocksFixture(responses).use { fixture ->
                val policy =
                    PayloadFailoverPolicy(
                        1,
                        "select",
                        listOf(control(), control("control-b.example.test")),
                        60,
                        2,
                        2,
                        2,
                        120,
                        900,
                    )
                val probe = fixture.httpProbe()
                assertTrue(probe.probePayload(fixture.endpoint, policy).succeeded)
                assertEquals(listOf("control-a.example.test", "control-b.example.test"), fixture.hosts)
            }
        }

    @Test
    fun `payload timeout and cancellation close stalled HTTPS sockets`() =
        runBlocking {
            val stalled = "HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\nx"
            StrictPayloadSocksFixture(listOf(stalled), stall = true).use { fixture ->
                assertFalse(fixture.probe(control(), 500))
                assertTrue(fixture.closed.await(2, TimeUnit.SECONDS))
            }
            StrictPayloadSocksFixture(listOf(stalled), stall = true).use { fixture ->
                val pending = async { fixture.probe(control(), 5_000) }
                yield()
                assertTrue(fixture.responseSent.await(3, TimeUnit.SECONDS))
                pending.cancelAndJoin()
                assertTrue(fixture.closed.await(2, TimeUnit.SECONDS))
            }
        }
}
