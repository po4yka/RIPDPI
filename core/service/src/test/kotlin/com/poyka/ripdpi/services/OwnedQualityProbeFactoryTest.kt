package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class OwnedQualityProbeFactoryTest {
    private val body = "x".repeat(32_768)
    private val digest =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    private val hosts = listOf("quality-a.example.test", "quality-b.example.test")

    @Test
    fun `generated certificate qualifies exact owned controls through real SOCKS TLS and body verification`() =
        runBlocking {
            for (host in hosts) {
                assertTrue(measure(host, 49_173, "/quality-payload", hosts))
            }
        }

    @Test
    fun `fixture trust does not extend to other host port or path and retains hostname checks`() =
        runBlocking {
            assertFalse(measure("control-a.example.test", 49_173, "/quality-payload", listOf("control-a.example.test")))
            assertFalse(measure(hosts.first(), 49_174, "/quality-payload", hosts))
            assertFalse(measure(hosts.first(), 49_173, "/other", hosts))
            assertFalse(measure(hosts.first(), 49_173, "/quality-payload", listOf("unrelated.example.test")))
        }

    @Test
    fun `a different generated certificate remains untrusted`() =
        runBlocking {
            StrictPayloadSocksFixture(emptyList()).use { unrelated ->
                assertFalse(measure(hosts.first(), 49_173, "/quality-payload", hosts, unrelated.certificateBase64))
            }
        }

    private suspend fun measure(
        host: String,
        port: Int,
        path: String,
        names: List<String>,
        otherCertificate: String? = null,
    ): Boolean {
        val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
        return StrictPayloadSocksFixture(listOf(response), certificateNames = names).use { fixture ->
            val controls =
                listOf(host, "unused.example.test").map {
                    PayloadProbeControl("https://$it:$port$path", body.length, digest)
                }
            val policy = PayloadFailoverPolicy(2, "select", controls, 30, 1, 2, 2, 30, 60)
            OwnedQualityProbeFactory
                .http({ otherCertificate ?: fixture.certificateBase64 }, { 49_173 })
                .probePayload(fixture.endpoint, policy)
                .succeeded
        }
    }
}
