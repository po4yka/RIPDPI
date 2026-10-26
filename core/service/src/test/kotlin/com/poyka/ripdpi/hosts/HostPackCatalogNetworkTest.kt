package com.poyka.ripdpi.hosts

import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class HostPackCatalogNetworkTest {
    private val bytes = ByteArray(16) { it.toByte() }

    @Test
    fun `body within the limit is returned whole`() {
        assertArrayEquals(bytes, bytes.toResponseBody().readBoundedHostPackBody(maxBytes = 16))
    }

    @Test
    fun `declared length above the limit is rejected before reading`() {
        assertThrows(IOException::class.java) { bytes.toResponseBody().readBoundedHostPackBody(maxBytes = 15) }
    }

    @Test
    fun `body without a declared length stops at the limit`() {
        val chunked =
            bytes
                .inputStream()
                .source()
                .buffer()
                .asResponseBody(contentLength = -1L)

        assertThrows(IOException::class.java) { chunked.readBoundedHostPackBody(maxBytes = 15) }
    }

    @Test
    fun `catalog body streams to the file and returns its digest`() {
        val target = File.createTempFile("host-pack-test-", ".dat")
        try {
            val digest = bytes.toResponseBody().writeBoundedHostPackBody(target, maxBytes = 16)

            assertArrayEquals(bytes, target.readBytes())
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, digest)
        } finally {
            target.delete()
        }
    }

    @Test
    fun `catalog body without a declared length stops streaming at the limit`() {
        val target = File.createTempFile("host-pack-test-", ".dat")
        try {
            val chunked =
                bytes
                    .inputStream()
                    .source()
                    .buffer()
                    .asResponseBody(contentLength = -1L)

            assertThrows(IOException::class.java) { chunked.writeBoundedHostPackBody(target, maxBytes = 15) }
        } finally {
            target.delete()
        }
    }
}
