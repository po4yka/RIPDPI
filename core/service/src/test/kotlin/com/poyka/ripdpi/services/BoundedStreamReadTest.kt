package com.poyka.ripdpi.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class BoundedStreamReadTest {
    @Test
    fun `read stops at the byte limit`() {
        val bytes = ByteArray(10) { it.toByte() }

        assertArrayEquals(bytes, readBoundedBytes(ByteArrayInputStream(bytes), maxBytes = 10))
        assertThrows(IOException::class.java) { readBoundedBytes(ByteArrayInputStream(bytes), maxBytes = 9) }
    }

    @Test
    fun `read stops when the overall deadline has passed`() {
        val expiredDeadline = System.nanoTime() - 1

        assertThrows(IOException::class.java) {
            readBoundedBytes(ByteArrayInputStream(ByteArray(10)), maxBytes = 10, deadlineNanos = expiredDeadline)
        }
    }
}
