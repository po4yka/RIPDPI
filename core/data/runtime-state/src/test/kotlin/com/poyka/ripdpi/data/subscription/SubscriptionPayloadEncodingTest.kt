package com.poyka.ripdpi.data.subscription

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionPayloadEncodingTest {
    @Test
    fun `malformed UTF-8 cannot change a JSON password`() {
        val prefix =
            """
            {"outbounds":[{"type":"shadowsocks","server":"relay.example","server_port":443,
            "method":"aes-128-gcm","password":"
            """.trimIndent()
        val suffix = """"}]}"""
        val bytes = prefix.toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + suffix.toByteArray()

        val result =
            runCatching { bytes.toResponseBody("application/json".toMediaType()).readBoundedSubscriptionPayload() }

        assertTrue(
            "Malformed bytes must not become a different password",
            result.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `an unmappable byte in a declared charset is rejected`() {
        val result =
            runCatching {
                byteArrayOf(0xE9.toByte())
                    .toResponseBody("text/plain; charset=us-ascii".toMediaType())
                    .readBoundedSubscriptionPayload()
            }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `valid charset text and literal replacement character survive without changes`() {
        val latin = byteArrayOf(0xE9.toByte())
        val unicode = "literal \uFFFD and ключ"

        assertEquals(
            "é",
            latin.toResponseBody("text/plain; charset=iso-8859-1".toMediaType()).readBoundedSubscriptionPayload().text,
        )
        assertEquals(
            unicode,
            unicode
                .toByteArray()
                .toResponseBody(
                    "application/json".toMediaType(),
                ).readBoundedSubscriptionPayload()
                .text,
        )
    }

    @Test
    fun `malformed UTF-8 in a base64 list is not a selectable credential`() {
        val bytes =
            "trojan://pa".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) +
                "@relay.example:443#node".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(bytes)

        val result = Base64SubscriptionParser.parse(encoded, "group")

        assertTrue(result.profiles.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `invalid encoding is a terminal bootstrap failure without another GET`() =
        runBlocking {
            val bytes =
                "trojan://pa".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) +
                    "@relay.example:443#node".toByteArray()
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .setHeader("Content-Type", "text/plain; charset=utf-8")
                        .body(Buffer().write(bytes))
                        .build(),
                )
                server.enqueue(MockResponse.Builder().code(410).build())
                server.start()
                val consumer = BootstrapConsumer(clockMillis = { 77L })
                val url = server.url("/bootstrap/encoding").toString()

                val first = consumer.consume(url, "group")

                assertTrue(first is BootstrapConsumeResult.ParseError)
                assertEquals(77L, (first as BootstrapConsumeResult.ParseError).consumedAtMillis)
                assertEquals(first, consumer.consume(url, "group"))
                assertEquals(1, server.requestCount)
            }
        }
}
