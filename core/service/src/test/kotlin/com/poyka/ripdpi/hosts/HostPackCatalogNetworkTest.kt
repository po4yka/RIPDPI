package com.poyka.ripdpi.hosts

import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

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
}
