package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadProbeControl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class VerifiedPayloadTest {
    private val payload = ByteArray(32_768) { (it % 251).toByte() }
    private val control =
        PayloadProbeControl(
            "https://control.example.test/payload",
            payload.size,
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        )

    private fun response(
        bytes: ByteArray,
        status: Int = 200,
        encoding: String? = null,
    ): Response =
        Response
            .Builder()
            .request(Request.Builder().url(control.url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body(bytes.toResponseBody())
            .apply {
                if (encoding != null) header("Content-Encoding", encoding)
            }.build()

    @Test
    fun `only exact complete successful payload qualifies`() {
        response(payload).use { assertTrue(consumeVerifiedPayload(it, control)) }
        for (bytes in listOf(ByteArray(0), payload.copyOf(16_384), payload.copyOf(32_769), ByteArray(32_768))) {
            response(bytes).use { assertFalse(consumeVerifiedPayload(it, control)) }
        }
    }

    @Test
    fun `partial redirects errors and compressed responses never qualify`() {
        for (status in listOf(204, 206, 301, 302, 403, 503)) {
            response(payload, status).use { assertFalse(consumeVerifiedPayload(it, control)) }
        }
        response(payload, encoding = "gzip").use { assertFalse(consumeVerifiedPayload(it, control)) }
        response(payload).newBuilder().header("Content-Range", "bytes 0-32767/65536").build().use {
            assertFalse(consumeVerifiedPayload(it, control))
        }
    }
}
